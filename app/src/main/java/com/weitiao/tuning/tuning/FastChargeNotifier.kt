package com.weitiao.tuning.tuning

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.weitiao.tuning.MainActivity
import com.weitiao.tuning.R

/**
 * 亮屏快充通知统一状态门面。
 *
 * 关键约束：
 * 1. 前台服务必须始终绑定一条通知以保持存活，严禁在待命态通过 `stopForeground`、`stopSelf` 或 `cancel(NOTIFICATION_ID)` 消除通知；
 * 2. 前台服务通知与充电实时数据通知合并为唯一 ID ([NOTIFICATION_ID] = 19077)：
 *    - 未插电/待命态：使用独立的 `IMPORTANCE_NONE` 隐藏渠道承载前台服务，通知栏不显示「亮屏快充」条目；
 *    - 插电充电态（含亮屏快充、息屏安全充电、高温保护恢复原生温控）：在同一通知 ID 上原地更新为 [CHANNEL_LIVE] 实时通知；
 *    - 拔线：立即将同一通知 ID 切回隐藏渠道通知，清除可见实时通知；
 * 3. 覆盖安装后通过带版本号的 [cleanupLegacyIfNeeded] 一次性清理旧版 19076 通知及旧渠道（仅在清理成功后写入版本号）；
 * 4. 严格区分两类渠道的设置策略：
 *    - 用户主动关闭「亮屏快充」可见通知类别 ([CHANNEL_LIVE])：100% 尊重用户设置，绝不新建替代渠道绕过；
 *    - 隐藏后台渠道 ([CHANNEL_HIDDEN_BASE]) 被系统或误操作调高重要性：支持有限次数（上限 [MAX_HIDDEN_CHANNEL_ROTATIONS] 次）
 *      「先新建隐藏渠道并切换前台通知绑定，再删除旧渠道」，确保不打断前台服务通知；若配额耗尽则回退至最小静默兜底渠道。
 */
object FastChargeNotifier {

    /** 唯一的亮屏快充通知 ID：前台服务承载与充电实时数据共用。 */
    const val NOTIFICATION_ID = 19077
    const val IDLE_STATUS = "待命中 · 接入充电器后自动生效"

    private const val TITLE = "亮屏快充"
    private const val LEGACY_SERVICE_NOTIFICATION_ID = 19076
    private const val CHANNEL_SERVICE_LEGACY = "fast_charge_service"
    private const val CHANNEL_SILENT_LEGACY = "fast_charge_service_silent"
    private const val CHANNEL_LIVE = "bright_screen_fast_charge"
    internal const val CHANNEL_HIDDEN_BASE = "fast_charge_service_hidden_v2"
    internal const val CHANNEL_HIDDEN_FALLBACK_MIN = "fast_charge_service_hidden_fallback"

    private const val PREFS_NAME = "fast_charge_notifier"
    private const val KEY_LEGACY_CLEANUP_VERSION = "legacy_cleanup_version"
    private const val KEY_HIDDEN_CHANNEL_ID = "hidden_channel_id"
    private const val KEY_HIDDEN_CHANNEL_ROTATIONS = "hidden_channel_rotations"
    private const val LEGACY_CLEANUP_VERSION = 3
    internal const val MAX_HIDDEN_CHANNEL_ROTATIONS = 3

    internal enum class DisplayState {
        NONE,
        HIDDEN,
        LIVE,
    }

    internal enum class TransitionAction {
        NO_OP,
        BIND_FOREGROUND_LIVE,
        BIND_FOREGROUND_HIDDEN,
        NOTIFY_LIVE,
        NOTIFY_HIDDEN,
        CANCEL_ALL,
    }

    internal data class HiddenChannelPlan(
        val targetChannelId: String,
        val createChannel: Boolean,
        val updatedRotations: Int,
        val obsoleteChannelIdToDeleteAfterBind: String? = null,
        val useDegradedMinimalFallback: Boolean = false,
    )

    @Volatile
    internal var displayState: DisplayState = DisplayState.NONE
        private set

    @Volatile
    internal var hiddenBoundToService: Boolean = false
        private set

