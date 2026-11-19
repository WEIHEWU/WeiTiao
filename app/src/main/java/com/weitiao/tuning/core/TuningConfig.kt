package com.weitiao.tuning.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.ContextCompat
import com.weitiao.tuning.App
import com.weitiao.tuning.tuning.BrightScreenFastChargeService
import com.weitiao.tuning.tuning.FastChargeNotifier
import com.weitiao.tuning.tuning.FastChargeScheduler
import com.weitiao.tuning.tuning.PassThroughService

data class TuningConfig(
    val stepEnabled: Boolean = false,
    val stepAuto: Boolean = true,
    val stepMultiplier: Int = 3,
    val stepMaximum: Int = 28_000,
    val stepEndHour: Int = 24,
    val antiRecall: Boolean = false,
    val brightScreenFastCharge: Boolean = false,
    val thermalCutoffCelsius: Int = 44,
    val passThrough: Boolean = false,
    val passThroughLimit: Int = 20,
    val stepMode: String = if (stepAuto) MODE_PLAN else MODE_MULTIPLIER,
    val stepTarget: Int = 20_000,
    val stepJitter: Int = 10,
    val stepWindowStart: Int = 420,
    val stepWindowEnd: Int = 1230,
    val stepAfterWindow: String = AFTER_NATURAL,
    val stepGeneration: Long = 0L,
    val stepBackend: String = BACKEND_SENSOR,
) {
    fun normalized(): TuningConfig {
        val start = stepWindowStart.coerceIn(0, 1439)
        return copy(
            stepMode = stepMode.takeIf { it in setOf(MODE_PLAN, MODE_MULTIPLIER, MODE_LEGACY_AUTO) }
                ?: if (stepAuto) MODE_PLAN else MODE_MULTIPLIER,
            stepTarget = stepTarget.coerceIn(1, 1_000_000),
            stepJitter = stepJitter.coerceIn(0, 50),
            stepWindowStart = start,
            stepWindowEnd = stepWindowEnd.coerceIn(start + 1, 1440),
            stepAfterWindow = if (stepAfterWindow == AFTER_FREEZE) AFTER_FREEZE else AFTER_NATURAL,
            stepMultiplier = stepMultiplier.coerceIn(1, 10_000),
            stepMaximum = stepMaximum.coerceIn(1, 1_000_000),
            stepEndHour = stepEndHour.coerceIn(0, 24),
            stepGeneration = stepGeneration.coerceAtLeast(0L),
            stepBackend = stepBackend.takeIf { it in setOf(BACKEND_SENSOR, BACKEND_AUTO, BACKEND_EXACT) }
                ?: BACKEND_SENSOR,
        )
    }

    /** Ignore caller's stale generation; unrelated settings must retain the active daily plan. */
    fun prepareForSave(previous: TuningConfig): TuningConfig {
        val next = normalized()
        val old = previous.normalized()
        val changed = next.stepEnabled != old.stepEnabled || next.stepMode != old.stepMode ||
            next.stepAuto != old.stepAuto || next.stepMultiplier != old.stepMultiplier ||
            next.stepMaximum != old.stepMaximum || next.stepEndHour != old.stepEndHour ||
            next.stepTarget != old.stepTarget || next.stepJitter != old.stepJitter ||
            next.stepWindowStart != old.stepWindowStart || next.stepWindowEnd != old.stepWindowEnd ||
            next.stepAfterWindow != old.stepAfterWindow || next.stepBackend != old.stepBackend
        return next.copy(stepGeneration = if (changed) {
            if (old.stepGeneration == Long.MAX_VALUE) 1L else old.stepGeneration + 1L
        } else old.stepGeneration)
    }

    companion object {
        const val MODE_PLAN = "PLAN"
        const val MODE_MULTIPLIER = "MULTIPLIER"
        const val MODE_LEGACY_AUTO = "LEGACY_AUTO"
        const val AFTER_NATURAL = "NATURAL"
        const val AFTER_FREEZE = "FREEZE"
        const val KEY_STEP_MODE = "tuning_wechat_step_mode"
        const val KEY_STEP_TARGET = "tuning_wechat_step_target"
        const val KEY_STEP_JITTER = "tuning_wechat_step_jitter"
        const val KEY_STEP_WINDOW_START = "tuning_wechat_step_window_start"
        const val KEY_STEP_WINDOW_END = "tuning_wechat_step_window_end"
        const val KEY_STEP_AFTER_WINDOW = "tuning_wechat_step_after_window"
        const val KEY_STEP_GENERATION = "tuning_wechat_step_generation"
        const val BACKEND_SENSOR = "SENSOR"
        const val BACKEND_AUTO = "AUTO"
        const val BACKEND_EXACT = "EXACT"
        const val KEY_STEP_BACKEND = "tuning_wechat_step_backend"

        const val MIN_THERMAL_CUTOFF_CELSIUS = 38
        const val MAX_THERMAL_CUTOFF_CELSIUS = 48
        const val DEFAULT_THERMAL_CUTOFF_CELSIUS = 44

        const val KEY_STEP_ENABLED = "tuning_wechat_step"
        const val KEY_STEP_AUTO = "tuning_wechat_step_auto"
        const val KEY_STEP_MULTIPLIER = "tuning_wechat_step_multiplier"
        const val KEY_STEP_MAX = "tuning_wechat_step_max"
        const val KEY_STEP_END_HOUR = "tuning_wechat_step_end_hour"
        const val KEY_ANTI_RECALL = "tuning_wechat_anti_recall"
        const val KEY_FAST_CHARGE = "tuning_bright_screen_fast_charge"
        const val KEY_THERMAL_CUTOFF = "tuning_thermal_cutoff_celsius"
        const val KEY_PASS_THROUGH = "tuning_pass_through"
        const val KEY_PASS_THROUGH_LIMIT = "tuning_pass_through_limit"

        fun fromPrefs(prefs: SharedPreferences) = TuningConfig(
            stepEnabled = prefs.getBoolean(KEY_STEP_ENABLED, false),
            stepAuto = prefs.getBoolean(KEY_STEP_AUTO, true),
            stepMultiplier = prefs.getInt(KEY_STEP_MULTIPLIER, 3).coerceIn(1, 10_000),
            stepMaximum = prefs.getInt(KEY_STEP_MAX, 28_000).coerceIn(1, 1_000_000),
            stepEndHour = prefs.getInt(KEY_STEP_END_HOUR, 24).coerceIn(0, 24),
            stepMode = prefs.getString(KEY_STEP_MODE, null)
                ?.takeIf { it in setOf(MODE_PLAN, MODE_MULTIPLIER, MODE_LEGACY_AUTO) }
                ?: if (prefs.getBoolean(KEY_STEP_AUTO, true)) MODE_PLAN else MODE_MULTIPLIER,
            stepTarget = prefs.getInt(KEY_STEP_TARGET, 20_000),
            stepJitter = prefs.getInt(KEY_STEP_JITTER, 10),
            stepWindowStart = prefs.getInt(KEY_STEP_WINDOW_START, 420),
            stepWindowEnd = if (prefs.contains(KEY_STEP_WINDOW_END)) {
                prefs.getInt(KEY_STEP_WINDOW_END, 1230)
            } else if (prefs.contains(KEY_STEP_END_HOUR)) {
                prefs.getInt(KEY_STEP_END_HOUR, 24).coerceIn(0, 24).let { if (it == 0) 1440 else it * 60 }
            } else 1230,
            stepAfterWindow = prefs.getString(KEY_STEP_AFTER_WINDOW, AFTER_NATURAL) ?: AFTER_NATURAL,
            stepGeneration = prefs.getLong(KEY_STEP_GENERATION, 0L).coerceAtLeast(0L),
            stepBackend = prefs.getString(KEY_STEP_BACKEND, BACKEND_SENSOR) ?: BACKEND_SENSOR,
            antiRecall = prefs.getBoolean(KEY_ANTI_RECALL, false),
            brightScreenFastCharge = prefs.getBoolean(KEY_FAST_CHARGE, false),
            thermalCutoffCelsius = prefs.getInt(KEY_THERMAL_CUTOFF, DEFAULT_THERMAL_CUTOFF_CELSIUS)
                .coerceIn(MIN_THERMAL_CUTOFF_CELSIUS, MAX_THERMAL_CUTOFF_CELSIUS),
            passThrough = prefs.getBoolean(KEY_PASS_THROUGH, false),
            passThroughLimit = prefs.getInt(KEY_PASS_THROUGH_LIMIT, 20).coerceIn(0, 100),
        ).normalized()

        fun write(editor: SharedPreferences.Editor, value: TuningConfig) {
            val config = value.normalized()
            editor.putString(KEY_STEP_MODE, config.stepMode)
            editor.putInt(KEY_STEP_TARGET, config.stepTarget)
            editor.putInt(KEY_STEP_JITTER, config.stepJitter)
            editor.putInt(KEY_STEP_WINDOW_START, config.stepWindowStart)
            editor.putInt(KEY_STEP_WINDOW_END, config.stepWindowEnd)
            editor.putString(KEY_STEP_AFTER_WINDOW, config.stepAfterWindow)
            editor.putLong(KEY_STEP_GENERATION, config.stepGeneration)
            editor.putString(KEY_STEP_BACKEND, config.stepBackend)
            editor.putBoolean(KEY_STEP_ENABLED, config.stepEnabled)
            editor.putBoolean(KEY_STEP_AUTO, config.stepAuto)
            editor.putInt(KEY_STEP_MULTIPLIER, config.stepMultiplier.coerceIn(1, 10_000))
            editor.putInt(KEY_STEP_MAX, config.stepMaximum.coerceIn(1, 1_000_000))
            editor.putInt(KEY_STEP_END_HOUR, config.stepEndHour.coerceIn(0, 24))
            editor.putBoolean(KEY_ANTI_RECALL, config.antiRecall)
            editor.putBoolean(KEY_FAST_CHARGE, config.brightScreenFastCharge)
            editor.putInt(
                KEY_THERMAL_CUTOFF,
                config.thermalCutoffCelsius.coerceIn(MIN_THERMAL_CUTOFF_CELSIUS, MAX_THERMAL_CUTOFF_CELSIUS),
            )
            editor.putBoolean(KEY_PASS_THROUGH, config.passThrough)
            editor.putInt(KEY_PASS_THROUGH_LIMIT, config.passThroughLimit.coerceIn(0, 100))
        }
    }
}

