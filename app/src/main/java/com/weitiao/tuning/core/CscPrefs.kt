package com.weitiao.tuning.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.weitiao.tuning.App

/**
 * 配置读写（libxposed 102 API 方案）：
 *
 * 存储走 **LSPosed 框架的 remote prefs**（`XposedService.getRemotePreferences`），
 * 与 hook 侧 `XposedInterface.getRemotePreferences` 读取的是同一份存储，
 * 由框架服务以 root 转发，天然跨进程、无 SELinux app_data_file 隔离问题。
 *
 * 注意：不要用 `Context.getSharedPreferences` 写配置——那会落到模块私有目录
 * （shared_prefs/），宿主进程（Galaxy Store 等）因 SELinux 隔离读不到。
 *
 * UI 侧读取逻辑：
 * 1. 优先走 XposedService remote prefs（若框架已连接）
 * 2. fallback 普通 SharedPreferences（框架未连接时 UI 仍可独立读写）
 */
object CscPrefs {

    /**
     * 写入配置。优先写入框架 remote prefs（hook 侧可读到），
     * 框架未连接时 fallback 到本地普通 prefs。
     */
    fun save(context: Context, config: CscConfig) {
        val remote = App.getXposedService()?.getRemotePreferences(CscConstants.PREFS_NAME)
        if (remote != null) {
            runCatching {
                val editor = remote.edit()
                CscConfig.toPrefs(editor, config)
                editor.apply()
            }.onFailure {
                Log.e("CscPrefs", "写入 LSPosed remote preferences 失败", it)
            }
            // 双写本地，保证 UI 进程自己也能读到（框架掉线/重启后仍有值）
            val localEditor = localPrefs(context).edit()
            CscConfig.toPrefs(localEditor, config)
            // 同步 commit：标记决定下一次启动的同步方向，不能靠异步 apply 赌落盘时机
            localEditor.putLong(CscConstants.KEY_LOCAL_SAVED_AT, System.currentTimeMillis())
            localEditor.commit()
        } else {
            val editor = localPrefs(context).edit()
            CscConfig.toPrefs(editor, config)
            editor.putLong(CscConstants.KEY_LOCAL_SAVED_AT, System.currentTimeMillis())
            editor.commit()
        }
    }

    /** UI 侧读取：优先框架 remote prefs，fallback 本地 */
    fun load(context: Context): CscConfig {
        val remote = App.getXposedService()?.getRemotePreferences(CscConstants.PREFS_NAME)
        if (remote != null && !remote.all.isEmpty()) {
            return CscConfig.fromPrefs(remote)
        }
        return CscConfig.fromPrefs(localPrefs(context))
    }

    /** UI 侧重置（仅清除 CSC/机型相关配置，保留微调相关独立配置） */
    fun clear(context: Context) {
        val keysToRemove = listOf(
            "enabled", "sales_code", "country_code", "spoof_sales_code",
            "spoof_country_code", "spoof_ril_sales_code", "spoof_omc_codes",
            "spoof_sem_csc_feature", "spoof_sim_info", "sim_mcc", "sim_mnc",
            "sim_country_iso", "sim_numeric", "sim_operator_name", "soul_market_unlocker",
            "spoof_device", "device_name",
            "device_target_packages", "device_target_filter_enabled",
            "custom_props", "custom_csc_features",
        )
        App.getXposedService()?.getRemotePreferences(CscConstants.PREFS_NAME)?.edit()?.let { editor ->
            keysToRemove.forEach { editor.remove(it) }
            editor.apply()
        }
        localPrefs(context).edit().let { editor ->
            keysToRemove.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    /** 本地普通 prefs（仅 UI 进程自用/fallback） */
    private fun localPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(CscConstants.PREFS_NAME, Context.MODE_PRIVATE)
}
