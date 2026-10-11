package com.weitiao.tuning.core

import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 读取 LSPosed 模块日志，推断"哪些作用域进程里本模块真的被加载了"。
 *
 * ## 为什么走日志而不是写 remote prefs 心跳
 *
 * 早期设计是 hook 写 `hook_heartbeat.<pkg>` 到 LSPosed remote prefs，App 读这个 key 计数。
 * 实测在 libxposed 102 下 **hook 拿到的 `XposedInterface.getRemotePreferences()` 是只读的**，
 * 任何 `.edit().putXxx().commit()` 都会抛 `UnsupportedOperationException: Read only implementation`。
 * 可写版本 `XposedService.getRemotePreferences()` 只对**模块自身 UID** 开放，hook 跑在
 * 宿主进程（com.tencent.mm 等）里拿不到，所以这条信号通道在 API 层就不可用。
 *
 * 真源是 LSPosed 模块日志 `/data/adb/lspd/log/modules_*.log`：每行形如
 * `[ 2026-09-02T21:40:21.645 ... ] ... CscChanger: hooked in com.tencent.mm ...`。
 * hook 每加载一个作用域进程就写一条，App 用 root 读 + 按时间窗过滤，就是真实的活跃度。
 *
 * 因为读 root 文件有开销，对外暴露的 [recentHookedPackages] 自带 2 秒缓存，
 * 配合 UI 的 2 秒轮询节奏刚好，不会因为 `LaunchedEffect` 多次重组而打爆 root shell。
 */
object HookLiveness {

    private const val TAG = "HookLiveness"
    private const val RECENT_WINDOW_MIN = 30L

    /**
     * 只把"最近 N 分钟内"加载过的进程算作当前活跃。
     *
     * 微信主进程可存活数小时，太短的窗口会把"还活着但很久没重启"误判为未生效。
     * 短生命周期进程（`:push` / `:appbrand*`）每次启动都重打 `hooked in`，
     * 时间戳自然新，所以短进程永远不会"过期"。
     */
    private val windowMillis = RECENT_WINDOW_MIN * 60_000L

    @Volatile private var cached: Set<String>? = null
    @Volatile private var cachedAt = 0L
    private const val CACHE_TTL_MS = 2_000L

    /**
     * 最近 [RECENT_WINDOW_MIN] 分钟内，被本模块成功加载过的作用域进程包名集合。
     *
     * - `null`：无法读取（root 不可用 / 异常），调用方按"未知"处理。
     * - 空集合：日志可读但没有符合时间窗的加载记录。
     */
    fun recentHookedPackages(): Set<String>? {
        val now = System.currentTimeMillis()
        val c = cached
        if (c != null && now - cachedAt < CACHE_TTL_MS) return c
        val fresh = compute()
        if (fresh != null) {
            cached = fresh
            cachedAt = now
        }
        return fresh
    }

    private fun compute(): Set<String>? = runCatching {
        // 在 su 下取最新模块日志（按 mtime 倒序），取末尾 500 行，筛本模块的 `hooked in`。
        val cmd = "LOG=\$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1); " +
            "if [ -n \"\$LOG\" ]; then tail -n 500 \"\$LOG\" 2>/dev/null | grep 'CscChanger: hooked in'; fi"
        val (code, out) = RootShell.exec(cmd, timeoutSeconds = 4)
        if (code < 0) {
            Log.w(TAG, "root exec failed, liveness unknown")
            null
        } else {
            parse(out)
        }
    }.getOrElse {
        Log.w(TAG, "HookLiveness compute failed: $it")
        null
    }

    private fun parse(stdout: String): Set<String> {
        if (stdout.isBlank()) return emptySet()
        val now = System.currentTimeMillis()
        val cutoff = now - windowMillis
        val result = LinkedHashSet<String>()
        val re = Regex(
            """^\[\s+(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+)\s+.*?CscChanger: hooked in\s+(\S+)\s""",
        )
        stdout.lineSequence().forEach { line ->
            val m = re.find(line) ?: return@forEach
            val (iso, pkg) = m.destructured
            val ts = runCatching {
                LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: return@forEach
            if (ts >= cutoff) result += pkg
        }
        return result
    }
}
