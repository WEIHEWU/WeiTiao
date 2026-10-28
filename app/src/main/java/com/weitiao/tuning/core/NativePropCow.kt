package com.weitiao.tuning.core

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Native Bionic libc 系统属性写时复制（Copy-On-Write / COW）与 Radix Trie 字典树注入引擎。
 *
 * 背景与原理：
 * Android 系统属性（ro.*, persist.*）通过 /dev/__properties__/ 共享内存文件（prop_area）映射至进程地址空间。
 * 第三方加固壳（如腾讯御安全/乐固、网易易盾、梆梆）以及主流应用 NDK 层（如酷安、Bilibili、QQ、安兔兔）
 * 会直接调用 libc.so 的 __system_property_find() / __system_property_get() / __system_property_read_callback()
 * 遍历 prop_area 内部的 Radix Ternary Search Tree（基数三叉搜索树）读取硬件属性。
 *
 * 核心突破（超越常规属性覆盖）：
 * 1. 原位覆写（In-place Value Patch）：针对宿主 ROM 已存在的属性（如 ro.product.model），按 Bionic 规范
 *    更新 prop_info 的 92 字节内联值与 serial 序列号（严格清除 bit 16 kLongFlag 防止越界 SIGSEGV）。
 * 2. 跨品牌缺失节点注入（Bump-Allocator Radix Trie Insertion）：在三星 OneUI 等 ROM 上，跨品牌营销名属性
 *    （如小米 ro.product.marketname、OPPO/一加 ro.vendor.oplus.market.name、vivo ro.vivo.market.name、
 *    华为 ro.config.marketing_name）在底层共享内存中根本不存在。本引擎直接复用 prop_area 尾部空闲容量
 *    （128KB 映射区通常剩余 >90KB），在 COW 私有内存页中动态分配 prop_bt 字典树节点与 prop_info 结构体，
 *    将跨品牌属性完整挂载进 Bionic 基数树！
 * 3. 多上下文智能路由（Multi-Context Routing）：同时向主属性区（build_prop / vendor / odm）与兜底属性区
 *    （default_prop）注入缺失节点，无论宿主 SELinux property_info 将未知前缀路由至何处，Native 层均 100% 命中。
 */
object NativePropCow {

    private const val TAG = "WeiTiaoNativeCow"

    internal const val PROP_AREA_MAGIC: Int = 0x504f5250 // "PROP"
    internal const val PROP_AREA_VERSION: Int = 0xfc6ed0ab.toInt()
    internal const val DATA_OFFSET: Long = 128L
    internal const val PROP_BT_HEADER_SIZE: Int = 20
    internal const val PROP_INFO_VALUE_OFFSET: Long = 4L
    internal const val PROP_INFO_NAME_OFFSET: Long = 96L
    internal const val PROP_VALUE_MAX: Int = 91
    private const val MAX_TRIE_STEPS: Int = 4096

    internal interface MemoryAccessor {
        fun getByte(addr: Long): Byte
        fun putByte(addr: Long, value: Byte)
        fun getIntLE(addr: Long): Int
        fun putIntLE(addr: Long, value: Int)
    }

    internal object UnsafeMemoryAccessor : MemoryAccessor {
        private val unsafe: sun.misc.Unsafe by lazy {
            val field = runCatching {
                sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            }.getOrElse {
                runCatching { sun.misc.Unsafe::class.java.getDeclaredField("THE_ONE") }
                    .getOrElse { sun.misc.Unsafe::class.java.getDeclaredField("u") }
            }.apply { isAccessible = true }
            field.get(null) as sun.misc.Unsafe
        }

        override fun getByte(addr: Long): Byte = unsafe.getByte(addr)
        override fun putByte(addr: Long, value: Byte) = unsafe.putByte(addr, value)
        override fun getIntLE(addr: Long): Int = unsafe.getInt(addr)
        override fun putIntLE(addr: Long, value: Int) = unsafe.putInt(addr, value)
    }

    internal data class PropMapArea(
        val start: Long,
        val end: Long,
        val length: Long,
        val path: String,
        val isWritable: Boolean = false,
    )

