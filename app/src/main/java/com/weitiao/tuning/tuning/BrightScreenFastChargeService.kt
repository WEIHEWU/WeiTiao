package com.weitiao.tuning.tuning

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.weitiao.tuning.core.TuningConfig
import com.weitiao.tuning.core.TuningPrefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 亮屏快充常驻前台服务。
 *
 * 设计要点（修复「后台/划掉后台后插电失效」）：
 * 1. **常驻不退**：只要开关处于打开状态，服务就一直以前台服务形式存活，
 *    拔线只做「恢复官方温控 + 隐藏实时通知」，不再 `stopSelf()`。
 *    这样再次插入电源时进程仍在，动态广播 + 主动轮询都能立即命中。
 * 2. **轮询代替纯广播**：每个 tick 直接读取 `ACTION_BATTERY_CHANGED` 粘性广播，
 *    即便系统遗漏/延迟了 `ACTION_POWER_CONNECTED`，最多一个 tick 内也会自愈。
 * 3. **状态自愈**：维护「期望状态 / 已确认状态」，并周期性用 root 复核节点权限位
 *    （三星温控守护进程会重置 `chmod`），被重置后自动重新屏蔽。
 * 4. **兜底链路**：本服务无法启动时由 [FastChargeReceiver] / [FastChargeJobService]
 *    直接下发策略并推送通知，保证插电即时可见。
 *
 * 充电策略：
 * - 80% 保护停充模式：0%~79% 满血快充，达到 80% 自动交还官方电池保护安全停充；
 * - 满血充至 100% 模式：全程不限上限，持续满速快充直至 100%。
 */
class BrightScreenFastChargeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val rootExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Tuning-FastChargeWorker").apply { isDaemon = true }
    }

    private var receiverRegistered = false
    private var foregroundStarted = false
    private val syncScheduled = AtomicBoolean(false)
    private val forceRestoreRequested = AtomicBoolean(false)
    private val isReadingMetrics = AtomicBoolean(false)

    // ---- 充电/屏幕/温度状态 ----
    @Volatile private var isWiredCharging = false
    @Volatile private var wasChargingLastTick = false
    @Volatile private var isScreenOn = true
    @Volatile private var isOverheated = false
    @Volatile private var currentLevel = 0
    @Volatile private var currentTempTenths = 320 // 默认 32.0°C 初始安全值

    // ---- 温控节点状态机 ----
    /** 期望状态：true=屏蔽充电限流温控节点（快充），false=恢复官方温控 */
    @Volatile private var desiredBlocked = false
    /** 已确认落地的状态，null=未知；与期望不一致时触发下发 */
    @Volatile private var appliedBlocked: Boolean? = null
    @Volatile private var rootSuccessState: Boolean? = null
    @Volatile private var lastVerifyElapsed = 0L
    @Volatile private var lastTickElapsed = 0L

    @Volatile private var latestMetrics = FastChargeController.BatteryMetrics(0, 0, 0.0, 0, 0)
    @Volatile private var hasFreshSysfsMetrics = false
    private var lastNotifiedLevel = -1
    private var lastNotifiedWatts = -1.0
    private var lastNotifyTime = 0L
    private var lastNotifiedScreenOn: Boolean? = null
    private var lastNotifiedOverheated: Boolean? = null

    private val tickerRunnable: Runnable = object : Runnable {
        override fun run() {
            handler.removeCallbacks(tickerRunnable)

            if (!isEnabled()) {
                restoreAndStop()
                return
            }

            // 广播驱动时做最小间隔保护，避免 BATTERY_CHANGED 高频触发导致过度轮询
            val now = SystemClock.elapsedRealtime()
            if (now - lastTickElapsed < MIN_TICK_INTERVAL_MS) {
                handler.postDelayed(this, MIN_TICK_INTERVAL_MS - (now - lastTickElapsed))
                return
            }
            lastTickElapsed = now

            // 直接读粘性广播 + 屏幕状态，避免依赖可能被系统合并/延迟的广播
            refreshChargingState()

            if (isWiredCharging) {
                wasChargingLastTick = true
                if (isScreenOn) {
                    // 若已有真实 sysfs 温度数据则立即评估策略；首次采样等待 readMetricsAsync 返回真实温度后再决定是否屏蔽节点
                    if (hasFreshSysfsMetrics || isOverheated) {
                        evaluatePolicy()
                    }
                    readMetricsAsync()
                    updateLiveNotification()
                    handler.postDelayed(this, TICKER_ACTIVE_MS)
                } else {
                    // 息屏充电时强制交还官方温控，暂停 5s 高频采样，亮屏广播会立即恢复。
                    // 仍处于充电状态，因此保留同一条实时通知，但不做高频采样。
                    evaluatePolicy()
                    updateLiveNotification()
                }
            } else {
                enterStandby()
            }
        }
    }

    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
                    if (plugged != isWiredCharging) {
                        if (!plugged) {
                            forceRestoreRequested.set(true)
                        }
                        lastTickElapsed = 0L
                        handler.removeCallbacks(tickerRunnable)
                        handler.post(tickerRunnable)
                    }
                }
                // 插电/亮屏事件响应
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_SCREEN_ON -> {
                    lastTickElapsed = 0L
                    handler.removeCallbacks(tickerRunnable)
                    handler.post(tickerRunnable)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isWiredCharging = false
                    forceRestoreRequested.set(true)
                    lastTickElapsed = 0L
                    handler.removeCallbacks(tickerRunnable)
                    handler.post(tickerRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    forceRestoreRequested.set(true)
                    lastTickElapsed = 0L
                    handler.removeCallbacks(tickerRunnable)
                    handler.post(tickerRunnable)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        FastChargeNotifier.ensureChannels(this)

        // 启动即进入前台（绑定隐藏渠道通知），保证进程优先级，避免被回收
        foregroundStarted = FastChargeNotifier.bindForegroundService(this)
        if (!foregroundStarted) {
            Log.e(TAG, "前台服务启动失败（可能受后台启动限制）")
        }

        val powerManager = getSystemService(PowerManager::class.java)
        isScreenOn = powerManager?.isInteractive ?: true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(chargingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(chargingReceiver, filter)
        }
        receiverRegistered = true

        // 只要功能处于开启状态，就保持兜底任务在册
        FastChargeScheduler.enable(this)

        handler.post(tickerRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceAlive = true
        // 每次 ContextCompat.startForegroundService 触发 onStartCommand 时均必须先同步满足系统的 startForeground 契约，
        // 随后再根据开关或插电状态决定继续运行还是 stopForeground + stopSelf，彻底杜绝 ForegroundServiceDidNotStartInTimeException。
        foregroundStarted = FastChargeNotifier.bindForegroundService(this) || foregroundStarted

        if (intent?.action == ACTION_RESTORE_AND_STOP) {
            restoreAndStop()
            return START_NOT_STICKY
        }
        if (!isEnabled()) {
            restoreAndStop()
            return START_NOT_STICKY
        }

        FastChargeScheduler.enable(this)
        refreshChargingState()
        if (!isWiredCharging) {
            enterStandby()
            return START_NOT_STICKY
        }

        postRoot {
            logResult("启用三星快充系统设置", FastChargeController.enableSamsungFastChargingSettings())
        }

        // 立即跑一轮，保证插电/开关切换后马上生效
        lastTickElapsed = 0L
        handler.removeCallbacks(tickerRunnable)
        handler.post(tickerRunnable)
        return START_STICKY
    }

    /** 从粘性广播与 PowerManager 同步最新状态（不依赖广播是否送达） */
    private fun refreshChargingState() {
        val battery = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        if (battery != null) {
            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            isWiredCharging = plugged > 0
            currentLevel = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, currentLevel)
            val tempExtra = FastChargeController.normalizeTemp(
                battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            )
            // 温控节点被 0000 锁死期间，系统 healthd 广播的 EXTRA_TEMPERATURE 会卡死在旧温度或返回 -888，
            // 因此仅在节点未锁死或尚未拿到有效 sysfs 温度时才允许广播温度更新兜底值。
            if (tempExtra > 0 && (appliedBlocked != true || !hasFreshSysfsMetrics)) {
                currentTempTenths = tempExtra
            }
        }

        val powerManager = getSystemService(PowerManager::class.java)
        isScreenOn = powerManager?.isInteractive ?: true

        updateOverheatState(latestMetrics)
    }

    private fun updateOverheatState(metrics: FastChargeController.BatteryMetrics) {
        val cutoffCelsius = currentConfig().thermalCutoffCelsius.coerceIn(
            TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS,
            TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS,
        )
        val effectiveTemp = if (metrics.temperatureTenths > 0) metrics.temperatureTenths else currentTempTenths
        val effectiveMetrics = if (metrics.temperatureTenths > 0) {
            metrics
        } else {
            metrics.copy(temperatureTenths = effectiveTemp)
        }

        val nowOverheated = FastChargeController.isOverheated(
            metrics = effectiveMetrics,
            cutoffCelsius = cutoffCelsius,
            previouslyOverheated = isOverheated,
        )

        if (nowOverheated && !isOverheated) {
            isOverheated = true
            isThermalCutoffActive = true
            forceRestoreRequested.set(true)
            Log.w(
                TAG,
                "触发高温安全熔断（电池/中框=${effectiveTemp / 10.0}℃, 芯片峰值=${metrics.maxChipTempTenths / 10.0}℃, 阈值=${cutoffCelsius}℃），立即恢复官方温控",
            )
        } else if (!nowOverheated && isOverheated) {
            isOverheated = false
            isThermalCutoffActive = false
            Log.i(
                TAG,
                "温度已回落至安全区间（电池/中框=${effectiveTemp / 10.0}℃, 芯片峰值=${metrics.maxChipTempTenths / 10.0}℃），恢复亮屏快充",
            )
        }
    }

    private fun readMetricsAsync() {
        if (isReadingMetrics.compareAndSet(false, true)) {
            postRoot {
                try {
                    val metrics = FastChargeController.readBatteryMetrics(this)
                    latestMetrics = metrics
                    lastKnownMetrics = metrics
                    if (metrics.temperatureTenths > 0) {
                        currentTempTenths = metrics.temperatureTenths
                        hasFreshSysfsMetrics = true
                    }
                    handler.post {
                        updateOverheatState(metrics)
                        if (isWiredCharging) {
                            evaluatePolicy()
                            updateLiveNotification()
                        }
                    }
                } finally {
                    isReadingMetrics.set(false)
                }
            }
        }
    }

    /** 核心策略：亮屏 + 有线充电 + 未过热 → 屏蔽充电温控节点，否则无条件恢复官方温控并定期复核 */
    private fun evaluatePolicy() {
        val desired = isWiredCharging && isScreenOn && !isOverheated
        desiredBlocked = desired

        val now = SystemClock.elapsedRealtime()
        val needVerify = now - lastVerifyElapsed >= VERIFY_INTERVAL_MS
        val forceRestore = !desired && forceRestoreRequested.get()

        if (desired) {
            if (appliedBlocked != true || needVerify) {
                scheduleNodeSync()
            }
        } else {
            // 当处于过热熔断、息屏或拔电状态时：
            // 不仅在 appliedBlocked != false 时恢复，且每 VERIFY_INTERVAL_MS 强校验底层节点权限，
            // 彻底杜绝任何外部任务或状态不同步导致节点残留 0000 锁死！
            if (forceRestore || isOverheated || appliedBlocked != false || needVerify) {
                scheduleNodeSync()
            }
        }
    }

    /**
     * 在单线程 rootExecutor 上串行同步温控节点状态。
     * 不再因前一次检测未完成而丢弃随后的「熔断/拔线/息屏解锁」指令！
     */
    private fun scheduleNodeSync() {
        if (!syncScheduled.compareAndSet(false, true)) return
        postRoot {
            try {
                while (true) {
                    syncScheduled.set(false)
                    val wantBlock = desiredBlocked && isEnabled() && isWiredCharging && isScreenOn && !isOverheated
                    val forceUnlock = forceRestoreRequested.getAndSet(false)

                    if (wantBlock && !forceUnlock) {
                        val blocked = FastChargeController.areTemperatureNodesBlocked()
                        if (blocked == true) {
                            appliedBlocked = true
                            rootSuccessState = true
                        } else {
                            val result = FastChargeController.blockTemperatureNodes()
                            rootSuccessState = result.success
                            appliedBlocked = if (result.success) true else null
                            logResult("亮屏启用充电温控优化", result)
                        }
                    } else {
                        // 过热保护触发或强制解锁时无条件执行 restoreTemperatureNodes（强制 chmod 0644 全部温控节点）
                        val shouldRestore = forceUnlock ||
                            isOverheated ||
                            appliedBlocked != false ||
                            (FastChargeController.isAnyTemperatureNodeBlocked() != false)
                        if (shouldRestore) {
                            val result = FastChargeController.restoreTemperatureNodes()
                            rootSuccessState = result.success
                            appliedBlocked = if (result.success) false else null
                            logResult("恢复官方原生温控", result)
                        } else {
                            appliedBlocked = false
                        }
                    }
                    lastVerifyElapsed = SystemClock.elapsedRealtime()

                    // 若在执行 root 命令期间 desiredBlocked 或 forceRestoreRequested 发生了变化，立即再跑一轮
                    if (!syncScheduled.get() && !forceRestoreRequested.get() && appliedBlocked == (desiredBlocked && isEnabled() && isWiredCharging && isScreenOn && !isOverheated)) {
                        break
                    }
                }
            } finally {
                syncScheduled.set(false)
            }
        }
    }

    /**
     * 拔线/未插电时：恢复官方温控节点、布防充电唤醒触发器，并退出前台服务移除通知，
     * 确保未充电时通知栏 100% 不残留任何通知，下次插电由广播 + JobScheduler 立即唤醒。
     */
    private fun enterStandby() {
        handler.removeCallbacks(tickerRunnable)
        wasChargingLastTick = false
        desiredBlocked = false
        hasFreshSysfsMetrics = false
        isOverheated = false
        isThermalCutoffActive = false
        lastNotifiedLevel = -1
        lastNotifiedWatts = -1.0
        lastNotifyTime = 0L
        lastNotifiedScreenOn = null
        lastNotifiedOverheated = null

        FastChargeScheduler.enable(this)
        postRoot {
            logResult("未充电待命，恢复官方温控", FastChargeController.restoreTemperatureNodes())
            appliedBlocked = false
            rootSuccessState = null
            handler.post {
                if (isEnabled() && FastChargeScheduler.isPluggedIn(this)) {
                    Log.i(TAG, "待命退出取消：已重新连接电源")
                    lastTickElapsed = 0L
                    handler.post(tickerRunnable)
                    return@post
                }
                FastChargeNotifier.cancelLive(this)
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                foregroundStarted = false
                stopSelf()
            }
        }
    }

    /**
     * 用同一个通知 ID 把可见实时通知原地替换成隐藏的前台服务通知。
     * 前台服务仍然保持前台优先级，但通知栏不会出现常驻条目。
     */
    private fun hideServiceNotification() {
        FastChargeNotifier.hideNotification(
            context = this,
            service = if (foregroundStarted) this else null,
        )
    }

    private fun updateLiveNotification() {
        if (!isWiredCharging) {
            hideServiceNotification()
            lastNotifiedLevel = -1
            lastNotifiedWatts = -1.0
            lastNotifyTime = 0L
            lastNotifiedScreenOn = null
            lastNotifiedOverheated = null
            return
        }
        val metrics = latestMetrics
        val effectiveTemp = if (metrics.temperatureTenths > 0) metrics.temperatureTenths else currentTempTenths
        val tempC = String.format("%.1f°C", effectiveTemp / 10.0)

        val now = SystemClock.elapsedRealtime()
        val stateChanged = lastNotifiedScreenOn != isScreenOn ||
            lastNotifiedOverheated != isOverheated ||
            (lastNotifiedWatts <= 0.0 && metrics.powerWatts > 0.0)
        // 节流机制：状态未发生切换、距离上次更新未满 5 秒，且电量未变、功率波动小于 0.8W，跳过重复更新，防止触发 SystemUI 120Hz 布局死循环与发热
        val wattsDiff = Math.abs(metrics.powerWatts - lastNotifiedWatts)
        if (!stateChanged && lastNotifyTime > 0L && now - lastNotifyTime < 5_000L && currentLevel == lastNotifiedLevel && wattsDiff < 0.8) {
            return
        }

        lastNotifiedLevel = currentLevel
        lastNotifiedWatts = metrics.powerWatts
        lastNotifyTime = now
        lastNotifiedScreenOn = isScreenOn
        lastNotifiedOverheated = isOverheated

        val statusText = FastChargeNotifier.formatChargingStatus(
            isScreenOn = isScreenOn,
            isOverheated = isOverheated,
            level = currentLevel,
            metrics = metrics,
        )
        val posted = FastChargeNotifier.postLive(
            context = this,
            status = statusText,
            subText = tempC,
            service = if (foregroundStarted) this else null,
        )
        if (!posted) {
            hideServiceNotification()
        }
    }

    private fun restoreAndStop() {
        handler.removeCallbacks(tickerRunnable)
        desiredBlocked = false
        postRoot {
            logResult("关闭亮屏快充，恢复官方温控", FastChargeController.restoreTemperatureNodes())
            appliedBlocked = false
            rootSuccessState = null
            handler.post {
                // 恢复温控是异步的，这期间用户完全可能又把开关打开了。
                // 若不重新判断就直接 stopSelf()，刚被重新启用的常驻服务会被这次延迟回调杀掉，
                // 表现为「关掉再打开后要等下一个周期任务才生效」。
                if (isEnabled()) {
                    Log.i(TAG, "停止流程被取消：功能已重新开启")
                    return@post
                }
                FastChargeNotifier.cancelLive(this)
                lastNotifiedLevel = -1
                lastNotifiedWatts = -1.0
                lastNotifyTime = 0L
                lastNotifiedScreenOn = null
                lastNotifiedOverheated = null
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                foregroundStarted = false
                stopSelf()
            }
        }
        FastChargeScheduler.disable(this)
    }

    /**
     * 提交 root 任务。
     *
     * 服务停止流程里 executor 已经 shutdown，此时再 execute 会抛
     * RejectedExecutionException 并让前台服务崩溃，这里统一兜住。
     */
    private fun postRoot(block: () -> Unit) {
        runCatching { rootExecutor.execute(block) }
            .onFailure { Log.w(TAG, "后台任务提交失败（服务正在停止）", it) }
    }

    private fun isEnabled(): Boolean = currentConfig().brightScreenFastCharge

    private fun currentConfig(): TuningConfig = TuningPrefs.load(this)

    private fun logResult(operation: String, result: FastChargeController.Result) {
        if (result.success) {
            Log.i(TAG, "$operation 完成")
        } else {
            Log.e(TAG, "$operation 失败: ${result.detail}")
        }
    }

    /**
     * 用户从最近任务划掉本应用时会回调这里。
     *
     * 清单里已声明 `android:stopWithTask="false"`，正常情况下本回调不会被触发、
     * 服务会继续存活；这里只是双保险：万一被某些定制 ROM（如三星的激进清理）
     * 强行结束，也能立刻重排兜底任务，让下次插电仍能被系统拉起。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "任务被移除，重新排布兜底任务")
        FastChargeScheduler.enable(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isServiceAlive = false
        handler.removeCallbacks(tickerRunnable)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(chargingReceiver) }
            receiverRegistered = false
        }
        runCatching {
            rootExecutor.execute {
                FastChargeController.restoreTemperatureNodes()
            }
        }
        rootExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.weitiao.tuning.action.START_FAST_CHARGE"
        const val ACTION_RESTORE_AND_STOP = "com.weitiao.tuning.action.RESTORE_FAST_CHARGE"

        @Volatile
        var isServiceAlive: Boolean = false
            private set

        @Volatile
        var lastKnownMetrics: FastChargeController.BatteryMetrics = FastChargeController.BatteryMetrics(0, 0, 0.0, 0, 0)
            internal set

        @Volatile
        var isThermalCutoffActive: Boolean = false
            internal set

        private const val TAG = "TuningFastCharge"

        private const val TICKER_ACTIVE_MS = 5_000L
        private const val TICKER_IDLE_MS = 30_000L
        private const val MIN_TICK_INTERVAL_MS = 3_000L
        private const val VERIFY_INTERVAL_MS = 15_000L // 每 15s 双向复核一次节点权限，防止被系统重置或异常锁死
    }
}
