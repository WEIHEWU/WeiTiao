package com.weitiao.tuning.core

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Root shell 执行工具（su 通道）。
 *
 * 优先复用长连接交互式 `su` 会话（避免旁路供电与亮屏快充守护每 3.5~5s fork 新进程带来的 CPU 抖动和 Magisk 弹窗日志），
 * 若长连接异常或超时则自动重建并回退到单次 `su` 进程兜底。
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

    private val sessionLock = Any()
    private var sessionProcess: Process? = null
    private var sessionWriter: BufferedWriter? = null
    private var sessionReader: BufferedReader? = null

    @Volatile
    private var lastSuErrorLogElapsed = 0L
    private const val SU_ERROR_LOG_COOLDOWN_MS = 30_000L

    /**
     * 执行 su 命令（优先走持久化 su 会话，异常自动回退单次 su 进程）
     * @param command 待执行的 shell 指令
     * @param timeoutSeconds 超时时间（秒）
     * @return Pair(exitCode, stdout)，失败返回 Pair(-1, errorMsg)
     */
    fun exec(command: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): Pair<Int, String> {
        val (shouldFallbackOneShot, persistentResult) = execPersistent(command, timeoutSeconds)
        if (persistentResult != null) return persistentResult
        // 若首次拉起 `su` 失败或被拒权立即退出（未超时也非复用旧会话掉线），不再重复 spawn execOneShot 刷屏
        if (!shouldFallbackOneShot) {
            return -1 to "Root su unavailable"
        }
        return execOneShot(command, timeoutSeconds)
    }

    private fun execPersistent(command: String, timeoutSeconds: Long): Pair<Boolean, Pair<Int, String>?> {
        synchronized(sessionLock) {
            var hadExistingSession = false
            return try {
                val existing = sessionProcess?.takeIf { it.isAlive }
                hadExistingSession = existing != null
                val proc = existing ?: openSessionLocked() ?: return false to null
                val writer = sessionWriter ?: return hadExistingSession to null
                val reader = sessionReader ?: return hadExistingSession to null
                val marker = "__WT_EOF_${System.nanoTime()}__"

                writer.write("{\n")
                writer.write(command)
                writer.write("\n} 2>&1\n")
                writer.write("__wt_rc=\$?\n")
                writer.write("echo \"\"\n")
                writer.write("echo \"$marker:\$__wt_rc\"\n")
                writer.flush()

                val future = ioExecutor.submit(Callable {
                    val out = StringBuilder()
                    var exitCode = -1
                    while (true) {
                        val line = reader.readLine() ?: throw IllegalStateException("EOF from persistent su session")
                        if (line.startsWith("$marker:")) {
                            exitCode = line.substringAfter("$marker:").trim().toIntOrNull() ?: -1
                            break
                        }
                        if (out.isNotEmpty()) out.append('\n')
                        out.append(line)
                    }
                    exitCode to out.toString()
                })

                try {
                    true to future.get(timeoutSeconds, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    closeSessionLocked()
                    Log.w(TAG, "Persistent su timed out after ${timeoutSeconds}s, falling back: $command")
                    true to null
                }
            } catch (t: Throwable) {
                closeSessionLocked()
                logSuFailureThrottled("Persistent su session failed (${t.message})")
                // 仅当已有存活会话中途断开时回退单次进程；全新 su 会话直接 EOF/失败多为无权限，不再重复拉起
                hadExistingSession to null
            }
        }
    }

    private fun openSessionLocked(): Process? {
        return try {
            closeSessionLocked()
            val proc = ProcessBuilder("su").redirectErrorStream(true).start()
            sessionProcess = proc
            sessionWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
            sessionReader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
            proc
        } catch (t: Throwable) {
            closeSessionLocked()
            logSuFailureThrottled("Failed to open su session: ${t.message}")
            null
        }
    }

    private fun elapsedMs(): Long =
        runCatching { android.os.SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }

    internal fun shouldLogSuFailure(nowElapsedMs: Long, lastLoggedElapsedMs: Long): Boolean =
        lastLoggedElapsedMs == 0L || (nowElapsedMs - lastLoggedElapsedMs) >= SU_ERROR_LOG_COOLDOWN_MS

    private fun logSuFailureThrottled(message: String) {
        val now = elapsedMs()
        if (shouldLogSuFailure(now, lastSuErrorLogElapsed)) {
            lastSuErrorLogElapsed = now
            Log.w(TAG, message)
        }
    }

    private fun closeSessionLocked() {
        runCatching { sessionWriter?.close() }
        runCatching { sessionReader?.close() }
        runCatching { sessionProcess?.destroyForcibly() }
        sessionWriter = null
        sessionReader = null
        sessionProcess = null
    }

    /** 单次 `su` 进程执行兜底 */
    private fun execOneShot(command: String, timeoutSeconds: Long): Pair<Int, String> {
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
            logSuFailureThrottled("Failed to exec root command (${e.message}): $command")
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

    /** 无阻塞读取最近 15 秒内的 Root 缓存结果；未探测或已过期返回 null，绝不在主线程触发 su */
    fun peekCachedHasRoot(): Boolean? {
        val now = elapsedMs()
        val checkedAt = lastRootCheckElapsed
        return if (checkedAt > 0L && (now - checkedAt < ROOT_CHECK_TTL_MS)) cachedHasRoot else null
    }

    /** 无阻塞读取本进程最近一次 Root 探测结果（用于 UI 首帧初始化，避免「未获 Root」横幅跳闪） */
    fun peekLastKnownHasRoot(): Boolean? =
        if (lastRootCheckElapsed > 0L) cachedHasRoot else null

    /** 检查 root 是否可用，支持 15 秒缓存以避免高频重复创建 su 进程与竞争锁 */
    fun hasRoot(forceRefresh: Boolean = false): Boolean {
        val now = elapsedMs()
        if (!forceRefresh && lastRootCheckElapsed > 0L && (now - lastRootCheckElapsed < ROOT_CHECK_TTL_MS)) {
            return cachedHasRoot
        }
        val (code, out) = exec("id", timeoutSeconds = 3)
        val available = code == 0 && (out.contains("uid=0") || out.trim() == "0" || out.lines().any { it.trim() == "0" })
        cachedHasRoot = available
        lastRootCheckElapsed = elapsedMs()
        return available
    }
}