    internal data class PreparedProp(
        val name: String,
        val keyBytes: ByteArray,
        val nameWithNull: ByteArray,
        val valueBytes: ByteArray,
    )

    internal data class PatchResult(
        val propInfoAddr: Long,
        val insertedNewNode: Boolean,
    )

    private val remappedAreas = java.util.concurrent.CopyOnWriteArrayList<PropMapArea>()

    /**
     * 判断是否是可扫描/可注入的系统属性数据存储区（排除全局序列号与属性上下文索引树）
     */
    internal fun isRelevantPropArea(path: String): Boolean {
        val p = path.lowercase()
        if (!p.contains("/dev/__properties__/")) return false
        return !p.endsWith("/properties_serial") && !p.endsWith("/property_info")
    }

    /**
     * 针对当前进程应用 COW 内存伪装补丁（含原位覆写 + 缺失属性 Radix Trie 注入）
     * @param targetProps 需要改写的键值对表（如 ro.product.model -> iPhone Duo 等）
     * @return 成功改写与注入的内存属性条目总数
     */
    fun applyCowSpoof(targetProps: Map<String, String>): Int {
        if (targetProps.isEmpty()) return 0

        val allMappings = findAllPropMappings()
        val discovered = allMappings.filter { isRelevantPropArea(it.path) }
        val serialMapping = allMappings.firstOrNull { it.path.lowercase().endsWith("/properties_serial") }

        val areas = if (discovered.isNotEmpty()) {
            val byStart = LinkedHashMap<Long, PropMapArea>()
            for (a in remappedAreas) {
                if (isRelevantPropArea(a.path)) byStart[a.start] = a
            }
            for (a in discovered) {
                val existing = byStart[a.start]
                if (existing == null || a.isWritable) {
                    byStart[a.start] = a
                }
            }
            byStart.values.toList()
        } else {
            remappedAreas.filter { isRelevantPropArea(it.path) }
        }

        if (areas.isEmpty()) {
            Log.w(TAG, "未在 /proc/self/maps 中找到相关系统属性映射区域")
            return 0
        }

        val preparedProps = prepareProps(targetProps)
        if (preparedProps.isEmpty()) return 0

        val mem = UnsafeMemoryAccessor
        var totalPatched = 0
        var totalInserted = 0
        var touchedAreas = 0
        val patchedKeys = HashSet<String>(preparedProps.size)
        var modelArea: PropMapArea? = null

        // 第一阶段：在所有已映射 prop_area 中通过 Radix Trie + 线性扫描定位已存在的属性并原位改写
        for (area in areas) {
            val matchedHits = findMatchedPropsInArea(mem, area, preparedProps)
            if (matchedHits.isEmpty()) continue

            if (!ensureAreaWritable(area)) continue
            touchedAreas++

            for ((propInfoAddr, prop) in matchedHits) {
                writePropValue(mem, propInfoAddr, prop.valueBytes)
                patchedKeys.add(prop.name)
                if (prop.name == "ro.product.model" && modelArea == null) {
                    modelArea = area
                }
                totalPatched++
            }
            bumpAreaSerial(mem, area.start)
        }

        // 第二阶段：针对宿主 ROM 不存在的跨品牌/跨分区属性，直接通过 Bump Allocator 注入 Radix Trie 节点
        val missingProps = preparedProps.filter { it.name !in patchedKeys }
        if (missingProps.isNotEmpty()) {
            for (prop in missingProps) {
                val targetAreas = selectTargetAreasForInsertion(prop.name, areas, modelArea)
                for (area in targetAreas) {
                    if (!ensureAreaWritable(area)) continue
                    val res = insertOrUpdatePropInTrie(mem, area, prop)
                    if (res != null) {
                        patchedKeys.add(prop.name)
                        if (res.insertedNewNode) totalInserted++ else totalPatched++
                    }
                }
            }
        }

        // 第三阶段：如果发生了任何属性改写或注入，COW 重映射并递增全局 properties_serial，强制刷新缓存
        if ((totalPatched > 0 || totalInserted > 0) && serialMapping != null) {
            if (ensureAreaWritable(serialMapping)) {
                bumpAreaSerial(mem, serialMapping.start)
            }
        }

        Log.i(
            TAG,
            "Native COW 引擎执行完毕: 扫描 ${areas.size} 个属性区，原位改写 $totalPatched 处，字典树新增注入 $totalInserted 处 (覆盖 ${patchedKeys.size}/${preparedProps.size} 项键)",
        )
        return totalPatched + totalInserted
    }

