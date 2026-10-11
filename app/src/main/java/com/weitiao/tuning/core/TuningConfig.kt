package com.weitiao.tuning.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.ContextCompat
import com.weitiao.tuning.App
import com.weitiao.tuning.tuning.BrightScreenFastChargeService
import com.weitiao.tuning.tuning.FastChargeScheduler
import com.weitiao.tuning.tuning.PassThroughService

data class TuningConfig(
    val stepEnabled: Boolean = false,
    val stepAuto: Boolean = true,
    val stepMultiplier: Int = 100,
    val stepMaximum: Int = 80_000,
    val stepEndHour: Int = 23,
    val antiRecall: Boolean = false,
    val brightScreenFastCharge: Boolean = false,
    val passThrough: Boolean = false,
    val passThroughLimit: Int = 20,
) {
    companion object {
        const val KEY_STEP_ENABLED = "tuning_wechat_step"
        const val KEY_STEP_AUTO = "tuning_wechat_step_auto"
        const val KEY_STEP_MULTIPLIER = "tuning_wechat_step_multiplier"
        const val KEY_STEP_MAX = "tuning_wechat_step_max"
        const val KEY_STEP_END_HOUR = "tuning_wechat_step_end_hour"
        const val KEY_ANTI_RECALL = "tuning_wechat_anti_recall"
        const val KEY_FAST_CHARGE = "tuning_bright_screen_fast_charge"
        const val KEY_PASS_THROUGH = "tuning_pass_through"
        const val KEY_PASS_THROUGH_LIMIT = "tuning_pass_through_limit"

        fun fromPrefs(prefs: SharedPreferences) = TuningConfig(
            stepEnabled = prefs.getBoolean(KEY_STEP_ENABLED, false),
            stepAuto = prefs.getBoolean(KEY_STEP_AUTO, true),
            stepMultiplier = prefs.getInt(KEY_STEP_MULTIPLIER, 100).coerceIn(1, 10_000),
            stepMaximum = prefs.getInt(KEY_STEP_MAX, 80_000).coerceIn(1, 1_000_000),
            stepEndHour = prefs.getInt(KEY_STEP_END_HOUR, 23).coerceIn(0, 24),
            antiRecall = prefs.getBoolean(KEY_ANTI_RECALL, false),
            brightScreenFastCharge = prefs.getBoolean(KEY_FAST_CHARGE, false),
            passThrough = prefs.getBoolean(KEY_PASS_THROUGH, false),
            passThroughLimit = prefs.getInt(KEY_PASS_THROUGH_LIMIT, 20).coerceIn(0, 100),
        )

        fun write(editor: SharedPreferences.Editor, config: TuningConfig) {
            editor.putBoolean(KEY_STEP_ENABLED, config.stepEnabled)
            editor.putBoolean(KEY_STEP_AUTO, config.stepAuto)
            editor.putInt(KEY_STEP_MULTIPLIER, config.stepMultiplier.coerceIn(1, 10_000))
            editor.putInt(KEY_STEP_MAX, config.stepMaximum.coerceIn(1, 1_000_000))
            editor.putInt(KEY_STEP_END_HOUR, config.stepEndHour.coerceIn(0, 24))
            editor.putBoolean(KEY_ANTI_RECALL, config.antiRecall)
            editor.putBoolean(KEY_FAST_CHARGE, config.brightScreenFastCharge)
            editor.putBoolean(KEY_PASS_THROUGH, config.passThrough)
            editor.putInt(KEY_PASS_THROUGH_LIMIT, config.passThroughLimit.coerceIn(0, 100))
        }
    }
}

object TuningPrefs {
    private const val APPS_PREFS = "tuning_pass_through_applications"
    private const val KEY_APPS = "tuning_pass_through_apps"

    fun load(context: Context): TuningConfig {
        // local 是用户在 App UI 操作的真相，优先使用。
        // 之前的实现把"XposedService 是否绑定"作为是否回退到 local 的条件，
        // 但 App 自身的后台服务（亮屏快充、旁路供电）根本不依赖 LSPosed hook，
        // 一旦 LSPosed 模块未激活/未就绪，服务就被错误判定为关闭，
        // 表现为"升级后服务不启动、用户必须手动重启模块/手机"。
        val local = localPrefs(context)
        if (local.all.isNotEmpty()) {
            return TuningConfig.fromPrefs(local)
        }
        // local 还没值（首次安装）才回退到 remote 做一次种子同步
        val remote = App.getXposedService()?.getRemotePreferences(CscConstants.PREFS_NAME)
        if (remote != null && remote.all.isNotEmpty()) {
            return TuningConfig.fromPrefs(remote)
        }
        return TuningConfig()
    }