    @Volatile
    internal var lastStatusText: String? = null
        private set

    @Volatile
    internal var lastSubText: String? = null
        private set

    @Volatile
    private var hiddenRotationExhaustedInSession: Boolean = false

    @Volatile
    private var pendingObsoleteHiddenChannelId: String? = null

    /**
     * 仅在覆盖安装后执行一次旧版 19076 通知与旧渠道清理。
     * - 若当前版本已清理过，仅做一次 SharedPreferences 内存读取，不触发任何 NotificationManager IPC；
     * - 绝不为从未开启过亮屏快充的用户创建任何新通知渠道或发布通知；
     * - 仅当清理全部成功完成时才写入 [LEGACY_CLEANUP_VERSION]，若发生异常则下次启动自动重试。
     */
    fun cleanupLegacyIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LEGACY_CLEANUP_VERSION, 0) >= LEGACY_CLEANUP_VERSION) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val currentHiddenId = prefs.getString(KEY_HIDDEN_CHANNEL_ID, CHANNEL_HIDDEN_BASE) ?: CHANNEL_HIDDEN_BASE
        if (cleanupLegacyNotifications(manager, currentHiddenId)) {
            prefs.edit().putInt(KEY_LEGACY_CLEANUP_VERSION, LEGACY_CLEANUP_VERSION).apply()
        }
    }

    fun ensureChannels(context: Context) {
        cleanupLegacyIfNeeded(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        prepareHiddenChannelId(context, manager)
        ensureLiveChannel(manager)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 检查用户是否在系统设置中允许显示「亮屏快充」实时通知。
     * 若用户主动关闭了应用通知或将该通知类别设为「无/关闭」，尊重用户设置，不强行展示或新建替代渠道。
     */
    fun isLiveChannelAllowed(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        ensureLiveChannel(manager)
        val channel = manager.getNotificationChannel(CHANNEL_LIVE) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 统一格式化充电状态文案（常驻服务与兜底路径共用，保证展示内容完全一致）。
     */
    internal fun formatChargingStatus(
        isScreenOn: Boolean,
        isOverheated: Boolean,
        level: Int,
        metrics: FastChargeController.BatteryMetrics,
    ): String = when {
        !isScreenOn -> String.format("息屏安全充 · 电量:%d%%", level)
        isOverheated -> String.format("高温保护恢复原生温控 · 电量:%d%%", level)
        metrics.currentMa > 0 && metrics.voltageMv > 0 -> {
            String.format(
                "电量:%d%% 电流:%dmA 电压:%.2fV 功率:%.1fW",
                level,
                metrics.currentMa,
                metrics.voltageMv / 1000.0,
                metrics.powerWatts,
            )
        }
        metrics.voltageMv > 0 -> {
            String.format("电量:%d%% 电流:读取中 电压:%.2fV", level, metrics.voltageMv / 1000.0)
        }
        else -> String.format("电量:%d%%", level)
    }

    /**
     * 纯 Kotlin 隐藏渠道决策逻辑（供生产环境 [prepareHiddenChannelId] 与单元测试共同调用）：
     * 1. 若当前渠道尚不存在：直接创建该隐藏渠道 (`IMPORTANCE_NONE`)；
     * 2. 若当前渠道存在且保持 `IMPORTANCE_NONE`：直接复用，0 额外开销；
     * 3. 若当前渠道重要性被外部改高（非 `IMPORTANCE_NONE`）：
     *    - 若重建次数未达上限 ([MAX_HIDDEN_CHANNEL_ROTATIONS]) 且本会话未熔断：规划创建新隐藏渠道，
     *      并将旧渠道记录为 [HiddenChannelPlan.obsoleteChannelIdToDeleteAfterBind]（必须在 `startForeground`/`notify` 切换到新渠道后再删除旧渠道，防止连带撤掉正在绑定的前台通知）；
     *    - 若重建次数已耗尽：切换至固定最小兜底渠道 [CHANNEL_HIDDEN_FALLBACK_MIN] 并启用降级最小化模式，不无限创建渠道。
     */
    internal fun planHiddenChannel(
        storedChannelId: String?,
        existingImportance: Int?,
        rotations: Int,
        exhaustedInSession: Boolean,
    ): HiddenChannelPlan {
        val currentId = storedChannelId?.takeIf { it.isNotBlank() } ?: CHANNEL_HIDDEN_BASE
        val safeRotations = rotations.coerceAtLeast(0)

        if (existingImportance == null) {
            return HiddenChannelPlan(
                targetChannelId = currentId,
                createChannel = true,
                updatedRotations = safeRotations,
            )
        }

        if (existingImportance == NotificationManager.IMPORTANCE_NONE) {
            return HiddenChannelPlan(
                targetChannelId = currentId,
                createChannel = false,
                updatedRotations = safeRotations,
            )
        }

        if (!exhaustedInSession && safeRotations < MAX_HIDDEN_CHANNEL_ROTATIONS) {
            val nextRotation = safeRotations + 1
            val nextId = "fast_charge_service_hidden_v${2 + nextRotation}"
            return HiddenChannelPlan(
                targetChannelId = nextId,
                createChannel = true,
                updatedRotations = nextRotation,
                obsoleteChannelIdToDeleteAfterBind = currentId.takeIf { it != nextId },
                useDegradedMinimalFallback = false,
            )
        }

        return HiddenChannelPlan(
            targetChannelId = CHANNEL_HIDDEN_FALLBACK_MIN,
            createChannel = true,
            updatedRotations = safeRotations,
            obsoleteChannelIdToDeleteAfterBind = currentId.takeIf { it != CHANNEL_HIDDEN_FALLBACK_MIN },
            useDegradedMinimalFallback = true,
        )
    }

    @Synchronized
    internal fun onBindForeground(liveChannelAllowed: Boolean): TransitionAction {
        val currentStatus = lastStatusText
        if (displayState == DisplayState.LIVE && !currentStatus.isNullOrBlank() && liveChannelAllowed) {
            hiddenBoundToService = false
            return TransitionAction.BIND_FOREGROUND_LIVE
        }
        displayState = DisplayState.HIDDEN
        hiddenBoundToService = true
        lastStatusText = IDLE_STATUS
        lastSubText = null
        return TransitionAction.BIND_FOREGROUND_HIDDEN
    }

    @Synchronized
    internal fun onPostLive(
        status: String,
        subText: String?,
        liveChannelAllowed: Boolean,
        hasService: Boolean,
    ): TransitionAction {
        if (!liveChannelAllowed) {
            return TransitionAction.NO_OP
        }
        if (displayState == DisplayState.LIVE && lastStatusText == status && lastSubText == subText) {
            return TransitionAction.NO_OP
        }
        displayState = DisplayState.LIVE
        hiddenBoundToService = false
        lastStatusText = status
        lastSubText = subText
        return if (hasService) TransitionAction.BIND_FOREGROUND_LIVE else TransitionAction.NOTIFY_LIVE
    }

    @Synchronized
    internal fun onHide(
        status: String = IDLE_STATUS,
        hasService: Boolean,
        forceStartForeground: Boolean = false,
    ): TransitionAction {
        if (hasService) {
            if (!forceStartForeground &&
                displayState == DisplayState.HIDDEN &&
                hiddenBoundToService &&
                lastStatusText == status
            ) {
                return TransitionAction.NO_OP
            }
            displayState = DisplayState.HIDDEN
            hiddenBoundToService = true
            lastStatusText = status
            lastSubText = null
            return TransitionAction.BIND_FOREGROUND_HIDDEN
        }

        if (displayState == DisplayState.HIDDEN && lastStatusText == status) {
            return TransitionAction.NO_OP
        }
        displayState = DisplayState.HIDDEN
        hiddenBoundToService = false
        lastStatusText = status
        lastSubText = null
        return TransitionAction.NOTIFY_HIDDEN
    }

    @Synchronized
    internal fun onCancelAll(): TransitionAction {
        displayState = DisplayState.NONE
        hiddenBoundToService = false
        lastStatusText = null
        lastSubText = null
        return TransitionAction.CANCEL_ALL
    }

    @Synchronized
    internal fun resetStateForTest() {
        displayState = DisplayState.NONE
        hiddenBoundToService = false
        lastStatusText = null
        lastSubText = null
        hiddenRotationExhaustedInSession = false
        pendingObsoleteHiddenChannelId = null
    }

    /**
     * 承载前台服务的最小化隐藏通知。仅用于满足系统对前台服务的要求，不在状态栏或通知抽屉展示。
     * 注意：若触发隐藏渠道轮转，此时仅创建新渠道并返回，旧渠道在 `startForeground`/`notify` 完成后再由
     * [deletePendingObsoleteHiddenChannel] 删除，确保不会因删除旧渠道而连带取消正在绑定的前台通知。
     */
    fun buildServiceNotification(context: Context, status: String = IDLE_STATUS): Notification {
        cleanupLegacyIfNeeded(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = manager?.let { prepareHiddenChannelId(context, it) } ?: CHANNEL_HIDDEN_BASE
        val isDegraded = channelId == CHANNEL_HIDDEN_FALLBACK_MIN
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_module)
            .setContentTitle(if (isDegraded) "" else TITLE)
            .setContentText(if (isDegraded) "" else status)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
        }
        return builder.build()
    }

    /** 充电时展示的唯一可见实时通知。 */
    fun buildLiveNotification(context: Context, status: String, subText: String? = null): Notification {
        ensureChannels(context)
        val builder = Notification.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_module)
            .setContentTitle(TITLE)
            .setContentText(status)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }
        return builder.build()
    }

    /**
     * 供前台服务在 `onCreate` / `onStartCommand` 中安全调用 `startForeground`：
     * - 若当前已处于充电实时通知态 ([DisplayState.LIVE]) 且通知允许展示，直接复用当前实时通知作为前台通知，避免闪烁；
     * - 否则绑定隐藏渠道通知，并在绑定完成后清理被替换的旧隐藏渠道。
     */
    @Synchronized
    fun bindForegroundService(service: Service): Boolean {
        val allowed = isLiveChannelAllowed(service)
        val manager = service.getSystemService(NotificationManager::class.java)
        runCatching { manager?.cancel(LEGACY_SERVICE_NOTIFICATION_ID) }

        return when (onBindForeground(allowed)) {
            TransitionAction.BIND_FOREGROUND_LIVE -> {
                val liveNotif = buildLiveNotification(service, lastStatusText ?: IDLE_STATUS, lastSubText)
                val bound = runCatching {
                    service.startForeground(NOTIFICATION_ID, liveNotif)
                    true
                }.getOrDefault(false)
                if (bound) {
                    deletePendingObsoleteHiddenChannel(manager)
                }
                bound
            }
            else -> {
                val hiddenNotif = buildServiceNotification(service, lastStatusText ?: IDLE_STATUS)
                val bound = runCatching {
                    service.startForeground(NOTIFICATION_ID, hiddenNotif)
                    true
                }.getOrDefault(false)
                hiddenBoundToService = bound
                if (bound) {
                    deletePendingObsoleteHiddenChannel(manager)
                }
                bound
            }
        }
    }

    /**
     * 统一入口：切换到充电实时可见态（原地更新同一个 [NOTIFICATION_ID]）。
     * 内置文案与副文本文本去重，数据未变化时不重复触发 SystemUI 重绘。
     */
    @Synchronized
    fun postLive(
        context: Context,
        status: String,
        subText: String? = null,
        service: Service? = null,
    ): Boolean {
        val allowed = isLiveChannelAllowed(context)
        if (!allowed) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        runCatching { manager.cancel(LEGACY_SERVICE_NOTIFICATION_ID) }

        val prevState = displayState
        val prevBound = hiddenBoundToService
        val prevStatus = lastStatusText
        val prevSubText = lastSubText

        val posted = when (onPostLive(status, subText, allowed, hasService = service != null)) {
            TransitionAction.NO_OP -> true
            TransitionAction.BIND_FOREGROUND_LIVE -> {
                val liveNotif = buildLiveNotification(context, status, subText)
                runCatching {
                    if (service != null) {
                        service.startForeground(NOTIFICATION_ID, liveNotif)
                    } else {
                        manager.notify(NOTIFICATION_ID, liveNotif)
                    }
                    true
                }.recoverCatching {
                    manager.notify(NOTIFICATION_ID, liveNotif)
                    true
                }.getOrDefault(false)
            }
            TransitionAction.NOTIFY_LIVE -> {
                runCatching {
                    manager.notify(NOTIFICATION_ID, buildLiveNotification(context, status, subText))
                    true
                }.getOrDefault(false)
            }
            else -> false
        }

        if (!posted) {
            displayState = prevState
            hiddenBoundToService = prevBound
            lastStatusText = prevStatus
            lastSubText = prevSubText
        } else {
            deletePendingObsoleteHiddenChannel(manager)
        }
        return posted
    }

    /**
     * 统一入口：未充电或拔线时切回隐藏态。
     * 始终用隐藏渠道通知原地替换 [NOTIFICATION_ID]，并在新通知绑定完成后再删除被轮转淘汰的旧隐藏渠道，
     * 绝不调用 `cancel(NOTIFICATION_ID)` 或 `stopForeground`，确保前台服务始终持有合法通知绑定。
     */
    @Synchronized
    fun hideNotification(
        context: Context,
        status: String = IDLE_STATUS,
        service: Service? = null,
        forceStartForeground: Boolean = false,
    ) {
        cleanupLegacyIfNeeded(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager?.cancel(LEGACY_SERVICE_NOTIFICATION_ID) }

        when (onHide(status = status, hasService = service != null, forceStartForeground = forceStartForeground)) {
            TransitionAction.NO_OP -> return
            TransitionAction.BIND_FOREGROUND_HIDDEN -> {
                val hiddenNotif = buildServiceNotification(service ?: context, status)
                val bound = runCatching {
                    service?.startForeground(NOTIFICATION_ID, hiddenNotif)
                    service != null
                }.getOrDefault(false)
                if (!bound && hasPermission(context)) {
                    runCatching { manager?.notify(NOTIFICATION_ID, hiddenNotif) }
                }
                hiddenBoundToService = bound
                deletePendingObsoleteHiddenChannel(manager)
            }
            TransitionAction.NOTIFY_HIDDEN -> {
                if (hasPermission(context)) {
                    val hiddenNotif = buildServiceNotification(context, status)
                    // 若隐藏渠道配额耗尽且处于降级模式，非 Service 上下文不主动 notify，防止在通知栏露出可见条目
                    val targetChannel = manager?.getNotificationChannel(hiddenNotif.channelId)
                    if (targetChannel == null || targetChannel.importance == NotificationManager.IMPORTANCE_NONE) {
                        runCatching {
                            manager?.notify(NOTIFICATION_ID, hiddenNotif)
                        }
                        deletePendingObsoleteHiddenChannel(manager)
                    }
                }
            }
            else -> Unit
        }
    }

    /** 关闭亮屏快充总开关时彻底移除通知并重置状态。 */
    @Synchronized
    fun cancelLive(context: Context) {
        onCancelAll()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.let { manager ->
                manager.cancel(NOTIFICATION_ID)
                manager.cancel(LEGACY_SERVICE_NOTIFICATION_ID)
                deletePendingObsoleteHiddenChannel(manager)
            }
        }
    }

    /**
     * 旧版本用 19076 发布常驻通知，升级后可能残留在通知栏。
     * 显式取消旧 ID / 旧通道上的活动通知并删除旧通知渠道。
     * @return 全部清理步骤无异常且旧渠道已不存在时返回 `true`。
     */
    private fun cleanupLegacyNotifications(
        manager: NotificationManager,
        activeHiddenChannelId: String,
    ): Boolean = runCatching {
        manager.cancel(LEGACY_SERVICE_NOTIFICATION_ID)
        runCatching {
            manager.activeNotifications.forEach { statusBarNotification ->
                val channelId = statusBarNotification.notification.channelId
                if (statusBarNotification.id == LEGACY_SERVICE_NOTIFICATION_ID ||
                    channelId == CHANNEL_SERVICE_LEGACY ||
                    channelId == CHANNEL_SILENT_LEGACY ||
                    (channelId.startsWith("fast_charge_service_hidden_v") && channelId != activeHiddenChannelId)
                ) {
                    manager.cancel(statusBarNotification.tag, statusBarNotification.id)
                }
            }
        }
        manager.deleteNotificationChannel(CHANNEL_SERVICE_LEGACY)
        manager.deleteNotificationChannel(CHANNEL_SILENT_LEGACY)
        runCatching {
            manager.notificationChannels.forEach { ch ->
                if (ch.id.startsWith("fast_charge_service_hidden_v") && ch.id != activeHiddenChannelId) {
                    manager.deleteNotificationChannel(ch.id)
                }
            }
        }
        manager.getNotificationChannel(CHANNEL_SERVICE_LEGACY) == null &&
            manager.getNotificationChannel(CHANNEL_SILENT_LEGACY) == null
    }.getOrDefault(false)

    /**
     * 准备独立隐藏渠道，但不在此处立即删除正在使用的旧隐藏渠道；
     * 旧隐藏渠道记入 [pendingObsoleteHiddenChannelId]，待新通知完成 `startForeground` / `notify` 绑定后再删除。
     */
    private fun prepareHiddenChannelId(context: Context, manager: NotificationManager): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedId = prefs.getString(KEY_HIDDEN_CHANNEL_ID, CHANNEL_HIDDEN_BASE) ?: CHANNEL_HIDDEN_BASE
        val rotations = prefs.getInt(KEY_HIDDEN_CHANNEL_ROTATIONS, 0)
        val existing = manager.getNotificationChannel(storedId)

        val plan = planHiddenChannel(
            storedChannelId = storedId,
            existingImportance = existing?.importance,
            rotations = rotations,
            exhaustedInSession = hiddenRotationExhaustedInSession,
        )

        if (plan.createChannel && manager.getNotificationChannel(plan.targetChannelId) == null) {
            createHiddenChannel(manager, plan.targetChannelId)
        }

        if (plan.targetChannelId != storedId || plan.updatedRotations != rotations) {
            prefs.edit()
                .putString(KEY_HIDDEN_CHANNEL_ID, plan.targetChannelId)
                .putInt(KEY_HIDDEN_CHANNEL_ROTATIONS, plan.updatedRotations)
                .apply()
        }

        if (plan.obsoleteChannelIdToDeleteAfterBind != null) {
            pendingObsoleteHiddenChannelId = plan.obsoleteChannelIdToDeleteAfterBind
        }

        val targetChannel = manager.getNotificationChannel(plan.targetChannelId)
        if (plan.createChannel && targetChannel != null && targetChannel.importance != NotificationManager.IMPORTANCE_NONE) {
            // 若底层 ROM 强制覆写新建渠道的 IMPORTANCE_NONE，标记本会话不再继续轮转，防止连续耗尽配额
            hiddenRotationExhaustedInSession = true
        }
        return plan.targetChannelId
    }

    private fun deletePendingObsoleteHiddenChannel(manager: NotificationManager?) {
        val obsoleteId = pendingObsoleteHiddenChannelId ?: return
        pendingObsoleteHiddenChannelId = null
        if (manager == null) return
        runCatching { manager.deleteNotificationChannel(obsoleteId) }
    }

    private fun createHiddenChannel(manager: NotificationManager, channelId: String) {
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "亮屏快充后台服务",
                NotificationManager.IMPORTANCE_NONE,
            ).apply {
                description = "维持亮屏快充监听（完全静默，接入充电器时才展示实时数据）"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    /**
     * 充电实时通知使用固定渠道。用户若在系统设置里主动关闭该渠道，尊重用户选择，
     * 不再创建新渠道绕过设置。
     */
    private fun ensureLiveChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_LIVE) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LIVE,
                TITLE,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "充电时实时展示电流、电压、功率与电池温度"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            },
        )
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