    internal fun prepareProps(targetProps: Map<String, String>): List<PreparedProp> {
        return targetProps.mapNotNull { (key, value) ->
            if (key.isBlank() || value.isBlank()) return@mapNotNull null
            val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
            val keyWithNull = ByteArray(keyBytes.size + 1)
            System.arraycopy(keyBytes, 0, keyWithNull, 0, keyBytes.size)
            val valBytes = value.toByteArray(StandardCharsets.UTF_8)
            PreparedProp(key, keyBytes, keyWithNull, valBytes)
        }
    }

    /**
     * 为宿主 ROM 缺失的属性选择最佳注入目标区。
     * 同时覆盖前缀匹配分区（build_prop / vendor / odm）与兜底分区（default_prop），
     * 确保无论 SELinux property_info 如何路由均能 100% 被 __system_property_find 检索到。
     */
    internal fun selectTargetAreasForInsertion(
        key: String,
        areas: List<PropMapArea>,
        fallbackArea: PropMapArea?,
    ): List<PropMapArea> {
        val selected = LinkedHashSet<PropMapArea>(3)
        val lowerKey = key.lowercase()

        val wantsVendor = lowerKey.contains("vendor") || lowerKey.contains("qti") ||
            lowerKey.startsWith("ro.hardware") || lowerKey.startsWith("ro.board")
        val wantsOdm = lowerKey.contains("odm")
        val wantsBootimage = lowerKey.contains("bootimage")
        val wantsProduct = lowerKey.contains(".product.")
        val wantsSystemExt = lowerKey.contains("system_ext")

        for (area in areas) {
            val p = area.path.lowercase()
            when {
                wantsVendor && (p.contains("build_vendor_prop") || p.contains("vendor_default_prop") || p.contains("vendor_build_prop")) ->
                    selected.add(area)
                wantsOdm && (p.contains("build_odm_prop") || p.contains("odm_default_prop") || p.contains("odm_build_prop")) ->
                    selected.add(area)
                wantsBootimage && p.contains("bootimage") ->
                    selected.add(area)
                wantsProduct && p.contains("product_build_prop") ->
                    selected.add(area)
                wantsSystemExt && p.contains("system_ext") ->
                    selected.add(area)
            }
        }

        // 始终加入 build_prop 与 default_prop 双保险
        for (area in areas) {
            val p = area.path.lowercase()
            if (p.endsWith("/u:object_r:build_prop:s0") || p.endsWith(":build_prop:s0") || p.endsWith("/build_prop")) {
                selected.add(area)
            } else if (p.endsWith("/u:object_r:default_prop:s0") || p.endsWith(":default_prop:s0") || p.endsWith("/default_prop")) {
                selected.add(area)
            }
        }

        if (selected.isEmpty()) {
            if (fallbackArea != null) {
                selected.add(fallbackArea)
            } else if (areas.isNotEmpty()) {
                selected.add(areas.first())
            }
        }
        return selected.toList()
    }

    private fun ensureAreaWritable(area: PropMapArea): Boolean {
        if (area.isWritable || remappedAreas.any { it.start == area.start && it.isWritable }) {
            return true
        }
        if (remapAsCow(area)) {
            if (remappedAreas.none { it.start == area.start }) {
                remappedAreas.add(area.copy(isWritable = true))
            }
            return true
        }
        return false
    }