data class TuningSaveResult(
    val config: TuningConfig,
    val remoteSynced: Boolean,
)

object TuningPrefs {
    private const val APPS_PREFS = "tuning_pass_through_applications"
    private const val KEY_APPS = "tuning_pass_through_apps"
    const val KEY_REMOTE_SYNC_OK = "local_config_remote_synced"

    internal fun verifyRemoteConfigMatches(remote: SharedPreferences, expected: TuningConfig): Boolean {
        if (!remote.contains(TuningConfig.KEY_STEP_GENERATION)) return false
        val actual = TuningConfig.fromPrefs(remote)
        return actual.stepEnabled == expected.stepEnabled &&
            actual.stepMode == expected.stepMode &&
            actual.stepBackend == expected.stepBackend &&
            actual.stepTarget == expected.stepTarget &&
            actual.stepJitter == expected.stepJitter &&
            actual.stepWindowStart == expected.stepWindowStart &&
            actual.stepWindowEnd == expected.stepWindowEnd &&
            actual.stepAfterWindow == expected.stepAfterWindow &&
            actual.stepMaximum == expected.stepMaximum &&
            actual.stepMultiplier == expected.stepMultiplier &&
            actual.stepGeneration == expected.stepGeneration &&
            actual.antiRecall == expected.antiRecall
    }

