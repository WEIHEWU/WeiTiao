package com.weitiao.tuning

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import com.weitiao.tuning.core.TuningConfig
import dalvik.system.DexFile
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 健壮的微信步数与防撤回 Hook 实现（兼容国内版与 Google Play 版微信 8.0.79+ / targetSdk 34+）。
 *
 * 核心设计：
 * 1. 双层传感器拦截（底层 SensorEventQueue + 公开 SDK SensorEventListener.onSensorChanged）：
 *    - 第一层：通过父类链反射 `BaseSensorEventQueue.mSensorsEvents` 与真实 `mHandle` 拦截 `dispatchSensorEvent`；
 *    - 第二层：在 `registerListener` / `registerListenerImpl` 时基于 100% 公开 SDK API (`Sensor.getType()`)
 *      动态 Hook 目标监听器的 `onSensorChanged(SensorEvent)`，彻底免疫 Google Play 版微信 8.0.79 (targetSdk >= 34)
 *      触发的 Android 14–17 Hidden API 反射限制。
 * 2. 彻底移除 `(sensor == null && values[0] > 0f)` 误判回退，严禁加速度计/陀螺仪/光线传感器污染计步引擎。
 * 3. 平滑主动驱动器 + 单次增量防异常跳变：
 *    - 首帧事件仅建立基准锚点（0 突跳），随后通过监听器的 Handler/Looper 平滑递增，
 *      即使手机静置在桌面也能在「自动递增模式」下稳定把微信运动步数推升至上限，且绝不触发微信单次跳变防作弊丢弃。
 */
