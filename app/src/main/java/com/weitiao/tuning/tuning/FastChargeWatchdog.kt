package com.weitiao.tuning.tuning

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.TuningConfig
import com.weitiao.tuning.core.TuningPrefs

/**
 * 亮屏快充兜底链路。
 *
 * 背景：Android 12（targetSdk 31+）起，应用在后台时**禁止**启动前台服务，
 * 而 `ACTION_POWER_CONNECTED` 并不在豁免名单内（只有 BOOT_COMPLETED /
 * LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED 等属于豁免）。
 * 这正是「拔线后服务自杀 → 再次插电时静默失败，只有重启手机才恢复」的根因。
 *
 * 兜底策略分两层：
 * 1. [FastChargeFallback.applyNow]：在广播/任务的短暂存活窗口内**直接**以 root
 *    下发温控策略并推送实时通知，不依赖前台服务是否被允许启动；
 * 2. [FastChargeScheduler]：以「仅在充电时运行」的周期性任务持续兜底，
 *    即使进程被系统回收，插电后仍会被系统重新拉起执行。
 */
object FastChargeScheduler {

    private const val TAG = "TuningFastCharge"
    private const val JOB_ID = 19078
    private const val JOB_ID_KICK = 19079
    private const val JOB_ID_ARM = 19080
    private const val PERIOD_MS = 15 * 60 * 1000L
    private const val ARM_DEBOUNCE_MS = 3_000L
    private const val KEY_ROOT_SUSPENDED = "fast_charge_root_suspended"

    @Volatile
    private var rootSuspendedCache: Boolean? = null

    data class BatterySnapshot(
        val plugged: Boolean,
        val level: Int,
        val status: Int,
        val pluggedType: Int = 0,
    ) {
        val isAcCharging: Boolean
            get() = FastChargeController.isAcPlugged(pluggedType)
    }

    internal enum class ArmTriggerDecision {
        CANCEL_ALL_SUSPENDED,
        CANCEL_IMMEDIATE_PLUGGED,
        SCHEDULE_ARM_UNPLUGGED,
    }

    internal fun decideArmTrigger(
        rootSuspended: Boolean,
        plugged: Boolean,
    ): ArmTriggerDecision = when {
        rootSuspended -> ArmTriggerDecision.CANCEL_ALL_SUSPENDED
        plugged -> ArmTriggerDecision.CANCEL_IMMEDIATE_PLUGGED
        else -> ArmTriggerDecision.SCHEDULE_ARM_UNPLUGGED
    }

    fun isRootSuspended(context: Context): Boolean {
        val cached = rootSuspendedCache
        if (cached != null) return cached
        val persisted = context
            .getSharedPreferences(FastChargeController.RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ROOT_SUSPENDED, false)
        rootSuspendedCache = persisted
        return persisted
    }

    fun setRootSuspended(context: Context, suspended: Boolean) {
        rootSuspendedCache = suspended
        context
            .getSharedPreferences(FastChargeController.RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ROOT_SUSPENDED, suspended)
            .apply()
    }

    internal fun resetStateForTest() {
        rootSuspendedCache = null
    }