    private fun findAllPropMappings(): List<PropMapArea> {
        val results = ArrayList<PropMapArea>(24)
        val mapsFile = File("/proc/self/maps")
        if (!mapsFile.canRead()) return results

        runCatching {
            mapsFile.forEachLine { line ->
                if (line.contains("/dev/__properties__/")) {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 6) {
                        val perms = parts[1]
                        if (!perms.startsWith("r")) return@forEachLine
                        val path = parts.last()
                        val range = parts[0].split("-")
                        if (range.size == 2) {
                            val start = range[0].toLongOrNull(16) ?: return@forEachLine
                            val end = range[1].toLongOrNull(16) ?: return@forEachLine
                            if (end > start) {
                                results.add(
                                    PropMapArea(
                                        start = start,
                                        end = end,
                                        length = end - start,
                                        path = path,
                                        isWritable = perms.startsWith("rw"),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "读取 /proc/self/maps 失败: $it")
        }
        return results
    }

    private fun remapAsCow(area: PropMapArea): Boolean {
        val fd = runCatching {
            Os.open(area.path, OsConstants.O_RDONLY, 0)
        }.getOrNull()

        if (fd != null) {
            try {
                Os.mmap(
                    area.start,
                    area.length,
                    OsConstants.PROT_READ or OsConstants.PROT_WRITE,
                    OsConstants.MAP_PRIVATE or OsConstants.MAP_FIXED,
                    fd,
                    0,
                )
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "mmap 文件 COW 重映射失败 [${area.path}]: $e")
            } finally {
                runCatching { Os.close(fd) }
            }
        }

        // SELinux 拒绝直接 open 属性文件时的兜底方案：
        // 先将进程内已有的只读共享映射数据备份到内存，再用匿名私有页覆盖原地址并拷回数据
        return runCatching {
            val len = area.length.toInt()
            if (len <= 0 || len > 4 * 1024 * 1024) return false
            val snapshot = ByteArray(len)
            val mem = UnsafeMemoryAccessor
            for (i in 0 until len) {
                snapshot[i] = mem.getByte(area.start + i)
            }
            val mapAnonFlag = runCatching {
                OsConstants::class.java.getField("MAP_ANONYMOUS").getInt(null)
            }.getOrElse { 0x20 }
            Os.mmap(
                area.start,
                area.length,
                OsConstants.PROT_READ or OsConstants.PROT_WRITE,
                OsConstants.MAP_PRIVATE or OsConstants.MAP_FIXED or mapAnonFlag,
                java.io.FileDescriptor(),
                0,
            )
            for (i in 0 until len) {
                mem.putByte(area.start + i, snapshot[i])
            }
            true
        }.onFailure { e ->
            Log.w(TAG, "mmap 匿名页 COW 兜底重映射失败 [${area.path}]: $e")
        }.getOrDefault(false)
    }

    /**
     * Bionic cmp_prop_name 规范实现：
     * 先比较片段长度（短者在前，长者在后），长度相同时按无符号字节字典序比较。
     */
    internal fun cmpPropName(
        mem: MemoryAccessor,
        keyBytes: ByteArray,
        segStart: Int,
        segLen: Int,
        nodeAddr: Long,
        nodeNameLen: Int,
    ): Int {
        if (segLen < nodeNameLen) return -1
        if (segLen > nodeNameLen) return 1
        val nameBase = nodeAddr + PROP_BT_HEADER_SIZE
        for (i in 0 until segLen) {
            val a = keyBytes[segStart + i].toInt() and 0xFF
            val b = mem.getByte(nameBase + i).toInt() and 0xFF
            if (a != b) return if (a < b) -1 else 1
        }
        return 0
    }

    /**
     * 在 Bionic prop_area 的 Radix Ternary Search Tree 中查找属性对应的 prop_info 绝对地址。
     * @return 找到则返回 prop_info 绝对地址，未找到或结构异常返回 0L
     */
    internal fun findPropInTrie(
        mem: MemoryAccessor,
        area: PropMapArea,
        keyBytes: ByteArray,
    ): Long {
        if (area.length <= DATA_OFFSET + PROP_BT_HEADER_SIZE || keyBytes.isEmpty()) return 0L
        val magic = runCatching { mem.getIntLE(area.start + 8) }.getOrDefault(0)
        if (magic != PROP_AREA_MAGIC) return 0L

        val dataCapacity = area.length - DATA_OFFSET
        val bytesUsed = (mem.getIntLE(area.start).toLong() and 0xFFFFFFFFL).coerceAtMost(dataCapacity)
        if (bytesUsed < PROP_BT_HEADER_SIZE) return 0L

        val dataBase = area.start + DATA_OFFSET
        var currAddr = dataBase
        var pos = 0
        val totalLen = keyBytes.size

        while (pos < totalLen) {
            // 跳过连续的 '.'
            if (keyBytes[pos] == '.'.code.toByte()) {
                pos++
                continue
            }
            var dot = pos
            while (dot < totalLen && keyBytes[dot] != '.'.code.toByte()) {
                dot++
            }
            val segLen = dot - pos

            val childrenRel = mem.getIntLE(currAddr + 16).toLong() and 0xFFFFFFFFL
            if (childrenRel == 0L || childrenRel + PROP_BT_HEADER_SIZE > bytesUsed) return 0L

            var nodeRel = childrenRel
            var matchedAddr = 0L
            var steps = 0
            while (nodeRel != 0L && steps++ < MAX_TRIE_STEPS) {
                if (nodeRel + PROP_BT_HEADER_SIZE > bytesUsed) return 0L
                val nodeAddr = dataBase + nodeRel
                val nodeNameLen = mem.getIntLE(nodeAddr)
                if (nodeNameLen < 0 || nodeRel + PROP_BT_HEADER_SIZE + nodeNameLen > bytesUsed) return 0L

                val cmp = cmpPropName(mem, keyBytes, pos, segLen, nodeAddr, nodeNameLen)
                nodeRel = when {
                    cmp == 0 -> {
                        matchedAddr = nodeAddr
                        break
                    }
                    cmp < 0 -> mem.getIntLE(nodeAddr + 8).toLong() and 0xFFFFFFFFL
                    else -> mem.getIntLE(nodeAddr + 12).toLong() and 0xFFFFFFFFL
                }
            }

            if (matchedAddr == 0L) return 0L
            currAddr = matchedAddr
            pos = dot + 1
        }

        val propRel = mem.getIntLE(currAddr + 4).toLong() and 0xFFFFFFFFL
        if (propRel == 0L || propRel + PROP_INFO_NAME_OFFSET > bytesUsed) return 0L
        return dataBase + propRel
    }

    /**
     * 在已 COW 重映射的 prop_area 中按 Bionic 规范查找或动态插入属性节点。
     * 若路径上的 prop_bt 节点或终端 prop_info 缺失，使用 prop_area 顶部的 bytes_used 指针执行 4 字节对齐分配。
     */
    internal fun insertOrUpdatePropInTrie(
        mem: MemoryAccessor,
        area: PropMapArea,
        prop: PreparedProp,
    ): PatchResult? {
        val keyBytes = prop.keyBytes
        if (area.length <= DATA_OFFSET + 24 || keyBytes.isEmpty()) return null
        val magic = runCatching { mem.getIntLE(area.start + 8) }.getOrDefault(0)
        if (magic != PROP_AREA_MAGIC) return null

        val dataCapacity = area.length - DATA_OFFSET
        var bytesUsed = mem.getIntLE(area.start).toLong() and 0xFFFFFFFFL
        if (bytesUsed < PROP_BT_HEADER_SIZE || bytesUsed > dataCapacity) return null

        val dataBase = area.start + DATA_OFFSET

        fun allocPropBt(segStart: Int, segLen: Int): Long {
            val alignedOffset = (bytesUsed + 3L) and 3L.inv()
            val allocSize = ((segLen and 3.inv()) + 24).toLong()
            if (alignedOffset + allocSize > dataCapacity) return 0L

            val nodeAddr = dataBase + alignedOffset
            for (i in 0L until allocSize) {
                mem.putByte(nodeAddr + i, 0)
            }
            mem.putIntLE(nodeAddr, segLen)
            for (i in 0 until segLen) {
                mem.putByte(nodeAddr + PROP_BT_HEADER_SIZE + i, keyBytes[segStart + i])
            }
            mem.putByte(nodeAddr + PROP_BT_HEADER_SIZE + segLen, 0)

            bytesUsed = alignedOffset + allocSize
            mem.putIntLE(area.start, bytesUsed.toInt())
            return alignedOffset
        }

        var currAddr = dataBase
        var pos = 0
        val totalLen = keyBytes.size

        while (pos < totalLen) {
            if (keyBytes[pos] == '.'.code.toByte()) {
                pos++
                continue
            }
            var dot = pos
            while (dot < totalLen && keyBytes[dot] != '.'.code.toByte()) {
                dot++
            }
            val segLen = dot - pos

            val childrenFieldAddr = currAddr + 16
            val childrenRel = mem.getIntLE(childrenFieldAddr).toLong() and 0xFFFFFFFFL
            if (childrenRel == 0L) {
                val newRel = allocPropBt(pos, segLen)
                if (newRel == 0L) return null
                mem.putIntLE(childrenFieldAddr, newRel.toInt())
                currAddr = dataBase + newRel
            } else {
                var nodeRel = childrenRel
                var matchedAddr = 0L
                var steps = 0
                while (nodeRel != 0L && steps++ < MAX_TRIE_STEPS) {
                    if (nodeRel + PROP_BT_HEADER_SIZE > bytesUsed) return null
                    val nodeAddr = dataBase + nodeRel
                    val nodeNameLen = mem.getIntLE(nodeAddr)
                    if (nodeNameLen < 0 || nodeRel + PROP_BT_HEADER_SIZE + nodeNameLen > bytesUsed) return null

                    val cmp = cmpPropName(mem, keyBytes, pos, segLen, nodeAddr, nodeNameLen)
                    if (cmp == 0) {
                        matchedAddr = nodeAddr
                        break
                    } else if (cmp < 0) {
                        val leftFieldAddr = nodeAddr + 8
                        val leftRel = mem.getIntLE(leftFieldAddr).toLong() and 0xFFFFFFFFL
                        if (leftRel == 0L) {
                            val newRel = allocPropBt(pos, segLen)
                            if (newRel == 0L) return null
                            mem.putIntLE(leftFieldAddr, newRel.toInt())
                            matchedAddr = dataBase + newRel
                            break
                        }
                        nodeRel = leftRel
                    } else {
                        val rightFieldAddr = nodeAddr + 12
                        val rightRel = mem.getIntLE(rightFieldAddr).toLong() and 0xFFFFFFFFL
                        if (rightRel == 0L) {
                            val newRel = allocPropBt(pos, segLen)
                            if (newRel == 0L) return null
                            mem.putIntLE(rightFieldAddr, newRel.toInt())
                            matchedAddr = dataBase + newRel
                            break
                        }
                        nodeRel = rightRel
                    }
                }
                if (matchedAddr == 0L) return null
                currAddr = matchedAddr
            }
            pos = dot + 1
        }

        val propFieldAddr = currAddr + 4
        val existingPropRel = mem.getIntLE(propFieldAddr).toLong() and 0xFFFFFFFFL
        if (existingPropRel != 0L) {
            if (existingPropRel + PROP_INFO_NAME_OFFSET > bytesUsed) return null
            val propInfoAddr = dataBase + existingPropRel
            writePropValue(mem, propInfoAddr, prop.valueBytes)
            bumpAreaSerial(mem, area.start)
            return PatchResult(propInfoAddr = propInfoAddr, insertedNewNode = false)
        }

        // 终端节点尚无 prop_info，在 bytes_used 处分配新 prop_info 并挂载至 currNode.prop
        val keyLen = keyBytes.size
        val alignedPropRel = (bytesUsed + 3L) and 3L.inv()
        val propAllocSize = ((keyLen and 3.inv()) + 100).toLong()
        if (alignedPropRel + propAllocSize > dataCapacity) return null

        val propInfoAddr = dataBase + alignedPropRel
        for (i in 0L until propAllocSize) {
            mem.putByte(propInfoAddr + i, 0)
        }

        val valLen = minOf(prop.valueBytes.size, PROP_VALUE_MAX)
        for (i in 0 until valLen) {
            mem.putByte(propInfoAddr + PROP_INFO_VALUE_OFFSET + i, prop.valueBytes[i])
        }
        for (i in 0 until keyLen) {
            mem.putByte(propInfoAddr + PROP_INFO_NAME_OFFSET + i, keyBytes[i])
        }
        mem.putByte(propInfoAddr + PROP_INFO_NAME_OFFSET + keyLen, 0)

        // 新建 prop_info 的初始 serial：高 8 位为内联值字节长度，kLongFlag 清零，低 16 位偶数计数器为 0
        mem.putIntLE(propInfoAddr, valLen shl 24)

        bytesUsed = alignedPropRel + propAllocSize
        mem.putIntLE(area.start, bytesUsed.toInt())
        mem.putIntLE(propFieldAddr, alignedPropRel.toInt())
        bumpAreaSerial(mem, area.start)

        return PatchResult(propInfoAddr = propInfoAddr, insertedNewNode = true)
    }

    /**
     * 在属性内存区内只读定位所有匹配的目标属性及其 prop_info 结构体起始地址。
     * 优先通过 O(keyLen) 的 Bionic Radix Trie 精确查找；若存在非标准碎片节点则回退至修正后的线性扫描。
     */
    internal fun findMatchedPropsInArea(
        mem: MemoryAccessor,
        area: PropMapArea,
        props: List<PreparedProp>,
    ): List<Pair<Long, PreparedProp>> {
        val hits = ArrayList<Pair<Long, PreparedProp>>(8)
        val seenAddrs = HashSet<Long>(8)

        // 1. Bionic Radix Trie 精确查找
        for (prop in props) {
            val addr = findPropInTrie(mem, area, prop.keyBytes)
            if (addr != 0L && seenAddrs.add(addr)) {
                hits.add(addr to prop)
            }
        }
        if (hits.isNotEmpty()) return hits

        // 2. 线性扫描兜底（注意：bytes_used 是相对于 DATA_OFFSET(128) 的偏移，因此有效扫描上限为 128 + bytes_used）
        val rawBytesUsed = runCatching { mem.getIntLE(area.start).toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        val totalUsedBytes = DATA_OFFSET + rawBytesUsed
        val scanLength = if (totalUsedBytes in (DATA_OFFSET + 20)..area.length) totalUsedBytes else area.length
        val start = area.start

        for (prop in props) {
            val targetBytes = prop.nameWithNull
            val targetLen = targetBytes.size
            val firstByte = targetBytes[0]
            val limit = start + scanLength - targetLen
            var curr = start + DATA_OFFSET + PROP_INFO_NAME_OFFSET
            while (curr <= limit) {
                if (mem.getByte(curr) == firstByte) {
                    var match = true
                    for (i in 1 until targetLen) {
                        if (mem.getByte(curr + i) != targetBytes[i]) {
                            match = false
                            break
                        }
                    }
                    if (match && mem.getByte(curr - 1) == 0.toByte()) {
                        val propInfoAddr = curr - PROP_INFO_NAME_OFFSET
                        if (propInfoAddr >= start + DATA_OFFSET && seenAddrs.add(propInfoAddr)) {
                            hits.add(propInfoAddr to prop)
                        }
                    }
                }
                curr++
            }
        }
        return hits
    }

    internal fun writePropValue(mem: MemoryAccessor, propInfoAddr: Long, newBytes: ByteArray) {
        val oldSerial = mem.getIntLE(propInfoAddr)
        val newLen = minOf(newBytes.size, PROP_VALUE_MAX)

        // 1. 设置最低位为 1（dirty bit），同时清除第 16 位 kLongFlag (0x00010000)，标明当前节点正在写入中
        val dirtyCounter = ((oldSerial and 0xFFFF) or 1)
        mem.putIntLE(propInfoAddr, dirtyCounter)

        // 2. 覆写 92 字节内联 value 区域，剩余字节填充 null
        for (i in 0 until 92) {
            val b = if (i < newLen) newBytes[i] else 0.toByte()
            mem.putByte(propInfoAddr + PROP_INFO_VALUE_OFFSET + i, b)
        }

        // 3. 更新序列号：高 8 位为字符串实际长度，低 16 位为偶数递增计数器，第 16~23 位严格清零（严禁保留 kLongFlag 导致 SIGSEGV）
        val counter = ((oldSerial and 0xFFFF) + 2) and 0xFFFF
        val newSerial = (newLen shl 24) or counter
        mem.putIntLE(propInfoAddr, newSerial)
    }

    private fun bumpAreaSerial(mem: MemoryAccessor, areaStart: Long) {
        runCatching {
            val oldAreaSerial = mem.getIntLE(areaStart + 4)
            mem.putIntLE(areaStart + 4, oldAreaSerial + 1)
        }
    }
}