internal class WechatHooks(
    private val install: (Executable, (XposedInterface.Chain) -> Any?) -> Unit,
    private val config: () -> TuningConfig,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val activated = AtomicBoolean()
    private val autoTickerStarted = AtomicBoolean()
    private val stepCounterHandles = CopyOnWriteArraySet<Int>()
    private val stepDetectorHandles = CopyOnWriteArraySet<Int>()
    private val hookedListenerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val activeStepListeners = ConcurrentHashMap<Int, RegisteredStepListener>()
    private val processedInDispatchQueue = ThreadLocal<Boolean>()
    private val syntheticDispatchInProgress = ThreadLocal<Boolean>()
    private val messageCache = ConcurrentHashMap<Long, Any>()

    @Volatile private var lastRawHardwareStep = 0f

    private val stepEngine = WechatStepEngine(logger)
    @Volatile private var messageInsertMethod: Method? = null
    @Volatile private var messageStorage: Any? = null

    private class RegisteredStepListener(
        val listenerRef: WeakReference<SensorEventListener>,
        val sensor: Sensor,
        val handler: Handler?,
        @Volatile var templateEvent: SensorEvent? = null,
    )

    fun register(classLoader: ClassLoader, processName: String) {
        // 1. 传感器 Hook 立即安装（双层拦截，绝不等待 Application）
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
     * Hook SensorManager.registerListener 与 SystemSensorManager.registerListenerImpl：
     * 1. 使用 100% 公开 SDK `sensor.type` 识别 TYPE_STEP_COUNTER (19) 与 TYPE_STEP_DETECTOR (18)。
     * 2. 动态 Hook 传入的 SensorEventListener 实现类的 onSensorChanged(SensorEvent)，绕开 Google 版微信 Hidden API 限制。
     * 3. 捕获真实传感器 handle（严禁使用 hashCode 伪造 handle）。
     */
    private fun installSensorManagerHook(classLoader: ClassLoader) {
        runCatching {
            val smClass = Class.forName("android.hardware.SensorManager", false, classLoader)
            smClass.declaredMethods.filter {
                it.name == "registerListener" && it.parameterTypes.size >= 2 &&
                    SensorEventListener::class.java.isAssignableFrom(it.parameterTypes[0])
            }.forEach { method ->
                method.isAccessible = true
                install(method) { chain ->
                    onSensorListenerRegistered(chain.args)
                    chain.proceed()
                }
            }

            smClass.declaredMethods.filter {
                it.name == "unregisterListener" && it.parameterTypes.isNotEmpty() &&
                    SensorEventListener::class.java.isAssignableFrom(it.parameterTypes[0])
            }.forEach { method ->
                method.isAccessible = true
                install(method) { chain ->
                    onSensorListenerUnregistered(chain.args)
                    chain.proceed()
                }
            }
        }.onFailure {
            logger("SensorManager.registerListener Hook 失败", it)
        }

        runCatching {
            val sysSmClass = Class.forName("android.hardware.SystemSensorManager", false, classLoader)
            sysSmClass.declaredMethods.filter {
                it.name == "registerListenerImpl" && it.parameterTypes.isNotEmpty() &&
                    SensorEventListener::class.java.isAssignableFrom(it.parameterTypes[0])
            }.forEach { method ->
                method.isAccessible = true
                install(method) { chain ->
                    onSensorListenerRegistered(chain.args)
                    chain.proceed()
                }
            }

            sysSmClass.declaredMethods.filter {
                it.name == "unregisterListenerImpl" && it.parameterTypes.isNotEmpty() &&
                    SensorEventListener::class.java.isAssignableFrom(it.parameterTypes[0])
            }.forEach { method ->
                method.isAccessible = true
                install(method) { chain ->
                    onSensorListenerUnregistered(chain.args)
                    chain.proceed()
                }
            }
        }.onFailure {
            logger("SystemSensorManager.registerListenerImpl Hook 失败", it)
        }
    }

    private fun onSensorListenerRegistered(args: List<Any?>) {
        val listener = args.firstOrNull { it is SensorEventListener } as? SensorEventListener ?: return
        val sensor = args.firstOrNull { it is Sensor } as? Sensor ?: return
        val handler = args.firstOrNull { it is Handler } as? Handler
        val type = sensor.type
        if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) return

        extractSensorHandle(sensor)?.let { handle ->
            if (type == Sensor.TYPE_STEP_COUNTER) {
                stepCounterHandles.add(handle)
            } else {
                stepDetectorHandles.add(handle)
            }
        }

        hookStepListenerClass(listener.javaClass)

        if (type == Sensor.TYPE_STEP_COUNTER) {
            val key = System.identityHashCode(listener)
            activeStepListeners[key] = RegisteredStepListener(
                listenerRef = WeakReference(listener),
                sensor = sensor,
                handler = handler,
            )
            ensureAutoTickerRunning()
        }
    }

    private fun onSensorListenerUnregistered(args: List<Any?>) {
        val listener = args.firstOrNull { it is SensorEventListener } as? SensorEventListener ?: return
        val sensor = args.firstOrNull { it is Sensor } as? Sensor
        if (sensor == null || sensor.type == Sensor.TYPE_STEP_COUNTER) {
            activeStepListeners.remove(System.identityHashCode(listener))
        }
    }

    /**
     * 基于 100% 公开 Android SDK API (`SensorEvent.sensor.type`, `SensorEvent.values`) 拦截计步回调。
     * 即使 Google Play 版微信 8.0.79 (targetSdk >= 34) 封锁了 `mSensorsEvents` / `mHandle` 隐藏字段反射，
     * 此层依然 100% 精准生效。
     */
    private fun hookStepListenerClass(listenerClass: Class<*>) {
        if (!hookedListenerClasses.add(listenerClass)) return
        runCatching {
            val method = findMethod(listenerClass, "onSensorChanged", SensorEvent::class.java) ?: return
            install(method) { chain ->
                val event = chain.args.firstOrNull() as? SensorEvent ?: return@install chain.proceed()
                val values = event.values
                if (values == null || values.isEmpty()) return@install chain.proceed()

                val sensorType = event.sensor?.type ?: 0
                val isStepCounter = sensorType == Sensor.TYPE_STEP_COUNTER
                val isStepDetector = sensorType == Sensor.TYPE_STEP_DETECTOR
                if (!isStepCounter && !isStepDetector) {
                    return@install chain.proceed()
                }

                if (isStepCounter) {
                    val listener = chain.thisObject as? SensorEventListener
                    if (listener != null) {
                        val entry = activeStepListeners[System.identityHashCode(listener)]
                        if (entry != null && entry.templateEvent == null) {
                            entry.templateEvent = event
                        }
                    }
                    if (syntheticDispatchInProgress.get() != true && processedInDispatchQueue.get() != true) {
                        if (values[0] > 0f) {
                            lastRawHardwareStep = values[0]
                        }
                    }
                }

                val cfg = config()
                if (cfg.stepEnabled && processedInDispatchQueue.get() != true) {
                    stepEngine.process(values, isStepDetector, cfg, isLiveWechatHook = true)
                }
                chain.proceed()
            }
            logger("已挂载微信计步监听器 ${listenerClass.name}.onSensorChanged", null)
        }.onFailure {
            hookedListenerClasses.remove(listenerClass)
            logger("挂载微信计步监听器失败: ${listenerClass.name}", it)
        }
    }

    /**
     * 后台平滑计步驱动器：
     * 当开启「自动递增模式」或存在待释放的倍率步数时，定期在监听器所属 Handler/Looper 上触发平滑增量，
     * 解决手机静置时硬件 TYPE_STEP_COUNTER (ON_CHANGE) 不触发第二次事件导致微信步数卡死为 0 的问题。
     */
    private fun ensureAutoTickerRunning() {
        if (!autoTickerStarted.compareAndSet(false, true)) return
        Thread({
            while (true) {
                try {
                    Thread.sleep(AUTO_TICK_INTERVAL_MS)
                    val cfg = config()
                    if (!cfg.stepEnabled || !stepEngine.shouldDispatchAutoTick(cfg)) {
                        continue
                    }
                    val iter = activeStepListeners.entries.iterator()
                    while (iter.hasNext()) {
                        val (_, entry) = iter.next()
                        val listener = entry.listenerRef.get()
                        if (listener == null) {
                            iter.remove()
                            continue
                        }
                        val event = entry.templateEvent
                            ?: createFallbackSensorEvent(entry.sensor)?.also { entry.templateEvent = it }
                            ?: continue
                        val targetHandler = entry.handler ?: Handler(Looper.getMainLooper())
                        targetHandler.post {
                            runCatching {
                                val currentCfg = config()
                                if (!currentCfg.stepEnabled || !stepEngine.shouldDispatchAutoTick(currentCfg)) {
                                    return@runCatching
                                }
                                val rawHw = lastRawHardwareStep.takeIf { it > 0f }
                                    ?: stepEngine.currentHardwareBaseline()
                                if (event.values != null && event.values.isNotEmpty()) {
                                    event.values[0] = rawHw
                                }
                                event.timestamp = SystemClock.elapsedRealtimeNanos()
                                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                                syntheticDispatchInProgress.set(true)
                                try {
                                    listener.onSensorChanged(event)
                                } finally {
                                    syntheticDispatchInProgress.set(false)
                                }
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                }
            }
        }, "WeiTiao-StepTicker").apply { isDaemon = true }.start()
    }

    private fun createFallbackSensorEvent(sensor: Sensor): SensorEvent? = runCatching {
        val ctor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        val event = ctor.newInstance(16)
        findField(SensorEvent::class.java, "sensor")?.set(event, sensor)
        event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        event.timestamp = SystemClock.elapsedRealtimeNanos()
        if (event.values != null && event.values.isNotEmpty()) {
            event.values[0] = lastRawHardwareStep.takeIf { it > 0f } ?: 1000f
        }
        event
    }.getOrNull()

    /**
     * Hook SystemSensorManager$SensorEventQueue.dispatchSensorEvent。
     * 注意：`mSensorsEvents` 定义在父类 `SystemSensorManager$BaseSensorEventQueue` 中，
     * 必须使用向父类遍历的 `findField(queueClass, "mSensorsEvents")` 获取！
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

            val sensorsEventsField: Field? = findField(queueClass, "mSensorsEvents")
            val listenerField: Field? = findField(queueClass, "mListener")

            targets.forEach { method ->
                method.isAccessible = true
                install(method) { chain ->
                    val handle = chain.getArg(0) as? Int ?: return@install chain.proceed()
                    val values = chain.getArg(1) as? FloatArray ?: return@install chain.proceed()
                    if (values.isEmpty()) return@install chain.proceed()

                    // 通过 BaseSensorEventQueue.mSensorsEvents 获取当前 handle 对应的 SensorEvent 与 Sensor
                    val eventObj: Any? = runCatching {
                        val events = sensorsEventsField?.get(chain.thisObject) as? SparseArray<*>
                        events?.get(handle)
                    }.getOrNull()

                    val sensor: Sensor? = runCatching {
                        (eventObj as? SensorEvent)?.sensor ?: (readField(eventObj, "sensor") as? Sensor)
                    }.getOrNull()

                    val sensorType = sensor?.type ?: 0
                    val isStepCounter = sensorType == Sensor.TYPE_STEP_COUNTER ||
                        (sensor == null && handle in stepCounterHandles)
                    val isStepDetector = sensorType == Sensor.TYPE_STEP_DETECTOR ||
                        (sensor == null && handle in stepDetectorHandles)

                    // 严格过滤非计步传感器（绝不把 sensor == null 的加速度计/陀螺仪当成计步器！）
                    if (!isStepCounter && !isStepDetector) {
                        return@install chain.proceed()
                    }

                    if (isStepCounter) {
                        if (values[0] > 0f) {
                            lastRawHardwareStep = values[0]
                        }
                        runCatching {
                            val queueListener = listenerField?.get(chain.thisObject) as? SensorEventListener
                            if (queueListener != null) {
                                hookStepListenerClass(queueListener.javaClass)
                                val entry = activeStepListeners[System.identityHashCode(queueListener)]
                                if (entry != null && entry.templateEvent == null && eventObj is SensorEvent) {
                                    entry.templateEvent = eventObj
                                }
                            }
                        }
                    }

                    val cfg = config()
                    if (!cfg.stepEnabled) {
                        return@install chain.proceed()
                    }

                    stepEngine.process(values, isStepDetector, cfg, isLiveWechatHook = true)

                    processedInDispatchQueue.set(true)
                    try {
                        chain.proceed()
                    } finally {
                        processedInDispatchQueue.set(false)
                    }
                }
            }
            logger("微信计步器 Hook 已就绪（${targets.size} 个入口）", null)
        }.onFailure { logger("微信计步器 Hook 安装失败", it) }
    }

    private fun extractSensorHandle(sensor: Sensor): Int? {
        readIntField(sensor, "mHandle")?.let { return it }
        runCatching {
            findMethod(sensor.javaClass, "getHandle")?.invoke(sensor) as? Int
        }.getOrNull()?.let { return it }
        return null
    }

    private fun readIntField(target: Any, name: String): Int? =
        runCatching { findField(target.javaClass, name)?.get(target) as? Int }.getOrNull()

    /**
     * 极速内存计步引擎：
     * 1. 纯内存原子计算，零磁盘阻塞 I/O，无锁无卡顿。
     * 2. 首帧锚点保护：进程/当日首个计步事件仅对齐基准（0 突跳），防止被微信首帧吞没或判定为异常跳变。
     * 3. 支持自动步数（逐步平滑增加）与真实步数倍率放大（含缓冲平滑释放）。
     * 4. 严格封顶：values[0] 注入值永远保证今日增量 <= stepMaximum，微信计算出的今日步数绝对不超限。
     * 5. 设备重启、跨天平滑衔接，严格保证 output 永远单调递增，彻底杜绝微信负跳变重启累加。
     */
    internal class WechatStepEngine(
        private val logger: (String, Throwable?) -> Unit,
    ) {
        @Volatile private var todayKey = 0
        @Volatile private var baseHardwareRaw = 0f
        @Volatile private var lastHardwareRaw = 0f
        @Volatile private var lastOutput = 0f
        @Volatile private var currentDelivered = 0L
        @Volatile private var pendingSteps = 0L
        @Volatile private var baselineEstablished = false
        @Volatile private var lastClampLogTime = 0L

        @Synchronized
        fun processStepCounter(raw: Float, cfg: TuningConfig): Float {
            val arr = floatArrayOf(raw)
            process(arr, isDetector = false, cfg = cfg, isLiveWechatHook = false)
            return arr[0]
        }

        @Synchronized
        fun processPassthrough(raw: Float): Float = raw

        @Synchronized
        fun currentHardwareBaseline(): Float =
            lastHardwareRaw.takeIf { it > 0f } ?: baseHardwareRaw.takeIf { it > 0f } ?: 1000f

        @Synchronized
        fun shouldDispatchAutoTick(cfg: TuningConfig): Boolean {
            if (!cfg.stepEnabled) return false
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val endHour = if (cfg.stepEndHour <= 0) 24 else cfg.stepEndHour.coerceIn(1, 24)
            if (currentHour >= endHour) return false
            val maxCap = cfg.stepMaximum.coerceAtLeast(1).toLong()
            if (currentDelivered >= maxCap) return false
            return cfg.stepAuto || pendingSteps > 0L || !baselineEstablished
        }

        @Synchronized
        fun process(
            values: FloatArray,
            isDetector: Boolean,
            cfg: TuningConfig,
            isLiveWechatHook: Boolean = false,
        ) {
            if (!cfg.stepEnabled) return
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val endHour = if (cfg.stepEndHour <= 0) 24 else cfg.stepEndHour.coerceIn(1, 24)

            val today = calendar.get(Calendar.YEAR) * 10000 +
                (calendar.get(Calendar.MONTH) + 1) * 100 +
                calendar.get(Calendar.DAY_OF_MONTH)

            val raw = values[0]
            val maxCap = cfg.stepMaximum.coerceAtLeast(1).toLong()
            val multiplier = cfg.stepMultiplier.coerceIn(1, 10_000).toLong()

            // 跨天重置
            if (todayKey != today) {
                todayKey = today
                baseHardwareRaw = maxOf(lastOutput, raw)
                lastHardwareRaw = raw
                currentDelivered = 0L
                pendingSteps = 0L
                baselineEstablished = false
            }

            // 设备重启检测（raw < lastHardwareRaw）：对齐硬件基准，维持当前输出不倒退
            if (raw + 100f < lastHardwareRaw) {
                baseHardwareRaw = maxOf(0f, lastOutput - currentDelivered)
                lastHardwareRaw = raw
            }

            // 截止时间判定：到达或超过截止小时，冻结增加步数
            if (currentHour >= endHour) {
                lastHardwareRaw = raw
                if (isDetector) {
                    values[0] = 0f
                } else {
                    val total = currentDelivered.toFloat().coerceAtMost(maxCap.toFloat())
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
                // 首帧事件仅建立微信计步基准锚点，不产生瞬间巨幅跳变，确保后续增量被微信 100% 计入今日步数
                if (!baselineEstablished) {
                    baselineEstablished = true
                    if (baseHardwareRaw <= 0f) {
                        baseHardwareRaw = maxOf(lastOutput, raw)
                    }
                    lastHardwareRaw = raw
                    val totalTodaySteps = currentDelivered.toFloat().coerceAtMost(maxCap.toFloat())
                    var output = baseHardwareRaw + totalTodaySteps
                    if (output < lastOutput) {
                        output = lastOutput
                    }
                    lastOutput = output
                    values[0] = output
                    return
                }

                if (cfg.stepAuto) {
                    // 自动步数模式：每个传感器事件平滑递增增量
                    if (currentDelivered < maxCap) {
                        val stepPerEvent = if (isLiveWechatHook) {
                            maxOf(multiplier, multiplier * 30L).coerceIn(300L, MAX_LIVE_EVENT_STEP_GAIN)
                        } else {
                            multiplier
                        }
                        val gain = stepPerEvent.coerceAtMost(maxCap - currentDelivered)
                        currentDelivered += gain
                    } else {
                        logCapReached(maxCap)
                    }
                } else {
                    // 真实步数倍率模式：当硬件产生有效步数时按 multiplier 倍率放大（含 1x 基础步数）
                    val deltaRaw = (raw - lastHardwareRaw).coerceIn(0f, 10_000f)
                    if (isLiveWechatHook) {
                        if (deltaRaw > 0f && deltaRaw < 500f) {
                            val realGain = (deltaRaw * multiplier).toLong().coerceAtLeast(0L)
                            pendingSteps = (pendingSteps + realGain).coerceAtMost((maxCap - currentDelivered).coerceAtLeast(0L))
                        }
                        if (pendingSteps > 0L && currentDelivered < maxCap) {
                            val chunk = pendingSteps
                                .coerceAtMost(MAX_LIVE_EVENT_STEP_GAIN)
                                .coerceAtMost(maxCap - currentDelivered)
                            currentDelivered += chunk
                            pendingSteps = (pendingSteps - chunk).coerceAtLeast(0L)
                        } else if (currentDelivered >= maxCap) {
                            pendingSteps = 0L
                            logCapReached(maxCap)
                        }
                    } else if (deltaRaw > 0f) {
                        val realGain = (deltaRaw * multiplier).toLong().coerceAtLeast(0L)
                        if (currentDelivered < maxCap) {
                            val gain = realGain.coerceAtMost(maxCap - currentDelivered)
                            currentDelivered += gain
                        } else {
                            logCapReached(maxCap)
                        }
                    }
                }
                lastHardwareRaw = raw

                // 核心输出：保证单调递增，且当日增量严格 <= maxCap
                val totalTodaySteps = currentDelivered.toFloat().coerceAtMost(maxCap.toFloat())
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

    private fun resolveWechatVersionKey(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val verCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${verCode}_${info.lastUpdateTime}"
    }.getOrDefault("")

    private fun installMessageStorageHook(context: Context, classLoader: ClassLoader) {
        runCatching {
            val cachePrefs = runCatching {
                context.getSharedPreferences(DEX_CACHE_PREFS, Context.MODE_PRIVATE)
            }.getOrNull()
            val versionKey = resolveWechatVersionKey(context)

            var target: Method? = null
            if (cachePrefs != null && versionKey.isNotEmpty() &&
                cachePrefs.getString(KEY_CACHE_VERSION, null) == versionKey
            ) {
                val storageClsName = cachePrefs.getString(KEY_STORAGE_CLASS, null)
                val msgClsName = cachePrefs.getString(KEY_MESSAGE_CLASS, null)
                val methodName = cachePrefs.getString(KEY_INSERT_METHOD, null)
                val paramCount = cachePrefs.getInt(KEY_INSERT_PARAM_COUNT, 0)
                if (!storageClsName.isNullOrBlank() && !msgClsName.isNullOrBlank() && !methodName.isNullOrBlank()) {
                    target = runCatching {
                        val storageCls = Class.forName(storageClsName, false, classLoader)
                        val msgCls = Class.forName(msgClsName, false, classLoader)
                        if (paramCount == 1) {
                            storageCls.getDeclaredMethod(methodName, msgCls)
                        } else {
                            storageCls.getDeclaredMethod(methodName, msgCls, Boolean::class.javaPrimitiveType!!)
                        }.apply { isAccessible = true }
                    }.getOrNull()
                }
            }

            if (target == null) {
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
                target = candidates.first().apply { isAccessible = true }
                if (cachePrefs != null && versionKey.isNotEmpty()) {
                    runCatching {
                        cachePrefs.edit()
                            .putString(KEY_CACHE_VERSION, versionKey)
                            .putString(KEY_STORAGE_CLASS, target.declaringClass.name)
                            .putString(KEY_MESSAGE_CLASS, messageClass.name)
                            .putString(KEY_INSERT_METHOD, target.name)
                            .putInt(KEY_INSERT_PARAM_COUNT, target.parameterCount)
                            .apply()
                    }
                }
            }

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
            logger("微信防撤回消息缓存 Hook 已就绪 (${target.declaringClass.simpleName}.${target.name})", null)
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
            val cachePrefs = runCatching {
                context.getSharedPreferences(DEX_CACHE_PREFS, Context.MODE_PRIVATE)
            }.getOrNull()
            val versionKey = resolveWechatVersionKey(context)

            var cachedParser: Method? = null
            if (cachePrefs != null && versionKey.isNotEmpty() &&
                cachePrefs.getString(KEY_CACHE_VERSION, null) == versionKey
            ) {
                val parserClsName = cachePrefs.getString(KEY_XML_PARSER_CLASS, null)
                val parserMethodName = cachePrefs.getString(KEY_XML_PARSER_METHOD, null)
                if (!parserClsName.isNullOrBlank() && !parserMethodName.isNullOrBlank()) {
                    cachedParser = runCatching {
                        val cls = Class.forName(parserClsName, false, classLoader)
                        cls.getDeclaredMethod(
                            parserMethodName,
                            String::class.java,
                            String::class.java,
                            String::class.java,
                        ).apply { isAccessible = true }
                    }.getOrNull()
                }
            }

            val known = cachedParser ?: runCatching {
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
            if (cachedParser == null && cachePrefs != null && versionKey.isNotEmpty()) {
                runCatching {
                    cachePrefs.edit()
                        .putString(KEY_CACHE_VERSION, versionKey)
                        .putString(KEY_XML_PARSER_CLASS, parser.declaringClass.name)
                        .putString(KEY_XML_PARSER_METHOD, parser.name)
                        .apply()
                }
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
            logger("微信防撤回 XML 提示 Hook 已就绪 (${parser.declaringClass.simpleName}.${parser.name})", null)
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
        const val AUTO_TICK_INTERVAL_MS = 1200L
        const val MAX_LIVE_EVENT_STEP_GAIN = 2_000L

        val RECALL_MEDIA_TABLES = setOf("ImgInfo2", "videoinfo2", "voiceinfo", "voiceinfo2")
        const val FIELD_MSG_ID = "field_msgId"
        const val FIELD_CREATE_TIME = "field_createTime"
        const val FIELD_TYPE = "field_type"
        const val FIELD_CONTENT = "field_content"

        const val DEX_CACHE_PREFS = "weitiao_wechat_dex_cache"
        const val KEY_CACHE_VERSION = "wechat_version_key"
        const val KEY_STORAGE_CLASS = "storage_class_name"
        const val KEY_MESSAGE_CLASS = "message_class_name"
        const val KEY_INSERT_METHOD = "insert_method_name"
        const val KEY_INSERT_PARAM_COUNT = "insert_param_count"
        const val KEY_XML_PARSER_CLASS = "xml_parser_class_name"
        const val KEY_XML_PARSER_METHOD = "xml_parser_method_name"
    }
}

