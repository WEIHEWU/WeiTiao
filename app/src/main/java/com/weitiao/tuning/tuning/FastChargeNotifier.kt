package com.weitiao.tuning.tuning

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.weitiao.tuning.MainActivity
import com.weitiao.tuning.R

/**
 * 亮屏快充通知门面：常驻服务通知与充电实时通知统一在这里构造，
 * 保证「前台服务」与「广播/任务兜底」两条路径展示完全一致的内容。
 *
 * 三通道设计：
 * - [CHANNEL_SILENT]：IMPORTANCE_NONE，承载前台服务但**完全不显示**。
 *   前台服务不能没有通知（否则会被系统回收，插电即时生效就没了），
 *   而 IMPORTANCE_NONE 能让服务保持前台优先级的同时不占状态栏与通知栏。
 * - [CHANNEL_SERVICE]：历史遗留的常驻通道（IMPORTANCE_MIN），升级时会被删除，
 *   保留常量仅用于清理旧版本残留。
 * - [CHANNEL_LIVE]：IMPORTANCE_LOW，仅在插入充电器时展示实时电流/电压/功率/温度。
 */
object FastChargeNotifier {

    const val CHANNEL_SERVICE = "fast_charge_service"
    const val CHANNEL_SILENT = "fast_charge_service_silent"
    const val CHANNEL_LIVE = "bright_screen_fast_charge"

    private const val LIVE_NOTIFICATION_ID = 19077

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // 旧版本的常驻通道会在通知栏留下一条永久条目，升级后删掉它。
        // 若用户手动改过该通道的重要性，系统会拒绝删除，忽略即可。
        runCatching { manager.deleteNotificationChannel(CHANNEL_SERVICE) }
        // IMPORTANCE_NONE：服务照旧是前台优先级，但通知在状态栏与通知抽屉里都不可见。
        // 这是「待命时通知栏全干净」与「服务不被系统回收」之间唯一可靠的平衡点。
        if (manager.getNotificationChannel(CHANNEL_SILENT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SILENT,
                    "亮屏快充后台服务",
                    NotificationManager.IMPORTANCE_NONE,
                ).apply {
                    description = "维持亮屏快充监听（完全静默，接入充电器时才展示实时数据）"
                    setShowBadge(false)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_LIVE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LIVE,
                    "亮屏快充",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "充电时实时展示电流、电压、功率与电池温度"
                    setShowBadge(false)
                },
            )
        }
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 承载前台服务的常驻通知（静默通道）。
     *
     * 只用于满足「前台服务必须有通知」的系统要求，因为走 IMPORTANCE_NONE 通道，
     * 用户不会在状态栏或通知抽屉里看到它。真正要给用户看的信息一律走 [buildLiveNotification]。
     */
    fun buildServiceNotification(context: Context, status: String): Notification {
        ensureChannels(context)
        return Notification.Builder(context, CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_module)
            .setContentTitle("亮屏快充")
            .setContentText(status)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    /** 充电时的实时数据通知（完全对齐 S8Tweak 原版通知格式） */
    fun buildLiveNotification(context: Context, status: String, subText: String? = null): Notification {
        ensureChannels(context)
        val builder = Notification.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_module)
            .setContentTitle("亮屏快充")
            .setContentText(status)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }
        return builder.build()
    }

    fun postLive(context: Context, status: String, subText: String? = null): Boolean {
        if (!hasPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.notify(LIVE_NOTIFICATION_ID, buildLiveNotification(context, status, subText))
        return true
    }

    fun cancelLive(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(LIVE_NOTIFICATION_ID)
        }
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
