package com.weitiao.tuning.core

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * LSPosed 作用域同步与调出助手
 * 支持通过 Root 自动将选中的伪装应用写入 LSPosed 作用域数据库，并自动调出 LSPosed 管理器供用户确认。
 */
object LsposedScopeHelper {

    private const val TAG = "LsposedScopeHelper"
    private const val LSPD_DB_PATH = "/data/adb/lspd/config/modules_config.db"
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

    /**
     * 方案 3：通过 Root 静默将用户勾选的应用写入 LSPosed 作用域数据库
     *
     * @param context 应用上下文
     * @param targetPackages 用户选中的伪装目标应用包名集合
     * @return true 表示成功写入数据库，false 表示未检测到 LSPosed 或无 Root 权限
     */
    fun syncScopesWithRoot(context: Context, targetPackages: Set<String>): Boolean {
        if (targetPackages.isEmpty()) {
            return false
        }

        try {
            // 1. 检查是否存在 LSPosed 模块配置数据库
            val (checkExit, _) = RootShell.exec("[ -f $LSPD_DB_PATH ]")
            if (checkExit != 0) {
                Log.d(TAG, "LSPosed modules_config.db not found at $LSPD_DB_PATH")
                return false
            }

            val cacheDir = context.cacheDir
            val localDb = File(cacheDir, "modules_config.db")
            val localWal = File(cacheDir, "modules_config.db-wal")
            val localShm = File(cacheDir, "modules_config.db-shm")

            // 清理本地残留临时文件
            localDb.delete()
            localWal.delete()
            localShm.delete()

            val appUid = android.os.Process.myUid()

            // 2. 通过 Root 将 LSPosed 数据库拷贝至应用缓存，并授权当前应用读写
            val copyCmd = """
                cp ${LSPD_DB_PATH}* '${cacheDir.absolutePath}/'
                chown $appUid:$appUid '${cacheDir.absolutePath}'/modules_config.db*
                chmod 666 '${cacheDir.absolutePath}'/modules_config.db*
            """.trimIndent()

            val (copyExit, _) = RootShell.exec(copyCmd)
            if (copyExit != 0 || !localDb.exists()) {
                Log.w(TAG, "Failed to copy LSPosed database to cache: exit $copyExit")
                return false
            }

            // 3. 使用 Android 原生 SQLiteDatabase 读写数据库
            var db: SQLiteDatabase? = null
            try {
                db = SQLiteDatabase.openDatabase(localDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                db.beginTransaction()
                try {
                    // 合并核心作用域与用户勾选的机型伪装目标应用
                    val allToInclude = CORE_SCOPES + targetPackages

                    for (pkg in allToInclude) {
                        val cv = ContentValues().apply {
                            put("module_pkg_name", MODULE_PKG)
                            put("app_pkg_name", pkg)
                            put("user_id", 0)
                        }
                        db.insertWithOnConflict("scope", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                    }

                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }

                // 截断并将 WAL 数据完全回写至主 db 文件
                try {
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null).use { c ->
                        while (c.moveToNext()) {
                            // 消费游标
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to checkpoint WAL: ${e.message}")
                }
            } finally {
                db?.close()
            }

            // 4. 将更新后的数据库通过 Root 原位写回 /data/adb/lspd/config/（保持 inode 不变），并恢复权限与 SELinux 上下文
            val writeBackCmd = """
                cat '${localDb.absolutePath}' > $LSPD_DB_PATH
                chown root:root $LSPD_DB_PATH
                chmod 600 $LSPD_DB_PATH
                chcon u:object_r:adb_data_file:s0 $LSPD_DB_PATH 2>/dev/null || true
                rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm
                rm -f '${cacheDir.absolutePath}'/modules_config.db*
            """.trimIndent()

            val (writeExit, _) = RootShell.exec(writeBackCmd)

            // 清理本地临时文件
            localDb.delete()
            localWal.delete()
            localShm.delete()

            // 同时通过 libxposed 内存服务通道请求未在当前内存作用域中的应用，确保无需重启系统即可立即挂载
            requestScopesViaService(targetPackages)

            if (writeExit == 0) {
                Log.i(TAG, "Successfully synced ${targetPackages.size} packages to LSPosed scope database")
                // 同步完成后重启目标应用进程（排除微信以防打断聊天，仅对普通伪装目标执行 am force-stop），确保下次启动即刻挂载 Hook
                val toRestart = targetPackages.filter { it != "com.tencent.mm" }
                if (toRestart.isNotEmpty()) {
                    StoreCleaner.forceStopPackages(toRestart)
                }
                return true
            } else {
                Log.w(TAG, "Failed to write back updated LSPosed database: exit $writeExit")
                return false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during syncScopesWithRoot", e)
            return false
        }
    }

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
                    onResult(approved.isNotEmpty())
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
