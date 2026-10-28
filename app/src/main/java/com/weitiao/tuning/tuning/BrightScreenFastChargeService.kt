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
    private val isApplying = AtomicBoolean(false)
    private val isReadingMetrics = AtomicBoolean(false)

    // ---- 充电/屏幕/温度状态 ----
    @Volatile private var isWiredCharging = false
    @Volatile private var wasChargingLastTick = false
    @Volatile private var isScreenOn = true
    @Volatile private var isOverheated = false
    @Volatile private var currentLevel = 0
    @Volatile private var currentTempTenths = 320 // 默认 32.0°C 初始安全值

    // ---- 温控节点状态机 ----
    /** 期望状态：true=屏蔽温控节点（快充），false=恢复官方温控 */
    @Volatile private var desiredBlocked = false
    /** 已确认落地的状态，null=未知；与期望不一致时触发下发 */
    @Volatile private var appliedBlocked: Boolean? = null
    @Volatile private var rootSuccessState: Boolean? = null
    @Volatile private var lastVerifyElapsed = 0L
    @Volatile private var lastTickElapsed = 0L

    @Volatile private var latestMetrics = FastChargeController.BatteryMetrics(0, 0, 0.0, 320)
    /** 常驻通知当前文案，用于去重，避免每个 tick 都刷新一次通知 */
    private var lastServiceNotificationText: String? = null
    private var lastNotifiedLevel = -1
    private var lastNotifiedWatts = -1.0
    private var lastNotifyTime = 0L

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
                evaluatePolicy()
                if (isScreenOn) {
                    readMetricsAsync()
                    updateLiveNotification()
                    handler.postDelayed(this, TICKER_ACTIVE_MS)
                } else {
                    // 息屏充电时已交还官方温控，暂停 5s RootShell 采样与通知刷新，亮屏广播会立即恢复
                    refreshServiceNotification()
                }
            } else {
                enterStandby()
                // 未插电待命态完全由动态广播唤醒，不再周期性轮询，彻底消除待机耗电
            }
        }
    }

    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
                    if (plugged != isWiredCharging) {
                        handler.removeCallbacks(tickerRunnable)
                        handler.post(tickerRunnable)
                    }
                }
                // 插电/亮屏/拔电事件响应
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(tickerRunnable)
                    handler.post(tickerRunnable)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    handler.removeCallbacks(tickerRunnable)
                    handler.post(tickerRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    handler.removeCallbacks(tickerRunnable)
                    handler.post(tickerRunnable)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        FastChargeNotifier.ensureChannels(this)

        // 启动即进入前台，保证进程优先级，避免被回收
        runCatching {
            startForeground(SERVICE_NOTIFICATION_ID, FastChargeNotifier.buildServiceNotification(this, SERVICE_IDLE_TEXT))
            foregroundStarted = true
        }.onFailure {
            Log.e(TAG, "前台服务启动失败（可能受后台启动限制）", it)
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
        if (intent?.action == ACTION_RESTORE_AND_STOP) {
            restoreAndStop()
            return START_NOT_STICKY
        }
        if (!isEnabled()) {
            restoreAndStop()
            return START_NOT_STICKY
        }

        runCatching {
            if (!foregroundStarted) {
                startForeground(SERVICE_NOTIFICATION_ID, FastChargeNotifier.buildServiceNotification(this, SERVICE_IDLE_TEXT))
                foregroundStarted = true
            }
        }
        FastChargeScheduler.enable(this)
        postRoot {
            logResult("启用三星快充系统设置", FastChargeController.enableSamsungFastChargingSettings())
        }

        // 立即跑一轮，保证插电/开关切换后马上生效
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
            val tempExtra = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            if (tempExtra > 0) {
                currentTempTenths = tempExtra
            }
        }

        val powerManager = getSystemService(PowerManager::class.java)
        isScreenOn = powerManager?.isInteractive ?: true

        // 高温熔断保护：电池自身温度达到用户设定阈值（默认 44.0℃）暂停屏蔽，降温 3.5℃ 后恢复
        val cutoffCelsius = currentConfig().thermalCutoffCelsius.coerceIn(38, 48)
        val overheatTenths = cutoffCelsius * 10
        val coolDownTenths = overheatTenths - 35
        val effectiveTemp = if (latestMetrics.temperatureTenths > 0) latestMetrics.temperatureTenths else currentTempTenths
        if (effectiveTemp >= overheatTenths && !isOverheated) {
            isOverheated = true
            isThermalCutoffActive = true
            Log.w(TAG, "电池温度 ${effectiveTemp / 10.0}℃ 触发安全熔断（阈值 ${cutoffCelsius}℃），交还官方温控")
        } else if (effectiveTemp <= coolDownTenths && isOverheated) {
            isOverheated = false
            isThermalCutoffActive = false
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
                    }
                } finally {
                    isReadingMetrics.set(false)
                }
            }
        }
    }

    /** 核心策略：亮屏 + 有线充电 + 未过热 → 屏蔽温控节点，否则恢复官方温控 */
    private fun evaluatePolicy() {
        val desired = isWiredCharging && isScreenOn && !isOverheated
        desiredBlocked = desired

        val now = SystemClock.elapsedRealtime()
        if (desired) {
            // 首次下发，或周期性复核（系统温控守护可能把节点权限改回去）
            val needVerify = now - lastVerifyElapsed >= VERIFY_INTERVAL_MS
            if (appliedBlocked != true || needVerify) {
                applyBlocked(true)
            }
        } else if (appliedBlocked != false) {
            applyBlocked(false)
        }
    }

    private fun applyBlocked(block: Boolean) {
        if (!isApplying.compareAndSet(false, true)) return
        postRoot {
            try {
                if (block) {
                    // 复核：root 拥有 CAP_DAC_OVERRIDE，必须用权限位判断而非可读性
                    val blocked = FastChargeController.areTemperatureNodesBlocked()
                    if (blocked == true) {
                        appliedBlocked = true
                        rootSuccessState = true
                    } else {
                        val result = FastChargeController.blockTemperatureNodes()
                        rootSuccessState = result.success
                        appliedBlocked = if (result.success) true else null
                        logResult("亮屏启用温控优化", result)
                    }
                } else {
                    val result = FastChargeController.restoreTemperatureNodes()
                    rootSuccessState = result.success
                    appliedBlocked = if (result.success) false else null
                    logResult("恢复官方原生温控", result)
                }
                lastVerifyElapsed = SystemClock.elapsedRealtime()
            } finally {
                isApplying.set(false)
            }
        }
    }

    /** 拔线/息屏时的待命态：恢复官方温控、隐藏实时通知，但服务保持存活 */
    private fun enterStandby() {
        val justUnplugged = wasChargingLastTick
        wasChargingLastTick = false
        desiredBlocked = false
        if (appliedBlocked != false) {
            applyBlocked(false)
        }
        FastChargeNotifier.cancelLive(this)
        lastNotifiedLevel = -1
        lastNotifiedWatts = -1.0
        lastNotifyTime = 0L
        // 仅在拔线瞬间重新布防「插电触发器」，避免待命态每次轮询都重复操作 JobScheduler
        if (justUnplugged) {
            FastChargeScheduler.armChargingTrigger(this)
        }
        refreshServiceNotification()
    }

    private fun refreshServiceNotification() {
        if (!foregroundStarted) return
        // 待命态每 10 秒一次 tick，文案没变就别重复 startForeground
        val text = if (isWiredCharging) SERVICE_ACTIVE_TEXT else SERVICE_IDLE_TEXT
        if (text == lastServiceNotificationText) return
        lastServiceNotificationText = text
        runCatching {
            startForeground(SERVICE_NOTIFICATION_ID, FastChargeNotifier.buildServiceNotification(this, text))
        }
    }

    private fun updateLiveNotification() {
        if (!isWiredCharging) {
            FastChargeNotifier.cancelLive(this)
            lastNotifiedLevel = -1
            lastNotifiedWatts = -1.0
            lastNotifyTime = 0L
            return
        }
        val metrics = latestMetrics
        val effectiveTemp = if (metrics.temperatureTenths > 0) metrics.temperatureTenths else currentTempTenths
        val tempC = String.format("%.1f°C", effectiveTemp / 10.0)

        val now = SystemClock.elapsedRealtime()
        // 节流机制：若距离上次更新未满 5 秒，且电量未变、功率波动小于 0.8W，跳过重复更新，防止触发 SystemUI 120Hz 布局死循环与发热
        val wattsDiff = Math.abs(metrics.powerWatts - lastNotifiedWatts)
        if (lastNotifyTime > 0L && now - lastNotifyTime < 5_000L && currentLevel == lastNotifiedLevel && wattsDiff < 0.8) {
            refreshServiceNotification()
            return
        }

        lastNotifiedLevel = currentLevel
        lastNotifiedWatts = metrics.powerWatts
        lastNotifyTime = now

        val statusText = if (metrics.currentMa > 0 && metrics.voltageMv > 0) {
            String.format("电量:%d%% 电流:%dmA 电压:%.2fV 功率:%.1fW", currentLevel, metrics.currentMa, metrics.voltageMv / 1000.0, metrics.powerWatts)
        } else if (metrics.voltageMv > 0) {
            String.format("电量:%d%% 电流:读取中 电压:%.2fV", currentLevel, metrics.voltageMv / 1000.0)
        } else {
            String.format("电量:%d%%", currentLevel)
        }
        FastChargeNotifier.postLive(this, statusText, tempC)
        refreshServiceNotification()
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
        handler.removeCallbacks(tickerRunnable)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(chargingReceiver) }
            receiverRegistered = false
        }
        rootExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.weitiao.tuning.action.START_FAST_CHARGE"
        const val ACTION_RESTORE_AND_STOP = "com.weitiao.tuning.action.RESTORE_FAST_CHARGE"

        @Volatile
        var lastKnownMetrics: FastChargeController.BatteryMetrics = FastChargeController.BatteryMetrics(0, 0, 0.0, 0)
            private set

        @Volatile
        var isThermalCutoffActive: Boolean = false
            private set

        private const val TAG = "TuningFastCharge"

        /** 承载前台服务的静默通知 ID（不要与实时通知 ID 冲突） */
        private const val SERVICE_NOTIFICATION_ID = 19076

        private const val SERVICE_IDLE_TEXT = "待命中 · 接入充电器后自动生效"
        private const val SERVICE_ACTIVE_TEXT = "充电监控中 · 亮屏自动解除温控限流"

        private const val TICKER_ACTIVE_MS = 5_000L
        private const val TICKER_IDLE_MS = 30_000L
        private const val MIN_TICK_INTERVAL_MS = 3_000L
        private const val VERIFY_INTERVAL_MS = 30_000L // 每 30s 复核一次节点权限，防止被系统重置
    }
}
