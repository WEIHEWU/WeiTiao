package com.weitiao.tuning

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.SparseArray
import com.weitiao.tuning.core.TuningConfig
import dalvik.system.DexFile
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 健壮的微信步数与防撤回 Hook 实现。
 *
 * 核心设计：
 * 1. 步数算法：基于「单调递增绝对锚点偏移模型」。对外输出永远单调递增，跨天平滑衔接，
 *    彻底杜绝负向跳变（diffStep < 0）触发微信的 rebootIncrease 恶性重启累加。
 *    微信计算出的当日步数严格封顶于 stepMaximum。
 * 2. 动态传感器 Handle 追踪：通过 Hook registerListenerImpl 动态记录 TYPE_STEP_COUNTER 的 handle，
 *    彻底解决 Android 13/14/15/16 及 One UI 各版本反射解析 Handle 失败的问题。
 * 3. 移除任何针对特定微信版本
 */
internal class WechatHooks(
    private val install: (Executable, (XposedInterface.Chain) -> Any?) -> Unit,
    private val config: () -> TuningConfig,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val activated = AtomicBoolean()
    private val stepCounterHandles = CopyOnWriteArraySet<Int>()
    private val messageCache = ConcurrentHashMap<Long, Any>()

    private val stepEngine = WechatStepEngine(logger)
    @Volatile private var messageInsertMethod: Method? = null
    @Volatile private var messageStorage: Any? = null

    fun register(classLoader: ClassLoader, processName: String) {
        // 1. 传感器 Hook 立即安装（吸收 sporteditor 机制，绝不等待 Application）
        installSensorManagerHook(classLoader)
        installSensorEventHook(classLoader)

        // 2. 挂载 Application 启动以初始化防撤回等需要 Context 的功能
        hookApplicationInit(classLoader, processName)
    }

    private fun hookApplicationInit(classLoader: ClassLoader, processName: String) {
        runCatching {
            val appClass = Class.forName("android.app.Application", false, classLoader)
            val targets = appClass.declaredMethods.filter {
                it.name in listOf("onCreate", "attach") && it.parameterTypes.size <= 1
            }
            targets.forEach { method ->
                method.isAccessible = true
                install(method) { chain ->
                    val result = chain.proceed()
                    val context = (chain.thisObject as? Context)
                        ?: (chain.args.firstOrNull { it is Context } as? Context)
                    if (context != null && activated.compareAndSet(false, true)) {
                        activate(context, context.classLoader ?: classLoader, processName)
                    }
                    result
                }
            }
        }.onFailure { logger("微信 Application 初始化 Hook 安装失败", it) }
    }

    private fun activate(context: Context, classLoader: ClassLoader, processName: String) {
        installAntiRecallCore(classLoader)
        Thread({
            installMessageStorageHook(context, classLoader)
            installXmlParserHook(context, classLoader)
        }, "Tuning-WeChatScan").apply { isDaemon = true }.start()
        logger("微信防撤回功能已在 $processName 初始化", null)
    }

    /**
     * Hook SensorManager.registerListenerImpl，动态捕获计步器 handle。
     */
    private fun installSensorManagerHook(classLoader: ClassLoader) {
        runCatching {
            val smClass = Class.forName("android.hardware.SystemSensorManager", false, classLoader)
            val targets = smClass.declaredMethods.filter {
                it.name == "registerListenerImpl" && it.parameterTypes.isNotEmpty() &&
                    SensorEventListener::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            targets.forEach { method ->
                method.isAccessible = true
                install(method) { chain ->
                    val sensor = chain.args.firstOrNull { it is Sensor } as? Sensor
                    if (sensor != null && (sensor.type == Sensor.TYPE_STEP_COUNTER || sensor.type == Sensor.TYPE_STEP_DETECTOR)) {
                        val handle = readIntField(sensor, "mHandle") ?: sensor.hashCode()
                        stepCounterHandles.add(handle)
                    }
                    chain.proceed()
                }
            }
        }.onFailure {
            logger("SensorManager 监听 Hook 失败（将使用反射回退模式）", it)
        }
    }

    /**
     * Hook SystemSensorManager$SensorEventQueue.dispatchSensorEvent。
     * 核心设计参考 sporteditor，直接拦截传感器底层事件分发，使用 mSensorsEvents 精准匹配。
     */
    private fun installSensorEventHook(classLoader: ClassLoader) {
        runCatching {
            val queueClass = Class.forName(
                "android.hardware.SystemSensorManager\$SensorEventQueue", false, classLoader,
            )
            val targets = queueClass.declaredMethods.filter {
                it.name == "dispatchSensorEvent" &&
                    it.returnType == Void.TYPE &&
                    it.parameterTypes.size >= 4 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == FloatArray::class.java
            }
            check(targets.isNotEmpty()) { "dispatchSensorEvent 不存在" }

            val sensorsEventsField: Field? = runCatching {
                queueClass.getDeclaredField("mSensorsEvents").apply { isAccessible = true }
            }.getOrNull()

            targets.forEach { method ->
                method.isAccessible = true
                install(method) { chain ->
                    val cfg = config()
                    if (!cfg.stepEnabled) {
                        return@install chain.proceed()
                    }

                    val handle = chain.getArg(0) as? Int ?: return@install chain.proceed()
                    val values = chain.getArg(1) as? FloatArray ?: return@install chain.proceed()
                    if (values.isEmpty()) return@install chain.proceed()

                    // 反射获取 Sensor（sporteditor 方案：mSensorsEvents.get(handle).sensor）
                    val sensor: Sensor? = runCatching {
                        val events = sensorsEventsField?.get(chain.thisObject) as? SparseArray<*>
                        val event = events?.get(handle)
                        (event as? SensorEvent)?.sensor ?: (readField(event, "sensor") as? Sensor)
                    }.getOrNull()

                    val sensorType = sensor?.type ?: 0
                    val isStepCounter = sensorType == Sensor.TYPE_STEP_COUNTER // 19
                    val isStepDetector = sensorType == Sensor.TYPE_STEP_DETECTOR // 18

                    // 过滤非计步传感器（如加速度计 1、光线感应器 5 等）
                    if (sensor != null && !isStepCounter && !isStepDetector) {
                        return@install chain.proceed()
                    }

                    val isLikelyStep = isStepCounter || isStepDetector ||
                        handle in stepCounterHandles || (sensor == null && values[0] > 0f)

                    if (!isLikelyStep) {
                        return@install chain.proceed()
                    }

                    // 核心计步引擎处理
                    stepEngine.process(values, isStepDetector, cfg)

                    chain.proceed()
                }
            }
            logger("微信计步器 Hook 已就绪（${targets.size} 个入口）", null)
        }.onFailure { logger("微信计步器 Hook 安装失败", it) }
    }

    private fun readIntField(target: Any, name: String): Int? =
        runCatching { findField(target.javaClass, name)?.get(target) as? Int }.getOrNull()

    /**
     * 极速内存计步引擎：
     * 1. 纯内存原子计算，零磁盘阻塞 I/O，无锁无卡顿。
     * 2. 支持自动步数（逐步增加）与真实步数倍率放大。
     * 3. 严格封顶：values[0] 注入值永远保证今日增量 <= stepMaximum，微信计算出的今日步数绝对不超限。
     * 4. 设备重启、跨天平滑衔接，严格保证 output 永远单调递增，彻底杜绝微信负跳变重启累加。
     */
    internal class WechatStepEngine(
        private val logger: (String, Throwable?) -> Unit,
    ) {
        @Volatile private var todayKey = 0
        @Volatile private var baseHardwareRaw = 0f
        @Volatile private var lastHardwareRaw = 0f
        @Volatile private var lastOutput = 0f
        @Volatile private var currentDelivered = 0L
        @Volatile private var lastClampLogTime = 0L

        @Synchronized
        fun processStepCounter(raw: Float, cfg: TuningConfig): Float {
            val arr = floatArrayOf(raw)
            process(arr, isDetector = false, cfg = cfg)
            return arr[0]
        }

        @Synchronized
        fun processPassthrough(raw: Float): Float = raw

        @Synchronized
        fun process(values: FloatArray, isDetector: Boolean, cfg: TuningConfig) {
            if (!cfg.stepEnabled) return
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val endHour = if (cfg.stepEndHour <= 0) 24 else cfg.stepEndHour.coerceIn(1, 24)

            val today = calendar.get(Calendar.YEAR) * 10000 +
                (calendar.get(Calendar.MONTH) + 1) * 100 +
                calendar.get(Calendar.DAY_OF_MONTH)

            val raw = values[0]
            val maxCap = cfg.stepMaximum.coerceAtLeast(1).toLong()
            val multiplier = cfg.stepMultiplier.coerceIn(1, 1000).toLong()

            // 跨天重置
            if (todayKey != today) {
                todayKey = today
                baseHardwareRaw = maxOf(lastOutput, raw)
                lastHardwareRaw = raw
                currentDelivered = 0L
            }

            // 设备重启检测（raw < lastHardwareRaw）：对齐硬件基准，维持当前输出不倒退
            if (raw + 100f < lastHardwareRaw) {
                baseHardwareRaw = lastOutput - currentDelivered
                lastHardwareRaw = raw
            }

            // 截止时间判定：到达或超过截止小时，冻结增加步数
            if (currentHour >= endHour) {
                if (isDetector) {
                    values[0] = 0f
                } else {
                    val deltaHw = (raw - lastHardwareRaw).coerceAtLeast(0f)
                    val total = (deltaHw + currentDelivered).coerceAtMost(maxCap.toFloat())
                    var output = baseHardwareRaw + total
                    if (output < lastOutput) output = lastOutput
                    lastOutput = output
                    values[0] = output
                }
                return
            }

            if (isDetector) {
                // Type 18: STEP_DETECTOR
                if (currentDelivered < maxCap) {
                    val gain = multiplier.coerceAtMost(maxCap - currentDelivered)
                    currentDelivered += gain
                    values[0] = gain.toFloat()
                } else {
                    values[0] = 0f
                    logCapReached(maxCap)
                }
            } else {
                // Type 19: STEP_COUNTER
                if (cfg.stepAuto) {
                    // 自动步数模式：每个传感器事件递增增量
                    if (currentDelivered < maxCap) {
                        val gain = multiplier.coerceAtMost(maxCap - currentDelivered)
                        currentDelivered += gain
                    } else {
                        logCapReached(maxCap)
                    }
                } else {
                    // 真实步数倍率模式：当硬件产生有效步数时放大
                    val deltaRaw = (raw - lastHardwareRaw).coerceAtLeast(0f)
                    if (deltaRaw > 0f && deltaRaw < 500f) {
                        val realBoost = (deltaRaw * (multiplier - 1)).toLong().coerceAtLeast(0L)
                        if (currentDelivered < maxCap) {
                            val gain = realBoost.coerceAtMost(maxCap - currentDelivered)
                            currentDelivered += gain
                        } else {
                            logCapReached(maxCap)
                        }
                    }
                }
                lastHardwareRaw = raw

                // 核心输出：保证单调递增，且当日增量严格 <= maxCap
                val deltaHardware = (raw - lastHardwareRaw).coerceAtLeast(0f)
                val totalTodaySteps = (deltaHardware + currentDelivered).coerceAtMost(maxCap.toFloat())
                var output = baseHardwareRaw + totalTodaySteps
                if (output < lastOutput) {
                    output = lastOutput
                }
                lastOutput = output
                values[0] = output
            }
        }

        private fun logCapReached(cap: Long) {
            val now = System.currentTimeMillis()
            if (now - lastClampLogTime > 60_000L) {
                lastClampLogTime = now
                logger("微信步数已达到设定上限 $cap，已停止增加", null)
            }
        }
    }

    private fun installAntiRecallCore(classLoader: ClassLoader) {
        runCatching {
            val database = Class.forName("com.tencent.wcdb.database.SQLiteDatabase", false, classLoader)
            val updateWithConflict = findMethod(
                database, "updateWithOnConflict", String::class.java, ContentValues::class.java,
                String::class.java, Array<String>::class.java, Int::class.javaPrimitiveType!!,
            )
            val update = findMethod(
                database, "update", String::class.java, ContentValues::class.java,
                String::class.java, Array<String>::class.java,
            )
            val delete = findMethod(
                database, "delete", String::class.java, String::class.java, Array<String>::class.java,
            )
            val fileDelete = File::class.java.getDeclaredMethod("delete")

            updateWithConflict?.let { method ->
                install(method) { chain ->
                    val cfg = config()
                    val table = chain.getArg(0) as? String
                    val values = chain.getArg(1) as? ContentValues
                    if (cfg.antiRecall && table == "message" && values != null &&
                        isRemoteRecallReplacement(values.getAsString("content"))
                    ) {
                        runCatching { insertRecallNotice(values) }
                            .onFailure { logger("防撤回提示消息插入失败", it) }
                        1
                    } else chain.proceed()
                }
            }

            update?.let { method ->
                install(method) { chain ->
                    val cfg = config()
                    val table = chain.getArg(0) as? String
                    val values = chain.getArg(1) as? ContentValues
                    if (cfg.antiRecall && table == "message" && values != null &&
                        isRemoteRecallReplacement(values.getAsString("content"))
                    ) {
                        runCatching { insertRecallNotice(values) }
                            .onFailure { logger("防撤回提示消息插入失败", it) }
                        1
                    } else chain.proceed()
                }
            }

            delete?.let { method ->
                install(method) { chain ->
                    val table = chain.getArg(0) as? String
                    if (config().antiRecall && table in RECALL_MEDIA_TABLES) 1 else chain.proceed()
                }
            }

            install(fileDelete) { chain ->
                val file = chain.thisObject as? File
                if (config().antiRecall && file != null && shouldPreserveMedia(file.absolutePath)) true
                else chain.proceed()
            }
            logger("微信防撤回数据库与媒体保护 Hook 已安装", null)
        }.onFailure { logger("微信防撤回核心安装失败", it) }
    }

    private fun isRemoteRecallReplacement(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        if (content.startsWith('"') && !content.contains("<msg>")) {
            val isSelfRecall = content.contains("你撤回了一条消息") ||
                content.contains("You've recalled a message") ||
                content.contains("You recalled a message")
            return !isSelfRecall
        }
        return false
    }

    private fun shouldPreserveMedia(path: String): Boolean =
        (path.contains("/image2/") || path.contains("/image/")) ||
            (path.contains("/voice2/") || path.contains("/voice/") || path.endsWith(".amr")) ||
            (path.contains("/video/") || path.endsWith(".mp4"))

    private fun installMessageStorageHook(context: Context, classLoader: ClassLoader) {
        runCatching {
            val classes = collectClassNames(context, classLoader, "com.tencent.mm.storage.")
                .mapNotNull { runCatching { Class.forName(it, false, classLoader) }.getOrNull() }
            val messageClasses = classes.filter(::isMessageClass)
            if (messageClasses.isEmpty()) {
                logger("未找到微信消息实体类，跳过提示消息克隆", null)
                return
            }
            val messageClass = messageClasses.first()
            val candidates = classes.flatMap { it.declaredMethods.asList() }.filter { method ->
                val p = method.parameterTypes
                method.returnType == Long::class.javaPrimitiveType && p.isNotEmpty() && p.size <= 2 &&
                    p[0] == messageClass && (p.size == 1 || p[1] == Boolean::class.javaPrimitiveType)
            }.distinct()
            if (candidates.isEmpty()) {
                logger("未找到微信消息写入方法", null)
                return
            }
            val target = candidates.first().apply { isAccessible = true }
            install(target) { chain ->
                val message = chain.getArg(0)
                val result = chain.proceed()
                messageStorage = chain.thisObject
                val messageId = readField(message, FIELD_MSG_ID) as? Number
                if (messageId != null && messageId.toLong() > 0) {
                    messageCache[messageId.toLong()] = message
                    pruneMessageCache()
                }
                result
            }
            messageInsertMethod = target
            logger("微信防撤回消息缓存 Hook 已就绪", null)
        }.onFailure { logger("微信消息存储扫描失败", it) }
    }

    private fun isMessageClass(candidate: Class<*>): Boolean =
        hasMethod(candidate, "getCreateTime", Long::class.javaPrimitiveType) &&
            hasMethod(candidate, "getType", Int::class.javaPrimitiveType) &&
            hasField(candidate, FIELD_MSG_ID, Long::class.javaPrimitiveType!!) &&
            hasField(candidate, FIELD_CREATE_TIME, Long::class.javaPrimitiveType!!) &&
            hasField(candidate, FIELD_TYPE, Int::class.javaPrimitiveType!!) &&
            hasField(candidate, FIELD_CONTENT, String::class.java)

    private fun insertRecallNotice(values: ContentValues) {
        val insert = messageInsertMethod ?: return
        val storage = messageStorage ?: return
        val messageId = when (val raw = values.get("msgId")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        } ?: return
        val original = messageCache[messageId] ?: return
        val clone = original.javaClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        copyInstanceFields(original, clone)
        val createTime = (readField(original, FIELD_CREATE_TIME) as? Number)?.toLong() ?: return
        writeField(clone, FIELD_TYPE, 10000)
        writeField(clone, FIELD_CONTENT, values.get("content"))
        writeField(clone, FIELD_CREATE_TIME, createTime + 1L)
        if (insert.parameterCount == 1) insert.invoke(storage, clone)
        else insert.invoke(storage, clone, false)
    }

    private fun pruneMessageCache() {
        if (messageCache.size < 256) return
        val cutoff = System.currentTimeMillis() - 120_000L
        messageCache.entries.removeIf { entry ->
            messageCache.size > 512 ||
                ((readField(entry.value, FIELD_CREATE_TIME) as? Number)?.toLong() ?: 0L) < cutoff
        }
    }

    private fun installXmlParserHook(context: Context, classLoader: ClassLoader) {
        runCatching {
            val known = runCatching {
                Class.forName("com.tencent.mm.sdk.platformtools.XmlParser", false, classLoader)
            }.getOrNull()?.let { findParserMethod(it, false) }
            val parser = known ?: collectClassNames(context, classLoader, "com.tencent.mm.sdk.platformtools.")
                .mapNotNull { runCatching { Class.forName(it, false, classLoader) }.getOrNull() }
                .mapNotNull { findParserMethod(it, true) }
                .firstOrNull()
            if (parser == null) {
                logger("微信 XML 解析器未匹配，数据库防撤回仍正常工作", null)
                return
            }
            install(parser) { chain ->
                val result = chain.proceed()
                if (config().antiRecall && chain.args.size >= 2 && chain.getArg(1) == "sysmsg") {
                    @Suppress("UNCHECKED_CAST")
                    val values = result as? MutableMap<Any?, Any?>
                    if (values?.get(".sysmsg.\$type") == "revokemsg") {
                        val replacement = values[".sysmsg.revokemsg.replacemsg"] as? String
                        if (replacement?.startsWith('"') == true) {
                            val quote = replacement.indexOf('"', 1)
                            if (quote >= 0) values[".sysmsg.revokemsg.replacemsg"] =
                                replacement.substring(0, quote + 1) + " 撤回了一条消息 (已拦截)"
                        }
                    }
                }
                result
            }
            logger("微信防撤回 XML 提示 Hook 已就绪", null)
        }.onFailure { logger("微信 XML 解析器扫描失败", it) }
    }

    private fun findParserMethod(candidate: Class<*>, requireThreadLocal: Boolean): Method? {
        if (requireThreadLocal && candidate.declaredFields.none { ThreadLocal::class.java.isAssignableFrom(it.type) }) {
            return null
        }
        return candidate.declaredMethods.firstOrNull { method ->
            val p = method.parameterTypes
            Modifier.isStatic(method.modifiers) && Map::class.java.isAssignableFrom(method.returnType) &&
                p.contentEquals(arrayOf(String::class.java, String::class.java, String::class.java))
        }?.apply { isAccessible = true }
    }

    private fun collectClassNames(context: Context, classLoader: ClassLoader, prefix: String): Set<String> {
        val names = linkedSetOf<String>()
        var loader: ClassLoader? = classLoader
        while (loader != null) {
            runCatching {
                val pathList = readField(loader, "pathList")
                val elements = readField(pathList, "dexElements") as? Array<*>
                elements?.forEach { element ->
                    (readField(element, "dexFile") as? DexFile)?.let { addDexNames(it, prefix, names) }
                }
            }
            loader = loader.parent
        }
        buildList {
            add(context.applicationInfo.sourceDir)
            context.applicationInfo.splitSourceDirs?.let(::addAll)
        }.filterNotNull().forEach { path ->
            runCatching {
                val dex = DexFile(path)
                try {
                    addDexNames(dex, prefix, names)
                } finally {
                    dex.close()
                }
            }.onFailure { logger("跳过 Dex 扫描：$path", it) }
        }
        return names
    }

    private fun addDexNames(dex: DexFile, prefix: String, names: MutableSet<String>) {
        val entries = dex.entries()
        while (entries.hasMoreElements()) entries.nextElement().takeIf { it.startsWith(prefix) }?.let(names::add)
    }

    private fun findMethod(owner: Class<*>, name: String, vararg types: Class<*>): Method? {
        var current: Class<*>? = owner
        while (current != null) {
            runCatching { current.getDeclaredMethod(name, *types) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun hasMethod(owner: Class<*>, name: String, returnType: Class<*>?, vararg types: Class<*>): Boolean =
        findMethod(owner, name, *types)?.let { returnType == null || it.returnType == returnType } == true

    private fun hasField(owner: Class<*>, name: String, type: Class<*>): Boolean =
        findField(owner, name)?.type == type

    private fun findField(owner: Class<*>?, name: String): Field? {
        var current = owner
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun readField(target: Any?, name: String): Any? =
        target?.let { runCatching { findField(it.javaClass, name)?.get(it) }.getOrNull() }

    private fun writeField(target: Any, name: String, value: Any?) {
        findField(target.javaClass, name)?.set(target, value)
    }

    private fun copyInstanceFields(source: Any, destination: Any) {
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    field.set(destination, field.get(source))
                }
            }
            current = current.superclass
        }
    }

    private companion object {
        val RECALL_MEDIA_TABLES = setOf("ImgInfo2", "videoinfo2", "voiceinfo", "voiceinfo2")
        const val FIELD_MSG_ID = "field_msgId"
        const val FIELD_CREATE_TIME = "field_createTime"
        const val FIELD_TYPE = "field_type"
        const val FIELD_CONTENT = "field_content"
    }
}