    internal fun resolveRemoteSyncState(
        local: SharedPreferences,
        remote: SharedPreferences?,
        expected: TuningConfig,
    ): Boolean? {
        if (remote != null) {
            return local.getBoolean(KEY_REMOTE_SYNC_OK, true) && verifyRemoteConfigMatches(remote, expected)
        }
        return if (local.getLong(CscConstants.KEY_LOCAL_SAVED_AT, 0L) > 0L &&
            !local.getBoolean(KEY_REMOTE_SYNC_OK, false)
        ) {
            false
        } else {
            null
        }
    }

    fun remoteSyncState(context: Context, expected: TuningConfig = load(context)): Boolean? =
        resolveRemoteSyncState(localPrefs(context), App.getRemotePrefs(), expected)

    fun isRemoteSynced(context: Context, expected: TuningConfig = load(context)): Boolean {
        val local = localPrefs(context)
        val remote = App.getRemotePrefs()
        if (remote != null) {
            return local.getBoolean(KEY_REMOTE_SYNC_OK, true) && verifyRemoteConfigMatches(remote, expected)
        }
        return if (local.getLong(CscConstants.KEY_LOCAL_SAVED_AT, 0L) > 0L) {
            local.getBoolean(KEY_REMOTE_SYNC_OK, false)
        } else {
            true
        }
    }

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