    fun save(context: Context, config: TuningConfig) {
        val previous = TuningConfig.fromPrefs(localPrefs(context))
        App.getXposedService()?.getRemotePreferences(CscConstants.PREFS_NAME)?.edit()?.let {
            TuningConfig.write(it, config)
            it.commit()
        }
        localPrefs(context).edit().let {
            TuningConfig.write(it, config)
            // 打上"本地被用户显式保存过"的标记。缺了它，App.onServiceBind 会认为
            // 本地是默认值而不敢拿它覆盖 remote，用户改的开关就推不到 hook。
            it.putLong(CscConstants.KEY_LOCAL_SAVED_AT, System.currentTimeMillis())
            it.commit()
        }
        syncServices(context, previous, config)
    }

    fun selectedApps(context: Context): Set<String> =
        context.getSharedPreferences(APPS_PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_APPS, emptySet())
            ?.toSet()
            .orEmpty()

    fun saveSelectedApps(context: Context, packages: Set<String>) {
        context.getSharedPreferences(APPS_PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_APPS, packages.toSet()).commit()
    }

    /**
     * 应用回到前台时重新武装常驻服务。
     *
     * 用户「划掉后台」会连同前台服务一起杀掉整个进程，之后插电只能靠静态广播兜底。
     * 这里在 Activity 可见时补一次拉起，让「打开页面就恢复」这件事不再依赖手动关开开关。
     */
    fun resyncServices(context: Context) {
        val config = load(context)
        if (config.brightScreenFastCharge) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BrightScreenFastChargeService::class.java)
                        .setAction(BrightScreenFastChargeService.ACTION_START),
                )
            }
        }
        if (config.passThrough) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PassThroughService::class.java)
                        .setAction(PassThroughService.ACTION_REFRESH),
                )
            }
        }
    }

    /**
     * 是否仍受电池优化限制。
     *
     * 三星的「深度休眠/自适应电池」比原生 Doze 激进得多：被判定为休眠的应用，
     * **即使在充电中**其 JobScheduler 任务也会被完全阻断，前台服务也更易被回收。
     * 这会让「插电立即生效」的整条兜底链路失效，而且同样表现为静默无反应。
     */
    fun isBatteryOptimizationRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val powerManager = context.getSystemService(android.os.PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) != true
    }

    /**
     * 跳转系统「电池优化」设置页。
     *
     * 优先用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 直接弹系统授权框；
     * 部分 ROM（尤其三星 One UI）会拦截该 Intent 或静默失败，此时退回到
     * 本应用的电池用量详情页，用户手动选择「不受限制」同样有效。
     */
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val launched = runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (launched) return
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun syncServices(context: Context, previous: TuningConfig, config: TuningConfig) {
        val fastIntent = Intent(context, BrightScreenFastChargeService::class.java).setAction(
            if (config.brightScreenFastCharge) BrightScreenFastChargeService.ACTION_START
            else BrightScreenFastChargeService.ACTION_RESTORE_AND_STOP,
        )
        if (config.brightScreenFastCharge) {
            // 兜底任务保证进程被回收后仍能恢复；服务自身也会在 onCreate 中注册
            FastChargeScheduler.enable(context)
            ContextCompat.startForegroundService(context, fastIntent)
        } else {
            FastChargeScheduler.disable(context)
            context.startService(fastIntent)
        }

        val passIntent = Intent(context, PassThroughService::class.java).setAction(
            if (config.passThrough) PassThroughService.ACTION_REFRESH
            else PassThroughService.ACTION_DISABLE,
        )
        if (config.passThrough) {
            ContextCompat.startForegroundService(context, passIntent)
        } else {
            context.startService(passIntent)
        }
    }

    private fun localPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(CscConstants.PREFS_NAME, Context.MODE_PRIVATE)
}
