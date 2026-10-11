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
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
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

    fun enable(context: Context) {
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
            armChargingTrigger(context)
        }.onFailure { Log.w(TAG, "兜底任务调度失败", it) }
    }

    fun disable(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(JOB_ID)
            scheduler.cancel(JOB_ID_ARM)
        }.onFailure { Log.w(TAG, "兜底任务取消失败", it) }
    }

    private fun isPluggedIn(context: Context): Boolean {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
    }

    /**
     * 布防「插电触发器」——整套修复里最关键的一环。
     *
     * `ACTION_POWER_CONNECTED` 不在 Android 8+ 隐式广播豁免名单里（targetSdk≥26
     * 时静态注册收不到），所以进程一旦被杀就再也收不到插电事件，只能等 15 分钟的
     * 周期任务。这里改用一个**只约束「正在充电」、且不设任何时间窗口**的一次性任务：
     * 未充电时它一直挂起，约束一旦满足（即插上电源）系统会**立刻**执行它，
     * 于是插电后几秒内就能下发策略并弹出通知，等价于拿到了我们自己的插电广播。
     *
     * 只在未充电时布防：充电中布防会立即触发，而那条路径已由常驻服务覆盖，无需重复。
     */
    fun armChargingTrigger(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            // 已布防则直接跳过：待命态下每 5 秒会被调用一次，不能每次都走一遍调度
            if (scheduler.getPendingJob(JOB_ID_ARM) != null) return
            if (isPluggedIn(context)) return
            val info = JobInfo.Builder(JOB_ID_ARM, ComponentName(context, FastChargeJobService::class.java))
                .setRequiresCharging(true)
                .setPersisted(true)
                .build()
            scheduler.schedule(info)
        }.onFailure { Log.w(TAG, "插电触发器布防失败", it) }
    }

    /**
     * 插电后立刻跑一次的唤醒任务。
     *
     * 前台服务被后台限制拒绝时，只靠 15 分钟的周期任务要等太久。
     * 这里排一个「要求正在充电 + 立即执行」的一次性任务：
     * JobService 执行期间应用处于活动窗口，比广播更容易成功拉起常驻前台服务，
     * 即便仍被拒绝，也会走一遍 root 策略下发与实时通知刷新。
     */
    fun kick(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID_KICK, ComponentName(context, FastChargeJobService::class.java))
                .setRequiresCharging(true)
                .setMinimumLatency(0L)
                .setOverrideDeadline(0L)
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
        if (!cfg.brightScreenFastCharge) {
            FastChargeScheduler.disable(context)
            FastChargeNotifier.cancelLive(context)
            return
        }
        FastChargeScheduler.enable(context)

        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0

        if (plugged <= 0) {
            FastChargeController.restoreTemperatureNodes()
            FastChargeNotifier.cancelLive(context)
            return
        }

        val powerManager = context.getSystemService(PowerManager::class.java)
        val screenOn = powerManager?.isInteractive ?: true

        if (screenOn) {
            FastChargeController.enableSamsungFastChargingSettings()
            val result = FastChargeController.blockTemperatureNodes()
            val metrics = FastChargeController.readBatteryMetrics(context)
            val statusText = if (metrics.currentMa > 0 && metrics.voltageMv > 0) {
                String.format("电量:%d%% 电流:%dmA 电压:%.2fV 功率:%.1fW", level, metrics.currentMa, metrics.voltageMv / 1000.0, metrics.powerWatts)
            } else {
                "电量:$level%"
            }
            val tempC = String.format("%.1f°C", metrics.temperatureTenths / 10.0)
            FastChargeNotifier.postLive(context, statusText, tempC)
            Log.i(TAG, "兜底路径已下发温控策略 success=${result.success}")
        } else {
            FastChargeController.restoreTemperatureNodes()
            FastChargeNotifier.postLive(context, "🌙 息屏安全充 · 电量:$level%")
        }

        // 若此刻处于允许启动前台服务的时机（例如开机广播），顺带拉起常驻服务
        runCatching {
            val intent = Intent(context, BrightScreenFastChargeService::class.java)
                .setAction(BrightScreenFastChargeService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun formatTemp(tenths: Int): String = String.format("%.1f", tenths / 10.0)

    private fun formatPower(metrics: FastChargeController.BatteryMetrics): String =
        if (metrics.currentMa > 0 && metrics.voltageMv > 0) {
            String.format(
                "%.1fW (%.2fV / %dmA)",
                metrics.powerWatts,
                metrics.voltageMv / 1000.0,
                metrics.currentMa,
            )
        } else {
            "读取中…"
        }
}

/** 仅在充电时运行的周期性兜底任务：进程被回收后仍能被系统拉起 */
class FastChargeJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val context = applicationContext
        Thread {
            try {
                FastChargeFallback.applyNow(context)
            } finally {
                runCatching { jobFinished(params, false) }
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