    fun enable(context: Context, knownPlugged: Boolean? = null) {
        if (isRootSuspended(context)) {
            disable(context)
            return
        }
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) == null) {
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, FastChargeJobService::class.java))
                    .setRequiresCharging(true)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(info)
            }
            armChargingTrigger(context, knownPlugged)
        }.onFailure { Log.w(TAG, "兜底任务调度失败", it) }
    }

    fun disable(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(JOB_ID)
            scheduler.cancel(JOB_ID_KICK)
            scheduler.cancel(JOB_ID_ARM)
        }.onFailure { Log.w(TAG, "兜底任务取消失败", it) }
    }

    /** 仅取消即时唤醒任务（插电待命 / 100% 满电退出服务时防止 JobScheduler 毫秒级乒乓拉起）。 */
    fun cancelImmediateTriggers(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(JOB_ID_KICK)
            scheduler.cancel(JOB_ID_ARM)
        }.onFailure { Log.w(TAG, "取消即时触发器失败", it) }
    }

    /**
     * 100% 满电保护退场：立即撤掉即时触发器避免与服务退出乒乓互踢，同时保留周期性兜底任务，
     * 并在物理拔线（由 App 进程级动态接收器 / 静态广播 / UI 回前台触发）后重新布防 JOB_ID_ARM。
     */
    fun onFullChargeStandby(context: Context) {
        if (isRootSuspended(context)) {
            disable(context)
            return
        }
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(JOB_ID_KICK)
            scheduler.cancel(JOB_ID_ARM)
            if (scheduler.getPendingJob(JOB_ID) == null) {
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, FastChargeJobService::class.java))
                    .setRequiresCharging(true)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(info)
            }
        }.onFailure { Log.w(TAG, "满电待命任务调整失败", it) }
    }

    internal fun readBatterySnapshot(context: Context): BatterySnapshot {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val pluggedType = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plugged = pluggedType > 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return BatterySnapshot(
            plugged = plugged,
            level = level,
            status = status,
            pluggedType = pluggedType,
        )
    }

    internal fun isPluggedIn(context: Context): Boolean = readBatterySnapshot(context).plugged

    /**
     * 布防「插电触发器」——只在物理未插电且具备 Root 时布防，并加 3s 最小延迟防抖：
     * - 无 Root 挂起态：撤消全部任务；
     * - 已插电态（如 100% 满电退场）：主动取消即时触发器，严禁在充电状态下排入 0ms RequiresCharging 任务引发乒乓死循环；
     * - 未插电态：若尚未布防则调度 `JOB_ID_ARM`，下次接入电源即刻触发。
     */
    fun armChargingTrigger(context: Context, knownPlugged: Boolean? = null) {
        val plugged = knownPlugged ?: isPluggedIn(context)
        when (decideArmTrigger(isRootSuspended(context), plugged)) {
            ArmTriggerDecision.CANCEL_ALL_SUSPENDED -> {
                disable(context)
                return
            }
            ArmTriggerDecision.CANCEL_IMMEDIATE_PLUGGED -> {
                cancelImmediateTriggers(context)
                return
            }
            ArmTriggerDecision.SCHEDULE_ARM_UNPLUGGED -> Unit
        }
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID_ARM) != null) return
            val info = JobInfo.Builder(JOB_ID_ARM, ComponentName(context, FastChargeJobService::class.java))
                .setRequiresCharging(true)
                .setMinimumLatency(ARM_DEBOUNCE_MS)
                .setPersisted(true)
                .build()
            scheduler.schedule(info)
        }.onFailure { Log.w(TAG, "插电触发器布防失败", it) }
    }

    /**
     * 插电后短暂延迟跑一次的唤醒任务（附加 3s 防抖延迟避免极端边界立即重入）。
     */
    fun kick(context: Context) {
        if (isRootSuspended(context)) return
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID_KICK, ComponentName(context, FastChargeJobService::class.java))
                .setRequiresCharging(true)
                .setMinimumLatency(ARM_DEBOUNCE_MS)
                .setOverrideDeadline(ARM_DEBOUNCE_MS + 2_000L)
                .build()
            scheduler.schedule(info)
        }.onFailure { Log.w(TAG, "即时唤醒任务调度失败", it) }
    }
}

/** 不依赖前台服务的一次性策略下发（广播与 JobService 共用） */
object FastChargeFallback {

    private const val TAG = "TuningFastCharge"

