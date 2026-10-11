package com.weitiao.tuning.core

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Root shell 执行工具（su 通道）。
 * 支持异步读取 stdout/stderr、超时保护与 UTF-8 字符流处理，防止管道死锁。
 */
object RootShell {

    private const val TAG = "RootShell"
    private const val DEFAULT_TIMEOUT_SECONDS = 10L
    /** 进程退出后等待读取线程排空管道的时间（EOF 一到即返回，正常远小于该值） */
    private const val DRAIN_TIMEOUT_SECONDS = 5L
    private val ioExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "RootShell-IO").apply { isDaemon = true }
    }

    /**
     * 执行 su 命令
     * @param command 待执行的 shell 指令
     * @param timeoutSeconds 超时时间（秒）
     * @return Pair(exitCode, stdout)，失败返回 Pair(-1, errorMsg)
     */
    fun exec(command: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): Pair<Int, String> {
        var process: Process? = null
        var stdoutFuture: Future<String>? = null
        var stderrFuture: Future<String>? = null
        return try {
            val proc = ProcessBuilder("su").redirectErrorStream(false).start()
            process = proc

            // 异步读取 stdout 与 stderr，避免管道缓冲区塞满导致死锁
            stdoutFuture = ioExecutor.submit<String> {
                proc.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
            stderrFuture = ioExecutor.submit<String> {
                proc.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }

            OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(command)
                writer.write("\nexit\n")
                writer.flush()
            }

            val completed = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                proc.destroyForcibly()
                Log.w(TAG, "Root command timed out after ${timeoutSeconds}s: $command")
                return -1 to "Command timed out"
            }

            val exitCode = proc.exitValue()
            // 进程已退出，管道必然到达 EOF，读取线程很快就会结束。
            // 但绝不能只给 1 秒：设备繁忙或 su 本身有开销时拿不到输出，
            // 于是 stdout 为空 → 调用方的 `stdout.contains("__UID__0")` 判定失败，
            // 表现为「明明有 root 却报未获得 Root 权限」「快充时好时坏」「电流读数时有时无」。
            // 这里给足时间，真正卡住的情况由外层 waitFor 超时兜底。
            val stdout = runCatching { stdoutFuture.get(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault("")
            val stderr = runCatching { stderrFuture.get(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault("")

            if (exitCode != 0 && stderr.isNotBlank()) {
                Log.d(TAG, "Root command exit=$exitCode, stderr: $stderr")
            }
            exitCode to (if (stdout.isNotBlank()) stdout else stderr)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to exec root command: $command", e)
            -1 to (e.message ?: "Root execution failed")
        } finally {
            stdoutFuture?.cancel(true)
            stderrFuture?.cancel(true)
            if (process != null && process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    @Volatile
    private var lastRootCheckElapsed = 0L
    @Volatile
    private var cachedHasRoot = false
    private const val ROOT_CHECK_TTL_MS = 15_000L

    /** 检查 root 是否可用，支持 15 秒缓存以避免高频重复创建 su 进程与竞争锁 */
    fun hasRoot(forceRefresh: Boolean = false): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!forceRefresh && lastRootCheckElapsed > 0L && (now - lastRootCheckElapsed < ROOT_CHECK_TTL_MS)) {
            return cachedHasRoot
        }
        val (code, out) = exec("id", timeoutSeconds = 3)
        val available = code == 0 && (out.contains("uid=0") || out.trim() == "0" || out.lines().any { it.trim() == "0" })
        cachedHasRoot = available
        lastRootCheckElapsed = now
        return available
    }
}
