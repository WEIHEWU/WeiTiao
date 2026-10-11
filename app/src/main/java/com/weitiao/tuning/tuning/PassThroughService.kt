package com.weitiao.tuning.tuning

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.weitiao.tuning.MainActivity
import com.weitiao.tuning.R
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.TuningPrefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

class PassThroughService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Tuning-PassThroughWorker").apply { isDaemon = true }
    }

    private var batteryLevel = 0
    private var appliedState: Boolean? = null
    private var receiverRegistered = false
    private var pollRunning = false
    private val evaluationQueued = AtomicBoolean()

    @Volatile private var isCharging = false
    @Volatile private var isScreenOn = true

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
                    if (plugged != isCharging) {
                        isCharging = plugged
                        requestEvaluation()
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    isCharging = true
                    handler.removeCallbacks(pollRunnable)
                    handler.post(pollRunnable)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    requestEvaluation()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    handler.removeCallbacks(pollRunnable)
                    handler.post(pollRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    requestEvaluation()
                }
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isFeatureEnabled()) {
                disableAndStop()
                return
            }
            requestEvaluation()
            // 待命状态（未插电或息屏）拉长轮询至 15s，插电且亮屏时保持 3.5s 极速响应
            val nextDelay = if (isCharging && isScreenOn) POLL_INTERVAL_MS else POLL_IDLE_MS
            handler.postDelayed(this, nextDelay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 待命态用静默通道承载前台服务：服务存活但通知不可见
        runCatching {
            startForeground(NOTIFICATION_ID, buildNotification("等待所选应用进入前台", visible = false))
        }.onFailure { Log.e(TAG, "前台服务启动失败（可能受后台启动限制）", it) }

        val powerManager = getSystemService(PowerManager::class.java)
        isScreenOn = powerManager?.isInteractive ?: true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        receiverRegistered = true
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            batteryLevel = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            isCharging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_DISABLE == intent.action) {
            disableAndStop()
            return START_NOT_STICKY
        }
        if (!isFeatureEnabled()) {
            disableAndStop()
            return START_NOT_STICKY
        }
        if (!pollRunning) {
            pollRunning = true
            handler.post(pollRunnable)
        } else {
            requestEvaluation()
        }
        return START_STICKY
    }

    private fun requestEvaluation() {
        if (!evaluationQueued.compareAndSet(false, true)) return
        postWorker {
            try {
                val config = TuningPrefs.load(this)
                val selected = TuningPrefs.selectedApps(this)
                val limit = config.passThroughLimit

                // 资源极简防抖守卫：
                // 仅在「插电中 + 亮屏 + 已选目标应用 + 电量达到限额」时，才执行耗资源的 dumpsys 窗口探测
                val canEngage = config.passThrough &&
                    isCharging &&
                    isScreenOn &&
                    selected.isNotEmpty() &&
                    batteryLevel >= limit

                val (desired, foreground) = if (canEngage) {
                    val fg = readForegroundPackage()
                    val match = selected.contains(fg)
                    (match to fg)
                } else {
                    (false to "")
                }

                applyIfChanged(desired)

                val status = when {
                    desired -> "旁路供电中 · $batteryLevel% · ${appLabel(foreground)}"
                    !isCharging -> "未连接充电器 · $batteryLevel%"
                    selected.isEmpty() -> "尚未选择应用"
                    batteryLevel < limit -> "等待电量达到 $limit%（当前 $batteryLevel%）"
                    !isScreenOn -> "息屏待命 · $batteryLevel%"
                    else -> "等待所选应用进入前台 · $batteryLevel%"
                }
                // 只有旁路供电真正生效时才让通知可见
                updateNotification(status, visible = desired)
            } finally {
                evaluationQueued.set(false)
            }
        }
    }

    private fun readForegroundPackage(): String {
        val (code, out) = RootShell.exec("dumpsys window displays | grep -E 'mCurrentFocus'", timeoutSeconds = 2)
        if (code == 0 && out.isNotBlank()) {
            val matcher = FOCUSED_WINDOW_PATTERN.matcher(out)
            if (matcher.find()) return matcher.group(1).orEmpty()
        }
        val (codeRecents, outRecents) = RootShell.exec("dumpsys activity recents | grep -m 1 -E 'topActivity='", timeoutSeconds = 2)
        if (codeRecents == 0 && outRecents.isNotBlank()) {
            val matcher = RECENTS_PATTERN.matcher(outRecents)
            if (matcher.find()) return matcher.group(1).orEmpty()
        }
        return ""
    }

    private fun applyIfChanged(enabled: Boolean) {
        if (appliedState == enabled) return
        val success = setPassThroughSysfs(enabled)
        if (success) {
            appliedState = enabled
            Log.i(TAG, "pass_through set to ${if (enabled) 1 else 0}")
        } else {
            Log.e(TAG, "旁路供电控制失败")
            // 故障提示必须让用户看见，即便此刻并不处于生效状态
            updateNotification("Root 控制失败，请检查授权", visible = true)
        }
    }

    private fun setPassThroughSysfs(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val cmd = "echo $value > /sys/class/power_supply/battery/pass_through; echo $value > /sys/class/power_supply/battery/batt_slate_mode; echo __OK__sysfs"
        val (code, out) = RootShell.exec(cmd, timeoutSeconds = 4)
        return code == 0 && out.contains("__OK__sysfs")
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun disableAndStop() {
        pollRunning = false
        handler.removeCallbacks(pollRunnable)
        postWorker {
            setPassThroughSysfs(false)
            appliedState = false
            handler.post {
                // 关 sysfs 是异步的，这期间用户可能又把开关打开了。
                // 不重新判断就 stopSelf() 会把刚重启的服务杀掉，
                // 表现为「关掉再打开后功能要等下次刷新才生效」。
                if (isFeatureEnabled()) {
                    Log.i(TAG, "停止流程被取消：功能已重新开启")
                    return@post
                }
                stopSelf()
            }
        }
    }

    /**
     * 提交后台任务。
     *
     * 与 BrightScreenFastChargeService 同一类问题：onDestroy 里 worker 已经 shutdown，
     * 此后 execute 会抛 RejectedExecutionException 让前台服务崩溃，这里统一兜住。
     */
    private fun postWorker(block: () -> Unit) {
        runCatching { worker.execute(block) }
            .onFailure { Log.w(TAG, "后台任务提交失败（服务正在停止）", it) }
    }

    private fun isFeatureEnabled(): Boolean =
        TuningPrefs.load(this).passThrough

    /**
     * 通道分两条，决定通知是否真的出现在状态栏：
     * - [CHANNEL_ID]：IMPORTANCE_LOW，仅旁路供电**正在生效**时使用，用户看得见；
     * - [CHANNEL_SILENT]：IMPORTANCE_NONE，待命态承载前台服务，完全不可见。
     *
     * 前者不能省（否则服务会被系统回收，切到游戏时无法即时接管供电）；
     * 后者保证未生效时通知栏干净。
     */
    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "USB PD 旁路供电",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "旁路供电生效时展示当前电量与前台应用"
                    setShowBadge(false)
                },
            )
        }
        runCatching {
            if (manager.getNotificationChannel(CHANNEL_SILENT) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_SILENT,
                        "USB PD 旁路供电后台服务",
                        NotificationManager.IMPORTANCE_NONE,
                    ).apply {
                        description = "维持旁路供电监听（完全静默，生效时才展示）"
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    private fun buildNotification(status: String, visible: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, if (visible) CHANNEL_ID else CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_module)
            .setContentTitle("微调 · 暂停 USB PD 游戏充电")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /**
     * @param visible 旁路供电是否正在生效。false 时通知走静默通道，
     *                即用户看不到任何东西，但前台服务依旧存活。
     */
    private fun updateNotification(status: String, visible: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = buildNotification(status, visible)
        if (visible) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        } else {
            // 用静默通道重新投递同一 ID，把上一次的可见通知顶掉
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        pollRunning = false
        handler.removeCallbacks(pollRunnable)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            receiverRegistered = false
        }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_REFRESH = "com.weitiao.tuning.action.REFRESH_PASS_THROUGH"
        const val ACTION_DISABLE = "com.weitiao.tuning.action.DISABLE_PASS_THROUGH"

        private const val TAG = "TuningPassThrough"
        private const val CHANNEL_ID = "usb_pd_pass_through"
        private const val CHANNEL_SILENT = "usb_pd_pass_through_silent"
        // 注意：不能与 FastChargeNotifier.LIVE_NOTIFICATION_ID(19077) 撞号。
        // 两个服务同属一个包，id 相同会互相覆盖通知——同时开启亮屏快充和旁路供电时
        // 用户只会看到其中一个，且实时电流信息会闪来闪去。
        private const val NOTIFICATION_ID = 19078
        // 将轮询优化为 3500ms（活跃态），待命态拉长为 15000ms，大幅减少 CPU 唤醒与系统 AMS 负载
        private const val POLL_INTERVAL_MS = 3_500L
        private const val POLL_IDLE_MS = 15_000L
        private val FOCUSED_WINDOW_PATTERN = Pattern.compile(
            "mCurrentFocus=Window\\{[^\\}]*?\\s+(?:u[0-9]+\\s+)?([A-Za-z0-9_.$]+)/"
        )
        private val RECENTS_PATTERN = Pattern.compile(
            "topActivity=\\{([A-Za-z0-9_.$]+)/"
        )
    }
}