    /**
     * 立刻依据当前充电/亮屏状态下发温控策略并刷新通知。
     * 必须在后台线程调用（内部会执行 root shell）。
     */
    fun applyNow(context: Context) {
        val cfg = TuningPrefs.load(context)
        val fastChargeEnabled = cfg.brightScreenFastCharge
        val passThroughEnabled = cfg.passThrough

        if (!fastChargeEnabled && !passThroughEnabled) {
            if (FastChargeController.isNodesMayBeBlocked(context) && RootShell.hasRoot()) {
                FastChargeController.restoreTemperatureNodes(context)
            }
            FastChargeScheduler.disable(context)
            FastChargeNotifier.cancelLive(context)
            return
        }

        val snap = FastChargeScheduler.readBatterySnapshot(context)
        val plugged = snap.plugged
        val level = snap.level

        if (!plugged) {
            BrightScreenFastChargeService.isFullChargeReleasedActive = false
            FastChargeNotifier.cancelLive(context)
            if (FastChargeController.isNodesMayBeBlocked(context) && RootShell.hasRoot()) {
                FastChargeController.restoreTemperatureNodes(context)
            }
            FastChargeScheduler.enable(context, knownPlugged = false)
            return
        }

        val fullReleased = FastChargeController.isFullChargeStopped(
            level = level,
            status = snap.status,
            previouslyReleased = BrightScreenFastChargeService.isFullChargeReleasedActive,
        )
        BrightScreenFastChargeService.isFullChargeReleasedActive = fullReleased

        if (passThroughEnabled && !PassThroughService.isServiceAlive && !FastChargeScheduler.isRootSuspended(context)) {
            if (RootShell.hasRoot()) {
                runCatching {
                    val passIntent = Intent(context, PassThroughService::class.java)
                        .setAction(PassThroughService.ACTION_REFRESH)
                    ContextCompat.startForegroundService(context, passIntent)
                }
            } else {
                FastChargeScheduler.setRootSuspended(context, true)
                FastChargeScheduler.disable(context)
                FastChargeNotifier.cancelLive(context)
                return
            }
        }

        if (!fastChargeEnabled) {
            FastChargeNotifier.cancelLive(context)
            if (FastChargeController.isNodesMayBeBlocked(context) && RootShell.hasRoot()) {
                FastChargeController.restoreTemperatureNodes(context)
            }
            return
        }

        // 非 AC 充电器（USB / 无线充）仅取消快充通知与节点屏蔽，避免误锁 USB 慢充与 JobScheduler 乒乓唤醒
        if (!snap.isAcCharging) {
            BrightScreenFastChargeService.isFullChargeReleasedActive = false
            FastChargeNotifier.cancelLive(context)
            if (FastChargeController.isNodesMayBeBlocked(context) && RootShell.hasRoot()) {
                FastChargeController.restoreTemperatureNodes(context)
            }
            FastChargeScheduler.onFullChargeStandby(context)
            if (BrightScreenFastChargeService.isServiceAlive) {
                runCatching {
                    context.startService(
                        Intent(context, BrightScreenFastChargeService::class.java)
                            .setAction(BrightScreenFastChargeService.ACTION_STOP_STANDBY),
                    )
                }
            }
            return
        }

        // 方案 A：100% 满电保护期间隐藏通知并停止后台轮询，绝不拉起前台服务也不重复刷 su
        if (fullReleased) {
            FastChargeNotifier.cancelLive(context)
            if (FastChargeController.isNodesMayBeBlocked(context) && RootShell.hasRoot()) {
                FastChargeController.restoreTemperatureNodes(context)
            }
            FastChargeScheduler.onFullChargeStandby(context)
            if (BrightScreenFastChargeService.isServiceAlive) {
                runCatching {
                    context.startService(
                        Intent(context, BrightScreenFastChargeService::class.java)
                            .setAction(BrightScreenFastChargeService.ACTION_STOP_STANDBY),
                    )
                }
            }
            return
        }

        // 无 Root 快速熔断：Fail-Fast 停止全部调度与前台服务拉起，避免无效重试与耗电
        if (!RootShell.hasRoot()) {
            FastChargeScheduler.setRootSuspended(context, true)
            FastChargeScheduler.disable(context)
            FastChargeNotifier.cancelLive(context)
            if (BrightScreenFastChargeService.isServiceAlive) {
                runCatching {
                    context.startService(
                        Intent(context, BrightScreenFastChargeService::class.java)
                            .setAction(BrightScreenFastChargeService.ACTION_STOP_STANDBY),
                    )
                }
            }
            Log.w(TAG, "未检测到 Root 权限，亮屏快充自动进入静默挂起状态")
            return
        }
        FastChargeScheduler.setRootSuspended(context, false)
        FastChargeScheduler.enable(context, knownPlugged = true)

        // 全部前置门控通过（已插 AC + 未满电停充 + 具备 Root）：优先拉起亮屏快充前台服务
        val startedOrNotified = runCatching {
            val intent = Intent(context, BrightScreenFastChargeService::class.java)
                .setAction(BrightScreenFastChargeService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
            true
        }.getOrDefault(false)

        if (BrightScreenFastChargeService.isServiceAlive || startedOrNotified) {
            return
        }

        val cutoffCelsius = cfg.thermalCutoffCelsius.coerceIn(
            TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS,
            TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS,
        )
        // 先读取电池温度并执行仅针对电池温度的高温安全熔断判定
        val metrics = FastChargeController.readBatteryMetrics(context, cutoffCelsius = cutoffCelsius)
        BrightScreenFastChargeService.lastKnownMetrics = metrics
        val overheated = FastChargeController.isOverheated(
            metrics = metrics,
            cutoffCelsius = cutoffCelsius,
            previouslyOverheated = BrightScreenFastChargeService.isThermalCutoffActive,
        )
        BrightScreenFastChargeService.isThermalCutoffActive = overheated

        if (overheated) {
            if (FastChargeController.isNodesMayBeBlocked(context)) {
                FastChargeController.restoreTemperatureNodes(context)
            }
            val tempC = String.format("%.1f°C", metrics.temperatureTenths / 10.0)
            val statusText = FastChargeNotifier.formatChargingStatus(
                isScreenOn = true,
                isOverheated = true,
                level = level,
                metrics = metrics,
            )
            FastChargeNotifier.postLive(context, statusText, tempC)
            Log.w(TAG, "兜底路径检测到电池高温 (batt=${metrics.temperatureTenths / 10.0}℃)，已恢复官方温控")
            return
        }

        FastChargeController.enableSamsungFastChargingSettings()
        val result = FastChargeController.blockTemperatureNodes(context)
        val statusText = FastChargeNotifier.formatChargingStatus(
            isScreenOn = true,
            isOverheated = false,
            level = level,
            metrics = metrics,
        )
        val tempC = String.format("%.1f°C", metrics.temperatureTenths / 10.0)
        FastChargeNotifier.postLive(context, statusText, tempC)
        Log.i(TAG, "兜底路径已下发温控策略 success=${result.success}")
    }
}

/** 仅在充电时运行的周期性兜底任务：进程被回收后仍能被系统拉起 */
class FastChargeJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val context = applicationContext
        Thread({
            try {
                FastChargeFallback.applyNow(context)
            } finally {
                runCatching { jobFinished(params, false) }
            }
        }, "Tuning-FastChargeJob").apply { isDaemon = true }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // 当用户拔掉充电器导致 requiresCharging 约束失效，或任务被取消时，系统会立即回调 onStopJob：
        // 即使常驻服务已被系统回收，这里也能在后台线程确保拔线瞬间按需恢复全部温控节点并重布插电触发器！
        val context = applicationContext
        val cfg = TuningPrefs.load(context)
        val anyEnabled = cfg.brightScreenFastCharge || cfg.passThrough
        Thread({
            runCatching {
                val plugged = FastChargeScheduler.isPluggedIn(context)
                if (!anyEnabled) {
                    if (FastChargeController.isNodesMayBeBlocked(context) && RootShell.hasRoot()) {
                        FastChargeController.restoreTemperatureNodes(context)
                    }
                    FastChargeScheduler.disable(context)
                    FastChargeNotifier.cancelLive(context)
                } else if (!plugged) {
                    BrightScreenFastChargeService.isFullChargeReleasedActive = false
                    if (FastChargeController.isNodesMayBeBlocked(context) && RootShell.hasRoot()) {
                        FastChargeController.restoreTemperatureNodes(context)
                    }
                    FastChargeNotifier.cancelLive(context)
                    FastChargeScheduler.armChargingTrigger(context, knownPlugged = false)
                }
            }
        }, "Tuning-FastChargeUnplugRestore").apply { isDaemon = true }.start()
        return anyEnabled && !FastChargeScheduler.isRootSuspended(context)
    }
}
