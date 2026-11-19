package com.weitiao.tuning

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import com.weitiao.tuning.core.ApkDigestCache
import com.weitiao.tuning.core.ApkDigestIdentity
import com.weitiao.tuning.core.LocalWechatStepProvider
import com.weitiao.tuning.core.SharedPrefsApkDigestStore
import com.weitiao.tuning.core.SportKvStepReader
import com.weitiao.tuning.core.ExactStepEngine
import com.weitiao.tuning.core.ReviewedWechatAdaptations
import com.weitiao.tuning.core.StepBackend
import com.weitiao.tuning.core.StepBackendSelector
import com.weitiao.tuning.core.ExactWechatResolver
import com.weitiao.tuning.core.WechatVersion
import com.weitiao.tuning.core.StepHookReporter
import com.weitiao.tuning.core.StepPlanPolicy
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

    @Volatile private var reporter: StepHookReporter? = null
    @Volatile private var activeBackend = "SENSOR"
    @Volatile private var backendReason = "通用传感器模式"
    private val heartbeatStarted = AtomicBoolean()
    @Volatile private var installedBackendRequest = "SENSOR"
    private val stepEngine = WechatStepEngine(logger = { message, error ->
        if (error != null) runCatching { reporter?.error(error) }
        logger(message, error)
    })
    @Volatile private var messageInsertMethod: Method? = null
    @Volatile private var messageStorage: Any? = null

    private class RegisteredStepListener(
        val listenerRef: WeakReference<SensorEventListener>,
        val sensor: Sensor,
        val handler: Handler?,
        @Volatile var templateEvent: SensorEvent? = null,
    )

    fun register(classLoader: ClassLoader, processName: String?) {
        if (processName != "com.tencent.mm") return
        if (config().stepBackend != "SENSOR") activeBackend = "PENDING"
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
        runCatching {
            val stepPrefs = context.getSharedPreferences(STEP_STATE_PREFS, Context.MODE_PRIVATE)
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            reporter = StepHookReporter(stepPrefs, BuildConfig.VERSION_NAME, "${info.versionName}/$versionCode")
            configureBackend(context, stepPrefs, classLoader, info, versionCode)
            if (activeBackend == "EXACT") return@runCatching
            stepEngine.bindStateStore(PrefsStepStateStore(stepPrefs))
            val sportKvProvider = SportKvStepReader.fromFiles(
                kvFile = File(context.filesDir, "mmkv/SportKv"),
            )
            stepEngine.bindLocalStepProvider(sportKvProvider)
            val restoredRaw = stepEngine.currentHardwareBaseline()
            if (restoredRaw > 0f && lastRawHardwareStep <= 0f) {
                lastRawHardwareStep = restoredRaw
            }
        }.onFailure { activeBackend = "SENSOR"; backendReason = "初始化失败，已回退传感器"; logger("初始化微信步数持久化状态失败", it) }

        startHeartbeat()
        installAntiRecallCore(classLoader)
        Thread({
            installMessageStorageHook(context, classLoader)
            installXmlParserHook(context, classLoader)
        }, "Tuning-WeChatScan").apply { isDaemon = true }.start()
        logger("微信防撤回与计步持久化已在 $processName 初始化", null)
    }

    private fun configureBackend(
        context: Context,
        prefs: SharedPreferences,
        classLoader: ClassLoader,
        packageInfo: PackageInfo,
        versionCode: Long,
    ) {
        val versionName = packageInfo.versionName.orEmpty()
        installedBackendRequest = config().stepBackend
        val requested = runCatching { StepBackend.valueOf(installedBackendRequest) }.getOrDefault(StepBackend.SENSOR)
        val version = WechatVersion(versionName, versionCode)
        val descriptor = ReviewedWechatAdaptations.forVersion(version)
        val digest = if (requested != StepBackend.SENSOR && descriptor != null) {
            val apkFile = File(context.applicationInfo.sourceDir)
            val identity = ApkDigestIdentity(
                packageName = context.packageName.orEmpty(),
                versionName = versionName,
                versionCode = versionCode,
                apkPath = apkFile.absolutePath,
                lastUpdateTime = packageInfo.lastUpdateTime,
                fileLength = apkFile.length(),
                lastModified = apkFile.lastModified(),
            )
            // Cache only skips re-reading the APK stream when metadata matches; probe() always runs below.
            ApkDigestCache.getOrCompute(identity, SharedPrefsApkDigestStore(prefs)) {
                ApkDigestCache.sha256Hex(apkFile)
            }
        } else null
        val resolution = if (requested != StepBackend.SENSOR) ExactWechatResolver().probe(classLoader, version, descriptor, digest) else null
        val selection = StepBackendSelector.select(requested, resolution)
        activeBackend = "SENSOR"
        backendReason = selection.reason
        if (!selection.installExact || config().stepMode != TuningConfig.MODE_PLAN) {
            if (selection.installExact) backendReason = "精确模式当前仅支持每日计划，已回退传感器"
            return
        }
        val candidate = resolution?.candidate ?: return
        val localSteps = SportKvStepReader.fromFiles(File(context.filesDir, "mmkv/SportKv"))
        val exact = ExactStepEngine(
            load = {
                val state = prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap().toMutableMap()
                val calendar = Calendar.getInstance()
                val day = calendar.get(Calendar.YEAR) * 10000 + (calendar.get(Calendar.MONTH) + 1) * 100 + calendar.get(Calendar.DAY_OF_MONTH)
                val local = localSteps.readToday(day)
                if (local != null && local.todayKey == day) {
                    if (state["plan_day"] != day.toLong()) state["step_current_delivered"] = 0L
                    state["plan_day"] = day.toLong()
                    state["step_current_delivered"] = maxOf(state["step_current_delivered"] ?: 0L, local.todaySteps)
                    if (state["exact_day"] == day.toLong()) state["exact_peak"] = maxOf(state["exact_peak"] ?: 0L, local.todaySteps)
                }
                state
            },
            save = { state ->
                val editor = prefs.edit()
                state.forEach { (key, value) -> editor.putLong(key, value) }
                check(editor.commit()) { "精确步数状态持久化失败" }
            },
        )
        try {
            install(candidate.read) { chain ->
                val original = chain.proceed() as? Long ?: return@install null
                if (activeBackend != "EXACT") return@install original
                val cfg = config()
                if (cfg.stepMode != TuningConfig.MODE_PLAN) return@install original
                val effective = runCatching { exact.evaluate(original, cfg, true) }.getOrElse { error ->
                    runCatching { reporter?.error(error) }; original
                }
                runCatching {
                    reporter?.event(
                        original.toFloat(),
                        effective.toFloat(),
                        false,
                        cfg.stepMode,
                        cfg.stepBackend,
                        cfg.stepGeneration,
                    )
                }
                effective
            }
            install(candidate.upload) { chain ->
                val original = chain.getArg(0) as? Long ?: return@install chain.proceed()
                if (activeBackend != "EXACT") return@install chain.proceed()
                val cfg = config()
                val effective = runCatching { exact.evaluate(original, cfg, false) }.getOrElse { error ->
                    runCatching { reporter?.error(error) }; original
                }
                val args = chain.args.toTypedArray()
                args[0] = effective
                chain.proceed(args)
            }
            // Both callbacks are installed before enabling their write gate; sensor callbacks remain passthrough.
            activeBackend = "EXACT"
            backendReason = "Play 版微信 8.0.80 / 3200 已通过真机验证：计划重建、首读无突跳、窗口内渐进增长。"
        } catch (error: Exception) {
            activeBackend = "SENSOR"
            backendReason = "精确Hook安装失败，已回退传感器：${error.javaClass.simpleName}"
            runCatching { reporter?.error(error) }
        }
    }

    private fun startHeartbeat() {
        if (!heartbeatStarted.compareAndSet(false, true)) return
        fun publish() = runCatching {
            val cfg = config()
            val reason = when {
                cfg.stepBackend != installedBackendRequest -> "后端设置已变更，请重启微信应用；当前仍使用 $activeBackend"
                activeBackend == "EXACT" && cfg.stepMode != TuningConfig.MODE_PLAN -> "精确模式仅支持每日计划；更改模式后请重启微信，当前业务Hook放行原值"
                else -> backendReason
            }
            reporter?.heartbeat(
                cfg.stepEnabled,
                activeBackend,
                reason,
                activeBackend == "EXACT" || stepEngine.hasHardwareReading(),
                cfg.stepMode,
                cfg.stepBackend,
                cfg.stepGeneration,
            )
        }
        publish()
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(60_000L) } catch (_: InterruptedException) { break }
                publish()
            }
        }, "WeiTiao-StepHeartbeat").apply { isDaemon = true }.start()
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
                            stepEngine.markRealHardwareReading(values[0])
                        }
                    }
                }

                val cfg = config()
                if (cfg.stepEnabled && processedInDispatchQueue.get() != true) {
                    if (activeBackend == "SENSOR") {
                        val original = values[0]
                        stepEngine.process(values, isStepDetector, cfg, isLiveWechatHook = true)
                        runCatching {
                            reporter?.event(
                                original,
                                values[0],
                                syntheticDispatchInProgress.get() == true,
                                cfg.stepMode,
                                cfg.stepBackend,
                                cfg.stepGeneration,
                            )
                        }
                    }
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
     *
     * 关键安全门禁：在尚未获取到真实硬件计步基准（或已持久化的真实基准）前，绝不派发 0f / 1000f 假事件，
     * 彻底杜绝污染微信的「今日零点/手机重启基准值」。
     */
    private fun ensureAutoTickerRunning() {
        if (!autoTickerStarted.compareAndSet(false, true)) return
        Thread({
            while (true) {
                try {
                    Thread.sleep(AUTO_TICK_INTERVAL_MS)
                    val cfg = config()
                    if (activeBackend != "SENSOR" || !cfg.stepEnabled || !stepEngine.shouldDispatchAutoTick(cfg)) {
                        continue
                    }
                    val currentRawHw = lastRawHardwareStep.takeIf { it > 0f }
                        ?: stepEngine.currentHardwareBaseline().takeIf { it > 0f }
                        ?: continue
                    val iter = activeStepListeners.entries.iterator()
                    while (iter.hasNext()) {
                        val (_, entry) = iter.next()
                        val listener = entry.listenerRef.get()
                        if (listener == null) {
                            iter.remove()
                            continue
                        }
                        val event = entry.templateEvent
                            ?: createFallbackSensorEvent(entry.sensor, currentRawHw)?.also { entry.templateEvent = it }
                            ?: continue
                        val targetHandler = entry.handler ?: Handler(Looper.getMainLooper())
                        targetHandler.post {
                            runCatching {
                                val currentCfg = config()
                                if (!currentCfg.stepEnabled || !stepEngine.shouldDispatchAutoTick(currentCfg)) {
                                    return@runCatching
                                }
                                val rawHw = lastRawHardwareStep.takeIf { it > 0f }
                                    ?: stepEngine.currentHardwareBaseline().takeIf { it > 0f }
                                    ?: return@runCatching
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

    private fun createFallbackSensorEvent(sensor: Sensor, initialRaw: Float): SensorEvent? {
        if (initialRaw <= 0f) return null
        return runCatching {
            val ctor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
            val event = ctor.newInstance(16)
            findField(SensorEvent::class.java, "sensor")?.set(event, sensor)
            event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            event.timestamp = SystemClock.elapsedRealtimeNanos()
            if (event.values != null && event.values.isNotEmpty()) {
                event.values[0] = initialRaw
            }
            event
        }.getOrNull()
    }

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
                            stepEngine.markRealHardwareReading(values[0])
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

                    if (activeBackend == "SENSOR") {
                        val original = values[0]
                        stepEngine.process(values, isStepDetector, cfg, isLiveWechatHook = true)
                        runCatching {
                            reporter?.event(
                                original,
                                values[0],
                                false,
                                cfg.stepMode,
                                cfg.stepBackend,
                                cfg.stepGeneration,
                            )
                        }
                    }

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

    internal interface StepStateStore {
        fun getInt(key: String, default: Int): Int
        fun getLong(key: String, default: Long): Long
        fun getFloat(key: String, default: Float): Float
        fun getBoolean(key: String, default: Boolean): Boolean
        fun savePlan(values: Map<String, Long>) {}
        fun saveState(
            todayKey: Int,
            baseHardwareRaw: Float,
            lastHardwareRaw: Float,
            currentDelivered: Long,
            lastOutput: Float,
            baselineEstablished: Boolean,
        )
    }

    internal class PrefsStepStateStore(
        private val prefs: SharedPreferences,
    ) : StepStateStore {
        override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
        override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
        override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
        override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

        private var pendingPlan: Map<String, Long> = emptyMap()

        override fun savePlan(values: Map<String, Long>) {
            pendingPlan = values.toMap()
        }

        override fun saveState(
            todayKey: Int,
            baseHardwareRaw: Float,
            lastHardwareRaw: Float,
            currentDelivered: Long,
            lastOutput: Float,
            baselineEstablished: Boolean,
        ) {
            val editor = prefs.edit()
            pendingPlan.forEach { (key, value) -> editor.putLong(key, value) }
            if (!pendingPlan.containsKey("step_real_today")) editor.remove("step_real_today")
            if (!pendingPlan.containsKey("daily_target")) editor.remove("daily_target")
            editor
                .putInt(KEY_STATE_TODAY, todayKey)
                .putFloat(KEY_STATE_BASE_RAW, baseHardwareRaw)
                .putFloat(KEY_STATE_LAST_RAW, lastHardwareRaw)
                .putLong(KEY_STATE_DELIVERED, currentDelivered)
                .putFloat(KEY_STATE_LAST_OUTPUT, lastOutput)
                .putBoolean(KEY_STATE_BASELINE_ESTABLISHED, baselineEstablished)
                .putLong(KEY_STATE_SAVED_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * 计步状态引擎（内存计算 + 宿主 SharedPreferences + 微信本地 `SportKv` MMKV 三方协同）：
     * 1. 真实硬件门禁 (`hasRealHardwareReading`)：未收到真实硬件 `TYPE_STEP_COUNTER` 读数（`raw > 0f`）前绝不注入 0f / 1000f，
     *    彻底杜绝污染微信的「今日零点/重启基准值」。
     * 2. 结合微信本地 `SportKv`（`KEY_STEP_DETAIL_INFO` / `KEY_LAST_UPLOAD_DEVICE_STEP_COUNT_LONG`）：
     *    每次运行时自动读取微信本地今日已记录步数与传感器锚点，将未注入/空窗期期间的真实步数合并计入当日进度，
     *    并对齐微信当前预期传感器基准，彻底避免「各走各的」导致的重复累加超上限或恢复注入跳变。
     * 3. 首帧锚点保护：当日首个计步事件仅对齐基准（0 突跳），防止被微信首帧吞没或判定为异常跳变。
     * 4. 严格封顶：`values[0]` 注入值永远保证今日增量不突破 `stepMaximum`。
     */
    internal class WechatStepEngine(
        @Volatile private var stateStore: StepStateStore? = null,
        private val clock: () -> Long = System::currentTimeMillis,
        private val logger: (String, Throwable?) -> Unit,
    ) {
        @Volatile private var localStepProvider: LocalWechatStepProvider? = null
        @Volatile private var todayKey = 0
        @Volatile private var baseHardwareRaw = 0f
        @Volatile private var lastHardwareRaw = 0f
        @Volatile private var lastOutput = 0f
        @Volatile private var currentDelivered = 0L
        @Volatile private var pendingSteps = 0L
        @Volatile private var baselineEstablished = false
        @Volatile private var hasRealHardwareReading = false
        @Volatile private var lastClampLogTime = 0L
        @Volatile private var lastSavedAtMs = 0L
        @Volatile private var emittedMinOutput = Float.NaN
        @Volatile private var emittedMaxOutput = Float.NaN
        @Volatile private var lastLocalSyncLogKey = ""

        private val plan = mutableMapOf<String, Long>()
        private var policyErrorLogged = false
        private var needsRecoveryAnchor = false

        init {
            loadPlan()
            stateStore?.let { syncFromStoreLocked(it) }
        }

        @Synchronized
        fun bindStateStore(store: StepStateStore) {
            this.stateStore = store
            loadPlan()
            syncFromStoreLocked(store)
            reconcileWithLocalWechatLocked(currentDayKey(), raw = 0f)
        }

        @Synchronized
        fun bindLocalStepProvider(provider: LocalWechatStepProvider) {
            this.localStepProvider = provider
            reconcileWithLocalWechatLocked(currentDayKey(), raw = 0f)
        }

        @Synchronized
        fun markRealHardwareReading(raw: Float) {
            if (raw > 0f) {
                hasRealHardwareReading = true
            }
        }

        @Synchronized
        fun hasHardwareReading(): Boolean =
            hasRealHardwareReading || lastHardwareRaw > 0f || baseHardwareRaw > 0f

        @Synchronized
        fun processStepCounter(raw: Float, cfg: TuningConfig): Float {
            if (raw > 0f) {
                hasRealHardwareReading = true
            }
            val arr = floatArrayOf(raw)
            process(arr, isDetector = false, cfg = cfg, isLiveWechatHook = false)
            return arr[0]
        }

        @Synchronized
        fun processPassthrough(raw: Float): Float = raw

        @Synchronized
        fun currentHardwareBaseline(): Float =
            lastHardwareRaw.takeIf { it > 0f } ?: baseHardwareRaw.takeIf { it > 0f } ?: 0f

        @Synchronized
        fun shouldDispatchAutoTick(cfg: TuningConfig): Boolean {
            if (!cfg.stepEnabled) return false
            if (!hasHardwareReading()) return false
            val calendar = Calendar.getInstance().apply { timeInMillis = clock() }
            val today = currentDayKey(calendar)
            if (todayKey != today) return true // Let process reset the day before reconciling SportKv.
            stateStore?.let { syncFromStoreLocked(it, today) }
            reconcileWithLocalWechatLocked(today, raw = 0f)
            if (currentDelivered >= cfg.stepMaximum.coerceAtLeast(1)) return false
            if (cfg.stepMode == "PLAN") {
                val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
                return minute in cfg.stepWindowStart until cfg.stepWindowEnd ||
                    (minute >= cfg.stepWindowEnd && (plan["plan_phase"] == 1L || currentDelivered < (plan["end_floor"] ?: 0L)))
            }
            val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            if (minute >= cfg.stepWindowEnd && cfg.stepAfterWindow == "FREEZE") return false
            return (minute < cfg.stepWindowEnd && cfg.stepMode == "LEGACY_AUTO") || pendingSteps > 0L || !baselineEstablished
        }

        private fun currentDayKey(calendar: Calendar = Calendar.getInstance().apply { timeInMillis = clock() }): Int =
            calendar.get(Calendar.YEAR) * 10000 +
                (calendar.get(Calendar.MONTH) + 1) * 100 +
                calendar.get(Calendar.DAY_OF_MONTH)

        private fun syncFromStoreLocked(store: StepStateStore, today: Int = currentDayKey()) {
            val storedToday = store.getInt(KEY_STATE_TODAY, 0)
            if (storedToday <= 0) return
            val storedBaseRaw = store.getFloat(KEY_STATE_BASE_RAW, 0f)
            val storedLastRaw = store.getFloat(KEY_STATE_LAST_RAW, 0f)
            val storedDelivered = store.getLong(KEY_STATE_DELIVERED, 0L)
            val storedLastOutput = store.getFloat(KEY_STATE_LAST_OUTPUT, 0f)
            val storedBaseline = store.getBoolean(KEY_STATE_BASELINE_ESTABLISHED, false)
            val storedSavedAt = store.getLong(KEY_STATE_SAVED_AT, 0L)

            if (storedLastRaw > 0f || storedBaseRaw > 0f) {
                hasRealHardwareReading = true
            }
            if (storedSavedAt > lastSavedAtMs) {
                lastSavedAtMs = storedSavedAt
            }

            if (storedToday == today) {
                if (todayKey != today) {
                    todayKey = today
                    baseHardwareRaw = storedBaseRaw
                    lastHardwareRaw = storedLastRaw
                    currentDelivered = storedDelivered
                    lastOutput = storedLastOutput
                    baselineEstablished = storedBaseline
                } else {
                    if (storedBaseRaw != 0f && baseHardwareRaw == 0f) {
                        baseHardwareRaw = storedBaseRaw
                    }
                    if (storedLastRaw > lastHardwareRaw) {
                        lastHardwareRaw = storedLastRaw
                    }
                    if (storedDelivered > currentDelivered) {
                        currentDelivered = storedDelivered
                    }
                    if (storedLastOutput > lastOutput) {
                        lastOutput = storedLastOutput
                    }
                    if (storedBaseline) {
                        baselineEstablished = true
                    }
                }
            } else if (todayKey == 0) {
                // 跨天且当前进程刚启动：继承昨日最终输出值与硬件读数作为跨天单调递增起点
                lastOutput = maxOf(lastOutput, storedLastOutput)
                if (storedLastRaw > 0f && lastHardwareRaw <= 0f) {
                    lastHardwareRaw = storedLastRaw
                }
            }
        }

        private fun recordEmittedOutputLocked(output: Float) {
            if (emittedMinOutput.isNaN() || output < emittedMinOutput) {
                emittedMinOutput = output
            }
            if (emittedMaxOutput.isNaN() || output > emittedMaxOutput) {
                emittedMaxOutput = output
            }
        }

        /**
         * 查看微信本地 `SportKv` 是否已有今日数据，并与微调自身步数状态合并：
         * - 微信今日步数若高于微调记录（例如临时 Root 重启窗口期或未开启微调期间已走动），立即对齐 `currentDelivered`；
         * - 若微信本地传感器锚点与微调持久化状态出现偏移（例如重启后微信裸跑过或跨进程更新），重新锚定 `lastOutput`，
         *   避免恢复注入时发送过高的旧输出造成暴跳或再次累加。
         */
        private fun reconcileWithLocalWechatLocked(today: Int, raw: Float) {
            val local = localStepProvider?.readToday(today) ?: return
            if (local.todayKey != today) return

            val localHadMoreSteps = local.todaySteps > currentDelivered
            var updated = false
            if (localHadMoreSteps) {
                currentDelivered = local.todaySteps
                pendingSteps = 0L
                updated = true
            }

            if (local.hasSensorValue) {
                val localSensor = local.lastSensorValue.coerceAtLeast(0f)
                val emittedByThisProcess = !emittedMinOutput.isNaN() &&
                    localSensor >= emittedMinOutput - 1f &&
                    localSensor <= emittedMaxOutput + 1f

                if (!emittedByThisProcess) {
                    val storeHasToday = (stateStore?.getInt(KEY_STATE_TODAY, 0) ?: 0) == today
                    val storedLastOutput = if (storeHasToday) stateStore?.getFloat(KEY_STATE_LAST_OUTPUT, 0f) ?: 0f else 0f
                    val storedDelivered = if (storeHasToday) stateStore?.getLong(KEY_STATE_DELIVERED, 0L) ?: 0L else 0L
                    val storedSavedAt = if (storeHasToday) stateStore?.getLong(KEY_STATE_SAVED_AT, 0L) ?: 0L else 0L

                    val localDivergedFromStore = !storeHasToday ||
                        local.todaySteps > storedDelivered ||
                        (kotlin.math.abs(localSensor - storedLastOutput) > 1f &&
                            (storedSavedAt <= 0L || local.saveTimeMs > storedSavedAt))

                    val localNewerThanLiveEngine = baselineEstablished &&
                        local.saveTimeMs > lastSavedAtMs &&
                        (localHadMoreSteps || kotlin.math.abs(localSensor - lastOutput) > 1f)

                    if ((!baselineEstablished && localDivergedFromStore) || localNewerThanLiveEngine) {
                        lastOutput = localSensor
                        baseHardwareRaw = localSensor - currentDelivered.toFloat()
                        baselineEstablished = true
                        if (lastHardwareRaw <= 0f && raw > 0f) {
                            lastHardwareRaw = raw
                        }
                        emittedMinOutput = localSensor
                        emittedMaxOutput = localSensor
                        updated = true
                    }
                }
            }

            if (updated) {
                if (todayKey != today) {
                    todayKey = today
                }
                if (stateStore != null) {
                    persistToStoreLocked()
                }
                val logKey = "$today:${local.todaySteps}:${local.lastSensorValue}"
                if (logKey != lastLocalSyncLogKey) {
                    lastLocalSyncLogKey = logKey
                    logger(
                        "已结合微信本地步数数据：今日累计=${currentDelivered}，传感器锚点=${lastOutput}",
                        null,
                    )
                }
            }
        }

        private fun persistToStoreLocked() {
            lastSavedAtMs = clock()
            stateStore?.savePlan(plan)
            stateStore?.saveState(
                todayKey = todayKey,
                baseHardwareRaw = baseHardwareRaw,
                lastHardwareRaw = lastHardwareRaw,
                currentDelivered = currentDelivered,
                lastOutput = lastOutput,
                baselineEstablished = baselineEstablished,
            )
        }

        private fun loadPlan() {
            val store = stateStore ?: return
            for (key in listOf("plan_day", "plan_gen", "seed", "start_at", "end_at", "start_real", "start_value",
                "daily_target", "natural_real", "step_real_today", "plan_schema", "plan_signature", "plan_phase", "end_floor")) {
                plan[key] = store.getLong(key, 0L)
            }
        }

        private fun planValue(cfg: TuningConfig, now: Long, today: Int, delta: Long): Long {
            if (plan["plan_day"] != today.toLong()) {
                val seed = plan["seed"] ?: 0L
                plan.clear()
                plan["seed"] = seed
                plan["plan_day"] = today.toLong()
            }
            val real = ((plan["step_real_today"] ?: 0L) + delta).coerceAtLeast(0L)
            plan["step_real_today"] = real
            val cap = cfg.stepMaximum.coerceAtLeast(1)
            val signature = listOf(cfg.stepMode, cfg.stepTarget, cfg.stepJitter, cfg.stepWindowStart,
                cfg.stepWindowEnd, cfg.stepAfterWindow, cfg.stepMultiplier, cap).joinToString(":").hashCode().toLong()
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            fun atMinute(minute: Int): Long = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val windowStart = atMinute(cfg.stepWindowStart.coerceIn(0, 1439))
            val windowEnd = atMinute(cfg.stepWindowEnd.coerceIn(cfg.stepWindowStart.coerceIn(0, 1439) + 1, 1440))
            val invalid = plan["plan_schema"] != 1L || plan["plan_gen"] != cfg.stepGeneration ||
                plan["plan_signature"] != signature || (plan["daily_target"] ?: 0L) !in 1L..cap.toLong() ||
                (plan["end_at"] ?: 0L) != windowEnd || (plan["start_at"] ?: 0L) !in windowStart..windowEnd ||
                (plan["start_value"] ?: -1L) < 0L || (plan["start_real"] ?: -1L) < 0L ||
                (plan["natural_real"] ?: -1L) < 0L
            if (invalid) {
                if ((plan["seed"] ?: 0L) == 0L) plan["seed"] = java.security.SecureRandom().nextLong().let { if (it == 0L) 1L else it }
                plan["plan_schema"] = 1L
                plan["plan_gen"] = cfg.stepGeneration
                plan["plan_signature"] = signature
                plan["start_at"] = maxOf(now, windowStart).coerceAtMost(windowEnd)
                plan["end_at"] = windowEnd
                plan["start_real"] = real
                plan["start_value"] = currentDelivered
                plan["natural_real"] = real
                val epochDay = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                plan["daily_target"] = StepPlanPolicy.dailyTarget(cfg.stepTarget, cfg.stepJitter, plan.getValue("seed"), epochDay, cap).toLong()
                plan["end_floor"] = currentDelivered
                plan["plan_phase"] = if (now < windowEnd) 1L else 2L
                // A settings change must not swallow walking observed in this same event.
                val naturalGain = if ((now < windowStart || now >= windowEnd) &&
                    !(now >= windowEnd && cfg.stepAfterWindow == "FREEZE")) delta else 0L
                val initial = (currentDelivered + naturalGain).coerceAtMost(cap.toLong())
                plan["end_floor"] = maxOf(currentDelivered, initial)
                return maxOf(currentDelivered, initial)
            }
            val result = if (now >= windowStart && now < windowEnd) {
                plan["natural_real"] = real
                StepPlanPolicy.planned(real, now, plan.getValue("start_at"), windowEnd,
                    plan.getValue("start_real"), plan.getValue("start_value"), currentDelivered, plan.getValue("daily_target").toInt())
            } else {
                var peak = currentDelivered
                if (now >= windowEnd && plan["plan_phase"] == 1L) {
                    // Settle the last window interval, even when the first wake-up is after the cutoff.
                    peak = maxOf(peak, plan.getValue("daily_target"))
                    plan["end_floor"] = peak
                    plan["plan_phase"] = 2L
                }
                if (now >= windowEnd) peak = maxOf(peak, plan["end_floor"] ?: 0L)
                val value = if (now >= windowEnd && cfg.stepAfterWindow == "FREEZE") peak
                    else StepPlanPolicy.natural(real, plan["natural_real"] ?: real, peak, 1, cap.toLong())
                plan["natural_real"] = real
                if (now >= windowEnd) plan["end_floor"] = value
                value
            }
            return result.coerceAtMost(cap.toLong())
        }

        @Synchronized
        fun process(values: FloatArray, isDetector: Boolean, cfg: TuningConfig, isLiveWechatHook: Boolean = false) {
            if (values.isEmpty() || !values[0].isFinite()) return
            val original = values[0]
            try {
                processInternal(values, isDetector, cfg, isLiveWechatHook)
            } catch (error: Exception) {
                values[0] = original
                // Re-anchor on recovery instead of adding uncommitted gains to an old sensor value.
                baselineEstablished = false
                needsRecoveryAnchor = true
                plan["plan_schema"] = 0L
                if (!policyErrorLogged) { policyErrorLogged = true; logger("微信步数计算失败，已放行原始步数", error) }
            }
        }

        private fun processInternal(
            values: FloatArray,
            isDetector: Boolean,
            cfg: TuningConfig,
            isLiveWechatHook: Boolean = false,
        ) {
            if (!cfg.stepEnabled) return
            val calendar = Calendar.getInstance().apply { timeInMillis = clock() }
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = currentHour * 60 + calendar.get(Calendar.MINUTE)
            val today = currentDayKey(calendar)

            stateStore?.let { syncFromStoreLocked(it, today) }

            val raw = values[0]
            if (!isDetector) {
                if (raw > 0f) {
                    hasRealHardwareReading = true
                } else if (!hasHardwareReading()) {
                    // 尚未收到任何真实硬件计步读数前，绝不处理 raw <= 0f 的无效事件，防止将微信基准污染为 0
                    return
                }
            }

            val maxCap = cfg.stepMaximum.coerceAtLeast(1).toLong()
            val multiplier = cfg.stepMultiplier.coerceIn(1, 10_000).toLong()

            // 跨天重置
            if (todayKey != today) {
                todayKey = today
                baseHardwareRaw = maxOf(lastOutput, raw)
                if (!isDetector) lastHardwareRaw = raw
                currentDelivered = 0L
                pendingSteps = 0L
                baselineEstablished = false
                emittedMinOutput = Float.NaN
                emittedMaxOutput = Float.NaN
            }

            // 每次处理均核对并结合微信本地 SportKv 数据
            reconcileWithLocalWechatLocked(today, if (!isDetector) raw else 0f)

            // 设备重启检测（raw < lastHardwareRaw）：对齐硬件基准，维持当前输出不倒退
            if (!isDetector && raw > 0f && raw + 100f < lastHardwareRaw) {
                baseHardwareRaw = lastOutput - currentDelivered.toFloat()
                lastHardwareRaw = raw
            }

            if (needsRecoveryAnchor && !isDetector) {
                lastOutput = raw
                lastHardwareRaw = raw
                baselineEstablished = false
                needsRecoveryAnchor = false
            }

            if (cfg.stepMode == "PLAN") {
                // Counter and detector are alternate sources; never count the same walk twice.
                if (isDetector && hasHardwareReading()) { values[0] = 0f; return }
                val delta = if (isDetector) 1L else if (baselineEstablished && lastHardwareRaw > 0f)
                    (raw - lastHardwareRaw).takeIf { it > 0f && it < 500f }?.toLong() ?: 0L else 0L
                if (!isDetector && !baselineEstablished) {
                    baselineEstablished = true
                    lastOutput = maxOf(lastOutput, raw)
                    lastHardwareRaw = raw
                }
                val desired = planValue(cfg, calendar.timeInMillis, today, delta)
                val gain = (desired - currentDelivered).coerceIn(0L, MAX_LIVE_EVENT_STEP_GAIN)
                    .coerceAtMost((maxCap - currentDelivered).coerceAtLeast(0L))
                currentDelivered += gain
                if (isDetector) values[0] = gain.toFloat() else {
                    lastHardwareRaw = raw
                    lastOutput += gain.toFloat()
                    baseHardwareRaw = lastOutput - currentDelivered.toFloat()
                    values[0] = lastOutput
                    recordEmittedOutputLocked(lastOutput)
                }
                persistToStoreLocked()
                return
            }

            plan["plan_day"] = 0L
            plan["plan_gen"] = cfg.stepGeneration
            plan.remove("step_real_today")
            plan.remove("daily_target")

            // 截止时间判定：到达或超过截止小时，冻结增加步数
            if (minute >= cfg.stepWindowEnd && cfg.stepAfterWindow == "FREEZE") {
                if (!isDetector && raw > 0f) {
                    lastHardwareRaw = raw
                }
                if (isDetector) {
                    values[0] = 0f
                } else {
                    val output = lastOutput
                    recordEmittedOutputLocked(output)
                    values[0] = output
                    persistToStoreLocked()
                }
                return
            }

            if (isDetector) {
                // Type 18: STEP_DETECTOR
                if (currentDelivered < maxCap) {
                    val gain = multiplier.coerceAtMost(maxCap - currentDelivered)
                    currentDelivered += gain
                    values[0] = gain.toFloat()
                    persistToStoreLocked()
                } else {
                    values[0] = 0f
                    logCapReached(maxCap)
                }
            } else {
                // Type 19: STEP_COUNTER
                // 首帧事件仅建立微信计步基准锚点，不产生瞬间巨幅跳变，确保后续增量被微信 100% 计入今日步数
                if (!baselineEstablished) {
                    baselineEstablished = true
                    if (lastOutput <= 0f || lastOutput < raw) {
                        lastOutput = maxOf(lastOutput, raw)
                    }
                    baseHardwareRaw = lastOutput - currentDelivered.toFloat()
                    if (raw > 0f) {
                        lastHardwareRaw = raw
                    }
                    val output = lastOutput
                    recordEmittedOutputLocked(output)
                    values[0] = output
                    persistToStoreLocked()
                    return
                }

                var gain = 0L
                if (cfg.stepMode == "LEGACY_AUTO" && minute < cfg.stepWindowEnd) {
                    // 自动步数模式：每个传感器事件平滑递增增量
                    if (currentDelivered < maxCap) {
                        val stepPerEvent = if (isLiveWechatHook) {
                            maxOf(multiplier, multiplier * 30L).coerceIn(300L, MAX_LIVE_EVENT_STEP_GAIN)
                        } else {
                            multiplier
                        }
                        gain = stepPerEvent.coerceAtMost(maxCap - currentDelivered)
                        currentDelivered += gain
                    } else {
                        logCapReached(maxCap)
                    }
                } else {
                    // 真实步数倍率模式：当硬件产生有效步数时按 multiplier 倍率放大（含 1x 基础步数）
                    val deltaRaw = if (lastHardwareRaw > 0f) (raw - lastHardwareRaw).coerceIn(0f, 10_000f) else 0f
                    if (isLiveWechatHook) {
                        if (deltaRaw > 0f && deltaRaw < 500f) {
                            val realGain = (deltaRaw * multiplier).toLong().coerceAtLeast(0L)
                            pendingSteps = (pendingSteps + realGain).coerceAtMost((maxCap - currentDelivered).coerceAtLeast(0L))
                        }
                        if (pendingSteps > 0L && currentDelivered < maxCap) {
                            gain = pendingSteps
                                .coerceAtMost(MAX_LIVE_EVENT_STEP_GAIN)
                                .coerceAtMost(maxCap - currentDelivered)
                            currentDelivered += gain
                            pendingSteps = (pendingSteps - gain).coerceAtLeast(0L)
                        } else if (currentDelivered >= maxCap) {
                            pendingSteps = 0L
                            logCapReached(maxCap)
                        }
                    } else if (deltaRaw > 0f) {
                        val realGain = (deltaRaw * multiplier).toLong().coerceAtLeast(0L)
                        if (currentDelivered < maxCap) {
                            gain = realGain.coerceAtMost(maxCap - currentDelivered)
                            currentDelivered += gain
                        } else {
                            logCapReached(maxCap)
                        }
                    }
                }
                if (raw > 0f) {
                    lastHardwareRaw = raw
                }

                // 核心输出：从当前微信预期锚点单调累加本帧增量，并与本地已记步数协同封顶
                val output = lastOutput + gain.toFloat()
                lastOutput = output
                baseHardwareRaw = lastOutput - currentDelivered.toFloat()
                recordEmittedOutputLocked(output)
                values[0] = output
                persistToStoreLocked()
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

    internal companion object {
        internal fun forProcessTest(
            config: () -> TuningConfig,
            logger: (String, Throwable?) -> Unit,
        ): WechatHooks = WechatHooks(
            install = { _, _ -> },
            config = config,
            logger = logger,
        )

        fun resolveCurrentProcessName(classLoader: ClassLoader? = null): String? {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                runCatching { Application.getProcessName() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            runCatching {
                val atClass = Class.forName("android.app.ActivityThread", false, classLoader ?: WechatHooks::class.java.classLoader)
                val method = atClass.getDeclaredMethod("currentProcessName").apply { isAccessible = true }
                method.invoke(null) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            runCatching {
                val bytes = File("/proc/self/cmdline").readBytes()
                val end = bytes.indexOf(0.toByte()).let { if (it >= 0) it else bytes.size }
                String(bytes, 0, end, Charsets.UTF_8).trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            return null
        }

        const val AUTO_TICK_INTERVAL_MS = 30_000L
        const val MAX_LIVE_EVENT_STEP_GAIN = 2_000L

        const val STEP_STATE_PREFS = "weitiao_step_state"
        const val KEY_STATE_TODAY = "step_today_key"
        const val KEY_STATE_BASE_RAW = "step_base_hardware_raw"
        const val KEY_STATE_LAST_RAW = "step_last_hardware_raw"
        const val KEY_STATE_DELIVERED = "step_current_delivered"
        const val KEY_STATE_LAST_OUTPUT = "step_last_output"
        const val KEY_STATE_BASELINE_ESTABLISHED = "step_baseline_established"
        const val KEY_STATE_SAVED_AT = "step_state_saved_at_ms"

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

