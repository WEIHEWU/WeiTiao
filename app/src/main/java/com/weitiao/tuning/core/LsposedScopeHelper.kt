package com.weitiao.tuning.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * LSPosed 作用域查询与官方 API 申请助手。
 *
 * 仅通过 libxposed 官方服务接口查询和申请作用域；
 * 不再通过 Root 直接覆写 `/data/adb/lspd/config/modules_config.db` 或删除 WAL/SHM，
 * 避免与运行中的 `lspd` 并发写锁冲突导致配置损坏。
 */
object LsposedScopeHelper {

    private const val TAG = "LsposedScopeHelper"
    private const val MODULE_PKG = "com.weitiao.tuning"

    private val SYSTEM_SKIP_RESTART_PACKAGES: Set<String> = setOf(
        MODULE_PKG,
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.samsung.android.app.settings",
        "com.samsung.android.app.telephonyui",
        "com.tencent.mm",
    )

    /**
     * 解析当前需要重启以使新机型配置立即生效的应用列表：
     * 优先使用显式勾选的 [explicitTargets]，若为空（即“全部应用”模式）则自动获取当前 LSPosed 已勾选的作用域应用。
     */
    fun resolveRestartableTargetPackages(explicitTargets: Set<String>): Set<String> {
        val raw: Set<String> = if (explicitTargets.isNotEmpty()) {
            explicitTargets
        } else {
            val serviceScope: Set<String> = runCatching {
                com.weitiao.tuning.App.getXposedService()?.scope?.toSet() ?: emptySet<String>()
            }.getOrDefault(emptySet<String>())
            serviceScope + HookLiveness.recentHookedPackages().orEmpty()
        }
        return raw.filterTo(LinkedHashSet<String>()) { pkg ->
            pkg.isNotBlank() &&
                pkg !in SYSTEM_SKIP_RESTART_PACKAGES &&
                !pkg.startsWith("com.android.server")
        }
    }

    /**
     * 模块核心推荐作用域清单（确保不被覆盖或遗漏）
     */
    val CORE_SCOPES: Set<String> = setOf(
        "com.sec.android.app.samsungapps",
        "com.samsung.android.themestore",
        "com.samsung.android.themecenter",
        "com.osp.app.signin",
        "com.samsung.android.samsungaccount",
        "com.samsung.android.authfw",
        "com.tencent.mm",
    )

    fun isServiceConnected(): Boolean = com.weitiao.tuning.App.getXposedService() != null

    fun isPackageInScope(packageName: String): Boolean? =
        runCatching { com.weitiao.tuning.App.getXposedService()?.scope?.contains(packageName) }.getOrNull()

    /**
     * 通过 libxposed API 102 原生通道批量请求将应用加入模块内存作用域（无需重启系统）
     */
    fun requestScopesViaService(packages: Collection<String>, onResult: (Boolean) -> Unit = {}) {
        val service = com.weitiao.tuning.App.getXposedService() ?: run {
            onResult(false)
            return
        }
        runCatching {
            val currentScope = runCatching { service.scope.toSet() }.getOrDefault(emptySet())
            val missing = packages
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != MODULE_PKG && it !in currentScope }
                .distinct()
            if (missing.isEmpty()) {
                onResult(true)
                return
            }
            service.requestScope(missing, object : io.github.libxposed.service.XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    onResult(approved.containsAll(missing))
                }

                override fun onScopeRequestFailed(message: String) {
                    Log.w(TAG, "requestScopesViaService failed: $message")
                    onResult(false)
                }
            })
        }.onFailure {
            Log.w(TAG, "requestScopesViaService failed for $packages: ${it.message}")
            onResult(false)
        }
    }

    /**
     * 通过 libxposed API 102 原生通道请求将单个应用加入模块作用域（无需重启系统，LSPosed 弹出官方确认框）
     */
    fun requestScopeViaService(packageName: String, onResult: (Boolean) -> Unit = {}) {
        requestScopesViaService(listOf(packageName), onResult)
    }

    /**
     * 方案 3：自动调出（唤起）LSPosed 管理器界面
     *
     * @param context 应用上下文
     * @return true 表示成功调出 LSPosed 管理器，false 表示未检测到 LSPosed 管理器
     */
    fun launchLsposedManager(context: Context): Boolean {
        val pm = context.packageManager
        val candidatePackages = listOf(
            "org.lsposed.manager",
            "org.lsposed.manager.nightly",
            "io.github.vvb2060.mahoshojo",
        )

        for (pkg in candidatePackages) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error launching package $pkg: ${e.message}")
            }
        }

        // 显式 ComponentName 兜底
        try {
            val explicitIntent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (explicitIntent.resolveActivity(pm) != null) {
                context.startActivity(explicitIntent)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Explicit launch failed: ${e.message}")
        }

        return false
    }
}
