package com.weitiao.tuning.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val labelLower: String,
    val pkgLower: String,
    val iconBitmap: ImageBitmap?,
)

/**
 * 进程级已安装应用列表与图标内存缓存仓库：
 * 将 `PackageManager.queryIntentActivities` + `loadLabel` + `loadIcon` + `toBitmap(84, 84)`
 * 统一下沉到 `Dispatchers.IO` 并缓存结果，彻底消除进入页面或打开应用选择弹窗时的主线程卡顿。
 */
object InstalledAppsRepository {

    private val loadMutex = Mutex()
    private val labelCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedLauncherApps: List<InstalledAppItem>? = null

    fun peekCachedApps(): List<InstalledAppItem>? = cachedLauncherApps

    fun peekAppLabel(packageName: String): String? = labelCache[packageName]

    suspend fun getInstalledLauncherApps(
        context: Context,
        forceRefresh: Boolean = false,
    ): List<InstalledAppItem> {
        if (!forceRefresh) {
            cachedLauncherApps?.let { return it }
        }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cachedLauncherApps?.let { return@withLock it }
            }
            withContext(Dispatchers.IO) {
                val appCtx = context.applicationContext
                val pm = appCtx.packageManager
                val selfPkg = appCtx.packageName
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val items = runCatching {
                    pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                        .asSequence()
                        .filter { it.activityInfo.packageName != selfPkg }
                        .distinctBy { it.activityInfo.packageName }
                        .map { resolveInfo ->
                            val pkg = resolveInfo.activityInfo.packageName
                            val label = runCatching { resolveInfo.loadLabel(pm).toString() }.getOrDefault(pkg)
                            labelCache[pkg] = label
                            val iconBitmap = runCatching {
                                resolveInfo.loadIcon(pm).toBitmap(84, 84).asImageBitmap()
                            }.getOrNull()
                            InstalledAppItem(
                                packageName = pkg,
                                label = label,
                                labelLower = label.lowercase(),
                                pkgLower = pkg.lowercase(),
                                iconBitmap = iconBitmap,
                            )
                        }
                        .sortedBy { it.labelLower }
                        .toList()
                }.getOrDefault(emptyList())
                if (items.isNotEmpty()) {
                    cachedLauncherApps = items
                }
                items
            }
        }
    }

    suspend fun resolveAppLabels(
        context: Context,
        packages: Collection<String>,
    ): Map<String, String> {
        if (packages.isEmpty()) return emptyMap()
        val missing = packages.filter { !labelCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val pm = context.applicationContext.packageManager
                for (pkg in missing) {
                    val label = runCatching {
                        val info = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(info).toString()
                    }.getOrDefault(pkg)
                    labelCache[pkg] = label
                }
            }
        }
        return packages.associateWith { labelCache[it] ?: it }
    }
}
