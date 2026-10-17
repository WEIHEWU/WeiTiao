package com.weitiao.tuning.core

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Native Bionic libc 系统属性写时复制（Copy-On-Write / COW）内存补丁引擎。
 *
 * 背景与原理：
 * Android 系统属性（ro.*, persist.*）通过 /dev/__properties__/ 共享内存文件映射至进程地址空间。
 * 第三方加固壳（如腾讯御安全/乐固）以及高性能 NDK 库（如酷安、QQ）会绕过 Java 虚拟机，直接调用
 * libc 的 __system_property_get() 或 __system_property_read_callback() 读取底层硬件属性。
 *
 * 若仅在 Java 层 Hook Build 类与 SystemProperties，会导致“Java 显示 Apple，Native 读出 Samsung”
 * 的双层矛盾，从而触发安全风控降级或识别失败。
 *
 * 本引擎通过在目标应用加载时读取 /proc/self/maps，针对关键机型相关属性映射调用
 * mmap(MAP_PRIVATE | MAP_FIXED)，将共享内存替换为当前进程独占的 COW 可写物理内存页。
 * 随后直接覆写 prop_info 字典树节点内的属性值与版本号序列（serial）。
 *
 * 效果：
 * 1. Native libc 无论通过 __system_property_get 还是 __system_property_read_callback 读取，
 *    读到的均为伪装后的品牌、型号与厂商（如 Apple / iPhone 16 Pro Max）。
 * 2. 纯私有内存修改，不触碰系统全局，无需持久驻留，完美与 Java 层对齐。
 */
object NativePropCow {

    private const val TAG = "WeiTiaoNativeCow"

    private data class PropMapArea(
        val start: Long,
        val end: Long,
        val length: Long,
        val path: String,
    )

    private val unsafe: sun.misc.Unsafe by lazy {
        val field = runCatching {
            sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        }.getOrElse {
            runCatching { sun.misc.Unsafe::class.java.getDeclaredField("THE_ONE") }
                .getOrElse { sun.misc.Unsafe::class.java.getDeclaredField("u") }
        }.apply { isAccessible = true }
        field.get(null) as sun.misc.Unsafe
    }

    /**
     * 判断是否是机型、品牌相关的系统属性存储区
     */
    private fun isRelevantPropArea(path: String): Boolean {
        val p = path.lowercase()
        return p.contains("build_prop") ||
            p.contains("build_vendor_prop") ||
            p.contains("build_odm_prop") ||
            p.contains("build_product_prop") ||
            p.contains("build_system_prop") ||
            p.contains("build_attestation_prop") ||
            p.contains("default_prop")
    }

    /**
     * 针对当前进程应用 COW 内存伪装补丁
     * @param targetProps 需要改写的键值对表（如 ro.product.model -> iPhone 16 Pro Max 等）
     * @return 成功改写的内存属性条目总数
     */
    fun applyCowSpoof(targetProps: Map<String, String>): Int {
        if (targetProps.isEmpty()) return 0

        val areas = findPropMappings()
        if (areas.isEmpty()) {
            Log.w(TAG, "未在 /proc/self/maps 中找到相关系统属性映射区域")
            return 0
        }

        // 预将目标键与值转为 UTF-8 字节数组，极大降低内存比对开销
        val preparedProps = targetProps.mapNotNull { (key, value) ->
            if (key.isBlank() || value.isBlank()) return@mapNotNull null
            val keyBytes = (key + "\u0000").toByteArray(StandardCharsets.UTF_8)
            val valBytes = value.toByteArray(StandardCharsets.UTF_8)
            PreparedProp(key, keyBytes, valBytes)
        }
        if (preparedProps.isEmpty()) return 0

        var totalPatched = 0
        for (area in areas) {
            val remapped = remapAsCow(area)
            if (!remapped) continue

            for (prop in preparedProps) {
                if (patchPropertyInArea(area, prop)) {
                    totalPatched++
                }
            }
        }

        Log.i(TAG, "Native COW 引擎执行完毕: 扫描 ${areas.size} 个关键属性区，成功改写 $totalPatched 处 Native 属性")
        return totalPatched
    }

    private data class PreparedProp(
        val name: String,
        val nameWithNull: ByteArray,
        val valueBytes: ByteArray,
    )

    private fun findPropMappings(): List<PropMapArea> {
        val results = ArrayList<PropMapArea>(8)
        val mapsFile = File("/proc/self/maps")
        if (!mapsFile.canRead()) return results

        runCatching {
            mapsFile.forEachLine { line ->
                if (line.contains("/dev/__properties__/")) {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 6) {
                        val path = parts.last()
                        if (isRelevantPropArea(path)) {
                            val range = parts[0].split("-")
                            if (range.size == 2) {
                                val start = range[0].toLongOrNull(16) ?: return@forEachLine
                                val end = range[1].toLongOrNull(16) ?: return@forEachLine
                                results.add(PropMapArea(start, end, end - start, path))
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
        }.getOrNull() ?: return false

        return try {
            Os.mmap(
                area.start,
                area.length,
                OsConstants.PROT_READ or OsConstants.PROT_WRITE,
                OsConstants.MAP_PRIVATE or OsConstants.MAP_FIXED,
                fd,
                0,
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "mmap COW 重映射失败 [${area.path}]: $e")
            false
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    /**
     * 在已转换为 MAP_PRIVATE 的属性内存区内搜索并改写目标属性
     */
    private fun patchPropertyInArea(
        area: PropMapArea,
        prop: PreparedProp,
    ): Boolean {
        val targetBytes = prop.nameWithNull
        val targetLen = targetBytes.size
        val firstByte = targetBytes[0]

        // 仅在真实已使用的 bytes_used 范围内搜索，提高效率与安全性
        val rawBytesUsed = runCatching { unsafe.getInt(area.start).toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        val scanLength = if (rawBytesUsed in 1024..area.length) rawBytesUsed else area.length

        val start = area.start
        val limit = start + scanLength - targetLen

        var curr = start
        var patched = false

        while (curr <= limit) {
            if (unsafe.getByte(curr) == firstByte) {
                var match = true
                for (i in 1 until targetLen) {
                    if (unsafe.getByte(curr + i) != targetBytes[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    // 在 Android Bionic 中，prop_info 结构体为：
                    // uint32_t serial (4 字节) + char value[92] (92 字节) + char name[0]
                    // 因此 prop_info 的起始地址恰好在 name 起始地址向前 96 字节处
                    val propInfoAddr = curr - 96
                    if (propInfoAddr >= start) {
                        writePropValue(propInfoAddr, prop.valueBytes)
                        patched = true
                    }
                }
            }
            curr++
        }
        return patched
    }

    private fun writePropValue(propInfoAddr: Long, newBytes: ByteArray) {
        val oldSerial = unsafe.getInt(propInfoAddr)
        val newLen = minOf(newBytes.size, 91)

        // 1. 设置最低位为 1，标明当前节点正在写入中
        unsafe.putInt(propInfoAddr, oldSerial or 1)

        // 2. 覆写 92 字节 value 区域，剩余字节填充 null
        for (i in 0 until 92) {
            val b = if (i < newLen) newBytes[i] else 0.toByte()
            unsafe.putByte(propInfoAddr + 4 + i, b)
        }

        // 3. 更新序列号：高 7 位为字符串实际长度，低 24 位为偶数递增计数器
        val counter = (oldSerial and 0x00FFFFFF) + 2
        val newSerial = (newLen shl 24) or (counter and 0x00FFFFFF)
        unsafe.putInt(propInfoAddr, newSerial)
    }
}
