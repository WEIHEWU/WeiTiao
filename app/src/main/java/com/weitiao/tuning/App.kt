package com.weitiao.tuning

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.weitiao.tuning.core.CscConstants
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 UI 进程 Application：
 * 绑定 LSPosed 的 XposedService，用于读写框架专用 remote prefs 存储
 * （与 hook 侧 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 读写的是同一份，避免 SELinux 的 app_data_file 隔离导致跨进程读不到）。
 *
 * ## 配置的三层真相
 *
 * 1. **本地 XML**（本进程 `shared_prefs/csc_config.xml`）：用户在 UI 上操作的直接落点。
 * 2. **LSPosed remote**（`/data/adb/lspd/config/modules_config.db`）：hook 进程唯一
 *    能读到的配置源。
 * 3. **XposedService 是否绑定**：决定"能不能"把 1 推进 2。
 *
 * 1 -> 2 的同步方向由 [CscConstants.KEY_LOCAL_SAVED_AT] 决定，见 [syncWithRemote]。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    @Volatile
    private var mService: XposedService? = null

    companion object {
        private const val TAG = "CscChanger"

        @Volatile
        private var service: XposedService? = null

        /** 供 CscPrefs 等静态工具访问已绑定的 service（可能为 null，若框架未连接） */
        @JvmStatic
        fun getXposedService(): XposedService? = service

        /**
         * 供 UI 读取 remote prefs（主要是 hook 心跳），用于判断模块是否真的在
         * 作用域进程里生效。未绑定时返回 null。
         */
        @JvmStatic
        fun getRemotePrefs(): SharedPreferences? =
            runCatching { service?.getRemotePreferences(CscConstants.PREFS_NAME) }.getOrNull()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "App.onCreate: registering XposedService listener")
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Log.i(TAG, "App.onServiceBind: XposedService connected, apiVersion=${service.apiVersion} framework=${service.frameworkName}")
        synchronized(this) {
            mService = service
            Companion.service = service
        }
        runCatching { syncWithRemote(service) }
            .onFailure { Log.e(TAG, "syncWithRemote failed", it) }
    }

    override fun onServiceDied(service: XposedService) {
        Log.w(TAG, "App.onServiceDied: XposedService died")
        synchronized(this) {
            if (mService === service) {
                mService = null
                Companion.service = null
            }
        }
    }

    /**
     * 本地与 remote 的方向性同步。
     *
     * 方向由本地是否"被用户在 UI 上保存过"决定，判据是 [CscConstants.KEY_LOCAL_SAVED_AT]：
     *
     * - **本地无标记**：这份本地配置是默认值（全新安装 / 备份恢复 / 数据被清），
     *   不代表用户意图。若拿它覆盖 remote，会把 hook 正在用的配置静默打回默认
     *   ——表现为"升级或重装后，所有开关莫名其妙全关了，但 UI 看起来一切正常"。
     *   正确做法是**反向**把 remote 灌回本地，让 UI 显示 hook 真正在用的值。
     * - **本地有标记**：本地即用户意图，正向覆盖 remote（原有行为）。
     *
     * 两个方向都做落盘校验并把结果写进日志，同步一旦不一致要能在 logcat 里看见，
     * 而不是等用户发现"功能又没生效"。
     */
    private fun syncWithRemote(service: XposedService) {
        val local = getSharedPreferences(CscConstants.PREFS_NAME, MODE_PRIVATE)
        val remote = service.getRemotePreferences(CscConstants.PREFS_NAME)
        val localSavedAt = local.getLong(CscConstants.KEY_LOCAL_SAVED_AT, 0L)

        if (localSavedAt == 0L) {
            if (remote.all.isEmpty()) {
                Log.i(TAG, "同步跳过：本地无保存标记且 remote 为空（首次安装）")
                return
            }
            val editor = local.edit()
            var written = 0
            remote.all.forEach { (key, value) ->
                if (key == CscConstants.KEY_LOCAL_SAVED_AT) return@forEach
                if (putValue(editor, key, value)) written++
            }
            // 回填后打上标记：从此本地不再是"默认值"，可以正常正向覆盖 remote 了
            editor.putLong(CscConstants.KEY_LOCAL_SAVED_AT, System.currentTimeMillis())
            val committed = editor.commit()
            Log.i(TAG, "同步方向 remote -> local（本地无保存标记，以 hook 现值为准）：写入 $written 项 commit=$committed")
            return
        }

        val editor = remote.edit()
        var written = 0
        local.all.forEach { (key, value) ->
            // 标记只属于本地进程，不能污染 remote（否则下次启动的方向判断会失真）
            if (key == CscConstants.KEY_LOCAL_SAVED_AT) return@forEach
            if (putValue(editor, key, value)) written++
        }
        val committed = editor.commit()
        Log.i(TAG, "同步方向 local -> remote（本地保存于 $localSavedAt）：写入 $written 项 commit=$committed")
        verifySync(local, remote)
    }

    /**
     * 落盘校验：同步完成后逐键比对，不一致立刻写日志。
     *
     * 静默的同步失败是这类模块最难排查的故障——UI 显示正常、hook 读到的却是旧值，
     * 用户只能看到"功能没生效"这一个现象。这里把差异摊到 logcat 里。
     */
    private fun verifySync(local: SharedPreferences, remote: SharedPreferences) {
        val mismatches = mutableListOf<String>()
        local.all.forEach { (key, value) ->
            if (key == CscConstants.KEY_LOCAL_SAVED_AT) return@forEach
            if (!remote.contains(key)) {
                mismatches += "$key(remote 缺失)"
                return@forEach
            }
            val remoteValue = remote.all[key]
            if (remoteValue != null && remoteValue != value) {
                mismatches += "$key(local=$value remote=$remoteValue)"
            }
        }
        if (mismatches.isEmpty()) {
            Log.i(TAG, "同步校验通过（local -> remote）")
        } else {
            Log.w(TAG, "同步校验不一致（local -> remote）：${mismatches.joinToString("; ")}")
        }
    }

    /** 把从 prefs 里读到的任意类型值写进 editor，返回是否识别了该类型。 */
    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?): Boolean = when (value) {
        is Boolean -> {
            editor.putBoolean(key, value)
            true
        }
        is Int -> {
            editor.putInt(key, value)
            true
        }
        is Long -> {
            editor.putLong(key, value)
            true
        }
        is Float -> {
            editor.putFloat(key, value)
            true
        }
        is String -> {
            editor.putString(key, value)
            true
        }
        is Set<*> -> {
            @Suppress("UNCHECKED_CAST")
            editor.putStringSet(key, value as Set<String>)
            true
        }
        else -> {
            Log.w(TAG, "同步跳过不支持的类型：key=$key type=${value?.javaClass?.name}")
            false
        }
    }
}