    fun saveWithStatus(context: Context, value: TuningConfig): TuningSaveResult {
        val previous = load(context)
        val config = value.prepareForSave(previous)
        var remoteSynced = false
        val remote = runCatching { App.getXposedService()?.getRemotePreferences(CscConstants.PREFS_NAME) }.getOrNull()
        if (remote != null) {
            remoteSynced = runCatching {
                val editor = remote.edit()
                TuningConfig.write(editor, config)
                editor.commit() && verifyRemoteConfigMatches(remote, config)
            }.onFailure {
                android.util.Log.e("TuningPrefs", "写入 LSPosed remote preferences 失败", it)
            }.getOrDefault(false)
        }
        localPrefs(context).edit().let { editor ->
            TuningConfig.write(editor, config)
            // 打上"本地被用户显式保存过"的标记。缺了它，App.onServiceBind 会认为
            // 本地是默认值而不敢拿它覆盖 remote，用户改的开关就推不到 hook。
            editor.putLong(CscConstants.KEY_LOCAL_SAVED_AT, System.currentTimeMillis())
            editor.putBoolean(KEY_REMOTE_SYNC_OK, remoteSynced)
            editor.commit()
        }
        syncServices(context, previous, config)
        return TuningSaveResult(config = config, remoteSynced = remoteSynced)
    }

    fun save(context: Context, value: TuningConfig): TuningConfig =
        saveWithStatus(context, value).config

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
     * 应用回到前台时重新武装充电触发器并在已插电时拉起前台服务。
     * 未插电时仅布防 JobScheduler 插电触发器，不在后台启动空转前台服务，确保未充电时通知栏 100% 不出现常驻通知。
     */
    fun resyncServices(context: Context) {
        val config = load(context)
        val plugged = FastChargeScheduler.isPluggedIn(context)
        if (config.brightScreenFastCharge || config.passThrough) {
            FastChargeScheduler.enable(context)
        }
        if (config.brightScreenFastCharge && plugged) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BrightScreenFastChargeService::class.java)
                        .setAction(BrightScreenFastChargeService.ACTION_START),
                )
            }
        }
        if (config.passThrough && plugged) {
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
        val plugged = FastChargeScheduler.isPluggedIn(context)
        val fastIntent = Intent(context, BrightScreenFastChargeService::class.java).setAction(
            if (config.brightScreenFastCharge) BrightScreenFastChargeService.ACTION_START
            else BrightScreenFastChargeService.ACTION_RESTORE_AND_STOP,
        )
        if (config.brightScreenFastCharge) {
            FastChargeScheduler.enable(context)
            if (plugged || BrightScreenFastChargeService.isServiceAlive) {
                runCatching { ContextCompat.startForegroundService(context, fastIntent) }
            }
        } else {
            if (!config.passThrough) {
                FastChargeScheduler.disable(context)
            }
            FastChargeNotifier.cancelLive(context)
            if (previous.brightScreenFastCharge || BrightScreenFastChargeService.isServiceAlive) {
                runCatching { context.startService(fastIntent) }
            }
        }

        val passIntent = Intent(context, PassThroughService::class.java).setAction(
            if (config.passThrough) PassThroughService.ACTION_REFRESH
            else PassThroughService.ACTION_DISABLE,
        )
        if (config.passThrough) {
            FastChargeScheduler.enable(context)
            if (plugged || PassThroughService.isServiceAlive) {
                runCatching { ContextCompat.startForegroundService(context, passIntent) }
            }
        } else {
            if (!config.brightScreenFastCharge) {
                FastChargeScheduler.disable(context)
            }
            if (previous.passThrough || PassThroughService.isServiceAlive) {
                runCatching { context.startService(passIntent) }
            }
        }
    }

    private fun localPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(CscConstants.PREFS_NAME, Context.MODE_PRIVATE)
}
