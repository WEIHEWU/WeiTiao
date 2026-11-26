package com.weitiao.tuning.tuning

import android.app.NotificationManager
import android.os.BatteryManager
import com.weitiao.tuning.core.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FastChargeSafetyTest {

    @Before
    fun setUp() {
        FastChargeNotifier.resetStateForTest()
    }

    @Test
    fun normalizeTempRejectsNegativeErrorValuesLikeMinus88Point8() {
        // SSRM / sysfs error value -888 (-88.8°C) and any negative value must NEVER become positive via abs()
        assertEquals(0, FastChargeController.normalizeTemp(-888))
        assertEquals(0, FastChargeController.normalizeTemp(-88800))
        assertEquals(0, FastChargeController.normalizeTemp(-300))
        assertEquals(0, FastChargeController.normalizeTemp(0))
        assertEquals(0, FastChargeController.normalizeTemp(null))
    }

    @Test
    fun normalizeTempHandlesTenthsHundredthsAndMillidegrees() {
        assertEquals(440, FastChargeController.normalizeTemp(440))
        assertEquals(440, FastChargeController.normalizeTemp(4400))
        assertEquals(440, FastChargeController.normalizeTemp(44000))
        assertEquals(567, FastChargeController.normalizeTemp(567))
        assertEquals(567, FastChargeController.normalizeTemp(56700))
    }

    @Test
    fun isOverheatedTripsOnBatteryOnlyAndHoldsUntilCooledDown() {
        // Case 1: Battery is cool (38.0°C) even if AP/PA chip temp field is high (50.0°C) -> must NOT false-trip!
        val coolBattHighChipMetrics = FastChargeController.BatteryMetrics(
            currentMa = 3000,
            voltageMv = 4200,
            powerWatts = 12.6,
            temperatureTenths = 380,
            maxChipTempTenths = 500,
        )
        assertFalse(
            "High AP/chip temperature must not trigger false thermal cutoff while battery is 38.0°C",
            FastChargeController.isOverheated(coolBattHighChipMetrics, cutoffCelsius = 44, previouslyOverheated = false),
        )

        // Case 2: Once overheated, if sensor returns 0 (e.g. -88.8°C error), must STAY overheated (fail-safe)
        val unreadableMetrics = FastChargeController.BatteryMetrics(
            currentMa = 3000,
            voltageMv = 4200,
            powerWatts = 12.6,
            temperatureTenths = 0,
            maxChipTempTenths = 0,
        )
        assertTrue(
            "Unreadable temperature (0) during overheat must keep thermal cutoff active",
            FastChargeController.isOverheated(unreadableMetrics, cutoffCelsius = 44, previouslyOverheated = true),
        )

        // Case 3: Battery at 44.0°C trips 44°C cutoff, stays overheated at 42.0°C, cools down at 40.5°C
        val hotBatt = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 440, maxChipTempTenths = 520)
        assertTrue(FastChargeController.isOverheated(hotBatt, cutoffCelsius = 44, previouslyOverheated = false))

        val warmBatt = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 420, maxChipTempTenths = 520)
        assertTrue(FastChargeController.isOverheated(warmBatt, cutoffCelsius = 44, previouslyOverheated = true))

        val cooledBatt = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 405, maxChipTempTenths = 520)
        assertFalse(FastChargeController.isOverheated(cooledBatt, cutoffCelsius = 44, previouslyOverheated = true))
    }

    @Test
    fun isOverheatedFollowsUserConfiguredBatteryCutoffAnd3DegreeHysteresis() {
        val batt420 = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 420)
        assertTrue(
            "Battery at 42.0°C must trip 42°C cutoff",
            FastChargeController.isOverheated(batt420, cutoffCelsius = 42, previouslyOverheated = false),
        )
        assertTrue(
            "Battery at 39.5°C (> 39.0°C cooldown) must stay overheated under 42°C cutoff",
            FastChargeController.isOverheated(
                batt420.copy(temperatureTenths = 395),
                cutoffCelsius = 42,
                previouslyOverheated = true,
            ),
        )
        assertFalse(
            "Battery cooled to 39.0°C (<= 39.0°C) should recover under 42°C cutoff",
            FastChargeController.isOverheated(
                batt420.copy(temperatureTenths = 390),
                cutoffCelsius = 42,
                previouslyOverheated = true,
            ),
        )
    }

    @Test
    fun fullChargeReleaseHoldsHysteresisUntil98Percent() {
        // 100% -> triggers full-charge release
        assertTrue(FastChargeController.nextFullChargeReleased(level = 100, previouslyReleased = false))
        // 99% after release -> stays released (2% hysteresis prevents 99 <-> 100 flapping)
        assertTrue(FastChargeController.nextFullChargeReleased(level = 99, previouslyReleased = true))
        // 99% fresh (never reached 100%) -> not released yet
        assertFalse(FastChargeController.nextFullChargeReleased(level = 99, previouslyReleased = false))
        // 98% -> resumes fast charge
        assertFalse(FastChargeController.nextFullChargeReleased(level = 98, previouslyReleased = true))

        // Policy helper blocks all 17 nodes whenever AC-charging, not overheated, and not full-charge released
        assertFalse(
            FastChargeController.shouldBlockChargingNodes(
                isAcCharging = true,
                isOverheated = false,
                fullChargeReleased = true,
            ),
        )
        assertFalse(
            FastChargeController.shouldBlockChargingNodes(
                isAcCharging = false,
                isOverheated = false,
                fullChargeReleased = false,
            ),
        )
        assertTrue(
            FastChargeController.shouldBlockChargingNodes(
                isAcCharging = true,
                isOverheated = false,
                fullChargeReleased = false,
            ),
        )
    }

    @Test
    fun formatChargingStatusShowsRestoredNoticeWhenFullReleased() {
        val withCurrent = FastChargeController.BatteryMetrics(
            currentMa = 850,
            voltageMv = 4450,
            powerWatts = 3.78,
            temperatureTenths = 335,
            maxChipTempTenths = 345,
        )
        assertEquals(
            "满电保护恢复原生温控 · 电量:100% 电流:850mA 电压:4.45V 功率:3.8W",
            FastChargeNotifier.formatChargingStatus(
                isScreenOn = true,
                isOverheated = false,
                level = 100,
                metrics = withCurrent,
                isFullReleased = true,
            ),
        )

        val noCurrent = FastChargeController.BatteryMetrics(0, 4423, 0.0, 333, 344)
        assertEquals(
            "满电保护恢复原生温控 · 电量:100% 电压:4.42V",
            FastChargeNotifier.formatChargingStatus(
                isScreenOn = true,
                isOverheated = false,
                level = 100,
                metrics = noCurrent,
                isFullReleased = true,
            ),
        )
    }

    @Test
    fun displayStateFullLifecycleUnpluggedToPluggedToScreenOffToScreenOnToUnplugged() {
        val sampleMetrics = FastChargeController.BatteryMetrics(
            currentMa = 4500,
            voltageMv = 9000,
            powerWatts = 40.5,
            temperatureTenths = 365,
            maxChipTempTenths = 410,
        )

        // 1. 未插电启动前台服务 -> 进入 HIDDEN 态，且随后 standby 轮询去重为 NO_OP
        assertEquals(
            FastChargeNotifier.TransitionAction.BIND_FOREGROUND_HIDDEN,
            FastChargeNotifier.onBindForeground(liveChannelAllowed = true),
        )
        assertEquals(FastChargeNotifier.DisplayState.HIDDEN, FastChargeNotifier.displayState)
        assertTrue(FastChargeNotifier.hiddenBoundToService)
        assertEquals(
            FastChargeNotifier.TransitionAction.NO_OP,
            FastChargeNotifier.onHide(hasService = true),
        )

        // 2. 插电亮屏充电 -> 切换为 LIVE 态，同一条通知更新实时功率；重复相同文案返回 NO_OP
        val activeText = FastChargeNotifier.formatChargingStatus(
            isScreenOn = true,
            isOverheated = false,
            level = 65,
            metrics = sampleMetrics,
        )
        assertEquals("电量:65% 电流:4500mA 电压:9.00V 功率:40.5W", activeText)
        assertEquals(
            FastChargeNotifier.TransitionAction.BIND_FOREGROUND_LIVE,
            FastChargeNotifier.onPostLive(activeText, "36.5°C", liveChannelAllowed = true, hasService = true),
        )
        assertEquals(FastChargeNotifier.DisplayState.LIVE, FastChargeNotifier.displayState)
        assertFalse(FastChargeNotifier.hiddenBoundToService)
        assertEquals(
            FastChargeNotifier.TransitionAction.NO_OP,
            FastChargeNotifier.onPostLive(activeText, "36.5°C", liveChannelAllowed = true, hasService = true),
        )

        // 3. 充电中即使息屏 -> 保持同一实时功率展示口径，不切换到息屏停充态，重复调用去重为 NO_OP
        val screenOffText = FastChargeNotifier.formatChargingStatus(
            isScreenOn = false,
            isOverheated = false,
            level = 66,
            metrics = sampleMetrics,
        )
        assertEquals("电量:66% 电流:4500mA 电压:9.00V 功率:40.5W", screenOffText)
        assertEquals(
            FastChargeNotifier.TransitionAction.BIND_FOREGROUND_LIVE,
            FastChargeNotifier.onPostLive(screenOffText, "36.5°C", liveChannelAllowed = true, hasService = true),
        )
        assertEquals(FastChargeNotifier.DisplayState.LIVE, FastChargeNotifier.displayState)
        assertEquals(
            FastChargeNotifier.TransitionAction.NO_OP,
            FastChargeNotifier.onPostLive(screenOffText, "36.5°C", liveChannelAllowed = true, hasService = true),
        )

        // 4. 亮屏恢复充电 -> 原地切回实时电流/电压/功率文案
        val screenOnResumeText = FastChargeNotifier.formatChargingStatus(
            isScreenOn = true,
            isOverheated = false,
            level = 68,
            metrics = sampleMetrics,
        )
        assertEquals(
            FastChargeNotifier.TransitionAction.BIND_FOREGROUND_LIVE,
            FastChargeNotifier.onPostLive(screenOnResumeText, "37.0°C", liveChannelAllowed = true, hasService = true),
        )
        assertEquals(screenOnResumeText, FastChargeNotifier.lastStatusText)
        assertEquals("37.0°C", FastChargeNotifier.lastSubText)

        // 5. 拔线：即使静态广播先于 Service 触发 NOTIFY_HIDDEN，Service 紧接着调用 onHide 仍会执行一次 BIND_FOREGROUND_HIDDEN 绑定前台，随后去重为 NO_OP
        assertEquals(
            FastChargeNotifier.TransitionAction.NOTIFY_HIDDEN,
            FastChargeNotifier.onHide(hasService = false),
        )
        assertEquals(FastChargeNotifier.DisplayState.HIDDEN, FastChargeNotifier.displayState)
        assertFalse(FastChargeNotifier.hiddenBoundToService)

        assertEquals(
            FastChargeNotifier.TransitionAction.BIND_FOREGROUND_HIDDEN,
            FastChargeNotifier.onHide(hasService = true),
        )
        assertTrue(FastChargeNotifier.hiddenBoundToService)
        assertNull(FastChargeNotifier.lastSubText)

        assertEquals(
            FastChargeNotifier.TransitionAction.NO_OP,
            FastChargeNotifier.onHide(hasService = true),
        )

        // 6. 用户关闭总开关 -> CANCEL_ALL 回到 NONE
        assertEquals(FastChargeNotifier.TransitionAction.CANCEL_ALL, FastChargeNotifier.onCancelAll())
        assertEquals(FastChargeNotifier.DisplayState.NONE, FastChargeNotifier.displayState)
    }

    @Test
    fun channelPolicyRespectsUserDisabledLiveChannelAndSafelyPlansHiddenChannelRotation() {
        // 1. 用户主动关闭「亮屏快充」可见通知类别时，onPostLive 必须返回 NO_OP，绝不绕过用户设置
        assertEquals(
            FastChargeNotifier.TransitionAction.NO_OP,
            FastChargeNotifier.onPostLive("电量:80%", "35.0°C", liveChannelAllowed = false, hasService = true),
        )
        assertEquals(FastChargeNotifier.DisplayState.NONE, FastChargeNotifier.displayState)

        // 2. 首次初始化（渠道尚不存在）-> 创建默认隐藏渠道，无待删旧渠道
        val initialPlan = FastChargeNotifier.planHiddenChannel(
            storedChannelId = null,
            existingImportance = null,
            rotations = 0,
            exhaustedInSession = false,
        )
        assertEquals(FastChargeNotifier.CHANNEL_HIDDEN_BASE, initialPlan.targetChannelId)
        assertTrue(initialPlan.createChannel)
        assertEquals(0, initialPlan.updatedRotations)
        assertNull(initialPlan.obsoleteChannelIdToDeleteAfterBind)
        assertFalse(initialPlan.useDegradedMinimalFallback)

        // 3. 隐藏渠道保持 IMPORTANCE_NONE -> 直接复用，不新建、不删除
        val keepPlan = FastChargeNotifier.planHiddenChannel(
            storedChannelId = FastChargeNotifier.CHANNEL_HIDDEN_BASE,
            existingImportance = NotificationManager.IMPORTANCE_NONE,
            rotations = 0,
            exhaustedInSession = false,
        )
        assertEquals(FastChargeNotifier.CHANNEL_HIDDEN_BASE, keepPlan.targetChannelId)
        assertFalse(keepPlan.createChannel)
        assertNull(keepPlan.obsoleteChannelIdToDeleteAfterBind)

        // 4. 隐藏渠道重要性被改高（未超上限）-> 先切新渠道 v3，并将旧渠道记入 obsoleteChannelIdToDeleteAfterBind 延后删除
        val rotatePlan = FastChargeNotifier.planHiddenChannel(
            storedChannelId = FastChargeNotifier.CHANNEL_HIDDEN_BASE,
            existingImportance = NotificationManager.IMPORTANCE_LOW,
            rotations = 0,
            exhaustedInSession = false,
        )
        assertEquals("fast_charge_service_hidden_v3", rotatePlan.targetChannelId)
        assertTrue(rotatePlan.createChannel)
        assertEquals(1, rotatePlan.updatedRotations)
        assertEquals(FastChargeNotifier.CHANNEL_HIDDEN_BASE, rotatePlan.obsoleteChannelIdToDeleteAfterBind)
        assertFalse(rotatePlan.useDegradedMinimalFallback)

        // 5. 达到最大重建上限 MAX_HIDDEN_CHANNEL_ROTATIONS (3) 后仍被改高 -> 退回固定最小兜底渠道，不再递增创建 v6+
        val exhaustedPlan = FastChargeNotifier.planHiddenChannel(
            storedChannelId = "fast_charge_service_hidden_v5",
            existingImportance = NotificationManager.IMPORTANCE_DEFAULT,
            rotations = FastChargeNotifier.MAX_HIDDEN_CHANNEL_ROTATIONS,
            exhaustedInSession = false,
        )
        assertEquals(FastChargeNotifier.CHANNEL_HIDDEN_FALLBACK_MIN, exhaustedPlan.targetChannelId)
        assertEquals(FastChargeNotifier.MAX_HIDDEN_CHANNEL_ROTATIONS, exhaustedPlan.updatedRotations)
        assertEquals("fast_charge_service_hidden_v5", exhaustedPlan.obsoleteChannelIdToDeleteAfterBind)
        assertTrue(exhaustedPlan.useDegradedMinimalFallback)
    }

    @Test
    fun parseNodeBlockReportHandlesAll17BlockedMixedOpenAndRestored() {
        // 1. All existing managed nodes (battery + AP/PA/thermal) at 0 / 0000 -> allChargingBlocked=true, anyNodeBlocked=true
        val blockedReport = FastChargeController.parseNodeBlockReport(
            """
            __UID__0
            0:/sys/class/power_supply/battery/temp
            0000:/sys/class/power_supply/battery/batt_temp
            0:/sys/class/sec/sec-ap-thermistor/temperature
            0:/sys/class/thermal
            __DONE__
            """.trimIndent(),
        )
        assertEquals(true, blockedReport?.allChargingBlocked)
        assertEquals(true, blockedReport?.anyNodeBlocked)

        // 2. Partial reset by thermal daemon (battery=0, sec-ap=644, thermal=755) -> allChargingBlocked=false, anyNodeBlocked=true
        val partialReport = FastChargeController.parseNodeBlockReport(
            """
            __UID__0
            0:/sys/class/power_supply/battery/temp
            0:/sys/class/power_supply/battery/batt_temp
            644:/sys/class/sec/sec-ap-thermistor/temperature
            755:/sys/class/thermal
            __DONE__
            """.trimIndent(),
        )
        assertEquals(false, partialReport?.allChargingBlocked)
        assertEquals(true, partialReport?.anyNodeBlocked)

        // 3. All restored -> allChargingBlocked=false, anyNodeBlocked=false
        val restoredReport = FastChargeController.parseNodeBlockReport(
            """
            __UID__0
            644:/sys/class/power_supply/battery/temp
            644:/sys/class/power_supply/battery/batt_temp
            644:/sys/class/sec/sec-ap-thermistor/temperature
            755:/sys/class/thermal
            __DONE__
            """.trimIndent(),
        )
        assertEquals(false, restoredReport?.allChargingBlocked)
        assertEquals(false, restoredReport?.anyNodeBlocked)

        // 4. Missing root or incomplete output -> null
        assertNull(FastChargeController.parseNodeBlockReport("__UID__10508\n__DONE__"))
        assertNull(FastChargeController.parseNodeBlockReport("__UID__0\n0:/sys/class/power_supply/battery/temp"))
    }

    @Test
    fun nodeSyncLoopTerminatesImmediatelyOnFailureAndMaxAttempts() {
        // 1. 失败场景（如无 Root 权限返回 appliedBlocked=null，与 targetBlocked=false 不相等）必须立刻退出循环
        assertFalse(
            BrightScreenFastChargeService.shouldContinueNodeSyncLoop(
                attempts = 1,
                syncFailed = true,
                syncScheduled = true,
                forceRestoreRequested = true,
                appliedBlocked = null,
                targetBlocked = false,
            ),
        )

        // 2. 达到最大轮次上限时即使状态尚未对齐也必须退出，杜绝无限自旋
        assertFalse(
            BrightScreenFastChargeService.shouldContinueNodeSyncLoop(
                attempts = 4,
                syncFailed = false,
                syncScheduled = false,
                forceRestoreRequested = false,
                appliedBlocked = true,
                targetBlocked = false,
            ),
        )

        // 3. 正常有 Root 且首轮成功对齐目标状态 -> 退出循环
        assertFalse(
            BrightScreenFastChargeService.shouldContinueNodeSyncLoop(
                attempts = 1,
                syncFailed = false,
                syncScheduled = false,
                forceRestoreRequested = false,
                appliedBlocked = true,
                targetBlocked = true,
            ),
        )

        // 4. 正常有 Root 但执行期间发生策略翻转（如切为强制恢复或状态未对齐）-> 继续同步一轮
        assertTrue(
            BrightScreenFastChargeService.shouldContinueNodeSyncLoop(
                attempts = 1,
                syncFailed = false,
                syncScheduled = false,
                forceRestoreRequested = true,
                appliedBlocked = true,
                targetBlocked = true,
            ),
        )
        assertTrue(
            BrightScreenFastChargeService.shouldContinueNodeSyncLoop(
                attempts = 1,
                syncFailed = false,
                syncScheduled = false,
                forceRestoreRequested = false,
                appliedBlocked = true,
                targetBlocked = false,
            ),
        )
    }

    @Test
    fun rootRetryCooldownAndErrorLogThrottleWorkWithoutMainThreadBlock() {
        // 1. 失败后在 60s 冷却期内且后台缓存尚未恢复 Root -> 保持冷却跳过重复尝试
        assertTrue(
            BrightScreenFastChargeService.isRootRetryInCooldown(
                rootSuccessState = false,
                lastRootFailureElapsed = 10_000L,
                nowElapsed = 45_000L,
                cachedHasRoot = false,
            ),
        )
        assertTrue(
            BrightScreenFastChargeService.isRootRetryInCooldown(
                rootSuccessState = false,
                lastRootFailureElapsed = 10_000L,
                nowElapsed = 45_000L,
                cachedHasRoot = null,
            ),
        )

        // 2. 后台周期探针已检测到用户重新授予 Root（cachedHasRoot == true）-> 立即解除冷却
        assertFalse(
            BrightScreenFastChargeService.isRootRetryInCooldown(
                rootSuccessState = false,
                lastRootFailureElapsed = 10_000L,
                nowElapsed = 20_000L,
                cachedHasRoot = true,
            ),
        )

        // 3. 超过 60s 冷却期 -> 解除冷却允许再探一次
        assertFalse(
            BrightScreenFastChargeService.isRootRetryInCooldown(
                rootSuccessState = false,
                lastRootFailureElapsed = 10_000L,
                nowElapsed = 70_000L,
                cachedHasRoot = false,
            ),
        )

        // 4. RootShell 异常日志 30s 节流保护
        assertTrue(RootShell.shouldLogSuFailure(nowElapsedMs = 5_000L, lastLoggedElapsedMs = 0L))
        assertFalse(RootShell.shouldLogSuFailure(nowElapsedMs = 20_000L, lastLoggedElapsedMs = 5_000L))
        assertTrue(RootShell.shouldLogSuFailure(nowElapsedMs = 35_000L, lastLoggedElapsedMs = 5_000L))
    }

    @Test
    fun activeChargingPolicyRequiresAcPluggedStopsAtFullOrUnpluggedAndRespectsAllowLiveReuse() {
        // 1. 未插电 / USB / 无线充 -> false（仅 AC 有线充电器激活）
        assertFalse(
            FastChargeController.isActivelyChargingForFastCharge(
                pluggedType = 0,
                level = 50,
                status = BatteryManager.BATTERY_STATUS_DISCHARGING,
                previouslyFullReleased = false,
            ),
        )
        assertFalse(
            FastChargeController.isActivelyChargingForFastCharge(
                pluggedType = BatteryManager.BATTERY_PLUGGED_USB,
                level = 50,
                status = BatteryManager.BATTERY_STATUS_CHARGING,
                previouslyFullReleased = false,
            ),
        )
        assertFalse(
            FastChargeController.isActivelyChargingForFastCharge(
                pluggedType = BatteryManager.BATTERY_PLUGGED_WIRELESS,
                level = 50,
                status = BatteryManager.BATTERY_STATUS_CHARGING,
                previouslyFullReleased = false,
            ),
        )
        // 2. AC 充电但已 100% 或系统报告 BATTERY_STATUS_FULL -> 方案 A 退出服务与通知 -> false
        assertFalse(
            FastChargeController.isActivelyChargingForFastCharge(
                pluggedType = BatteryManager.BATTERY_PLUGGED_AC,
                level = 100,
                status = BatteryManager.BATTERY_STATUS_CHARGING,
                previouslyFullReleased = false,
            ),
        )
        assertFalse(
            FastChargeController.isActivelyChargingForFastCharge(
                pluggedType = BatteryManager.BATTERY_PLUGGED_AC,
                level = 85,
                status = BatteryManager.BATTERY_STATUS_FULL,
                previouslyFullReleased = false,
            ),
        )
        // 3. 满电回落至 99%（滞回期内）仍停用 -> false；回落至 98% 重新激活 -> true
        assertFalse(
            FastChargeController.isActivelyChargingForFastCharge(
                pluggedType = BatteryManager.BATTERY_PLUGGED_AC,
                level = 99,
                status = BatteryManager.BATTERY_STATUS_CHARGING,
                previouslyFullReleased = true,
            ),
        )
        assertTrue(
            FastChargeController.isActivelyChargingForFastCharge(
                pluggedType = BatteryManager.BATTERY_PLUGGED_AC,
                level = 98,
                status = BatteryManager.BATTERY_STATUS_CHARGING,
                previouslyFullReleased = true,
            ),
        )

        // 4. 前次遗留过 LIVE 状态时，若以 allowLiveReuse = false 绑定前台契约（准备立即退出），绝不闪现可见通知
        FastChargeNotifier.onPostLive("电量:80%", "35.0°C", liveChannelAllowed = true, hasService = true)
        assertEquals(FastChargeNotifier.DisplayState.LIVE, FastChargeNotifier.displayState)
        assertEquals(
            FastChargeNotifier.TransitionAction.BIND_FOREGROUND_HIDDEN,
            FastChargeNotifier.onBindForeground(liveChannelAllowed = true, allowLiveReuse = false),
        )
        assertEquals(FastChargeNotifier.DisplayState.HIDDEN, FastChargeNotifier.displayState)
        assertNull(FastChargeNotifier.lastSubText)
    }

    @Test
    fun overheatAndFullChargeDoNotSpinNodeSyncOrChmodOnceRestored() {
        // 1. 刚进入过热/满电切换（appliedBlocked=true 或 forceRestore=true）-> 必须触发恢复
        assertTrue(
            BrightScreenFastChargeService.shouldScheduleNodeSync(
                desiredBlocked = false,
                forceRestore = true,
                appliedBlocked = true,
                needVerify = false,
            ),
        )
        // 2. 节点已恢复（appliedBlocked=false）且未到 15s/30s 周期复核点，过热期间也不再每 10s 进同步
        assertFalse(
            BrightScreenFastChargeService.shouldScheduleNodeSync(
                desiredBlocked = false,
                forceRestore = false,
                appliedBlocked = false,
                needVerify = false,
            ),
        )
        // 3. 到 15s/30s 复核周期时探测：若底层确实均为 0644 (anyBlocked=false)，绝不重复执行 chmod 恢复
        assertTrue(
            BrightScreenFastChargeService.shouldScheduleNodeSync(
                desiredBlocked = false,
                forceRestore = false,
                appliedBlocked = false,
                needVerify = true,
            ),
        )
        assertFalse(
            BrightScreenFastChargeService.shouldExecuteRestoreNodes(
                forceUnlock = false,
                appliedBlocked = false,
                anyBlocked = false,
                rootSuccessState = true,
            ),
        )
        // 4. 若外部守护进程再次将节点锁成 0000 (anyBlocked=true) 或为切换瞬间 (forceUnlock=true) -> 立即恢复
        assertTrue(
            BrightScreenFastChargeService.shouldExecuteRestoreNodes(
                forceUnlock = false,
                appliedBlocked = false,
                anyBlocked = true,
                rootSuccessState = true,
            ),
        )
        assertTrue(
            BrightScreenFastChargeService.shouldExecuteRestoreNodes(
                forceUnlock = true,
                appliedBlocked = false,
                anyBlocked = false,
                rootSuccessState = true,
            ),
        )
    }

    @Test
    fun armTriggerDecisionPreventsPingPongAndRootSuspensionSpins() {
        assertEquals(
            FastChargeScheduler.ArmTriggerDecision.CANCEL_ALL_SUSPENDED,
            FastChargeScheduler.decideArmTrigger(rootSuspended = true, plugged = false),
        )
        assertEquals(
            FastChargeScheduler.ArmTriggerDecision.CANCEL_IMMEDIATE_PLUGGED,
            FastChargeScheduler.decideArmTrigger(rootSuspended = false, plugged = true),
        )
        assertEquals(
            FastChargeScheduler.ArmTriggerDecision.SCHEDULE_ARM_UNPLUGGED,
            FastChargeScheduler.decideArmTrigger(rootSuspended = false, plugged = false),
        )
    }

    @Test
    fun lowCurrentKickTriggersOnlyAfterConfirmationAndRespectsCooldownAndCap() {
        // 1. 满足全部条件：AC 充电、未满电、未过热、已屏蔽、电量 < 85%、连续 2 次电流 1..999mA、无冷却 -> true
        assertTrue(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = false,
                level = 60,
                currentMa = 650,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = BrightScreenFastChargeService.LOW_CURRENT_KICK_CONFIRM_SAMPLES,
                consecutiveKicks = 0,
                lastKickElapsedMs = 0L,
                nowElapsedMs = 10_000L,
            ),
        )
        // 2. 仅单次低电流采样、电流为 0 或 >= 1000mA、电量 >= 85% -> 不触发
        assertFalse(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = false,
                level = 60,
                currentMa = 650,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 1,
                consecutiveKicks = 0,
                lastKickElapsedMs = 0L,
                nowElapsedMs = 10_000L,
            ),
        )
        assertFalse(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = false,
                level = 60,
                currentMa = 0,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 2,
                consecutiveKicks = 0,
                lastKickElapsedMs = 0L,
                nowElapsedMs = 10_000L,
            ),
        )
        assertFalse(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = false,
                level = 60,
                currentMa = BrightScreenFastChargeService.LOW_CURRENT_KICK_THRESHOLD_MA,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 2,
                consecutiveKicks = 0,
                lastKickElapsedMs = 0L,
                nowElapsedMs = 10_000L,
            ),
        )
        assertFalse(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = false,
                level = BrightScreenFastChargeService.LOW_CURRENT_KICK_LEVEL_LIMIT,
                currentMa = 500,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 2,
                consecutiveKicks = 0,
                lastKickElapsedMs = 0L,
                nowElapsedMs = 10_000L,
            ),
        )
        // 3. 过热、满电、非 AC、正在重协商或节点未锁死 -> 不触发
        assertFalse(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = false,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = false,
                level = 60,
                currentMa = 500,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 2,
                consecutiveKicks = 0,
                lastKickElapsedMs = 0L,
                nowElapsedMs = 10_000L,
            ),
        )
        assertFalse(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = true,
                appliedBlocked = true,
                kickInProgress = false,
                level = 60,
                currentMa = 500,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 2,
                consecutiveKicks = 0,
                lastKickElapsedMs = 0L,
                nowElapsedMs = 10_000L,
            ),
        )
        assertFalse(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = true,
                level = 60,
                currentMa = 500,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 2,
                consecutiveKicks = 0,
                lastKickElapsedMs = 0L,
                nowElapsedMs = 10_000L,
            ),
        )
        // 4. 60s 冷却期内不触发，满 60s 恢复可触发；连续触发达 3 次上限停止，防止插老旧 5V/1A 充电器无限自旋
        assertFalse(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = false,
                level = 60,
                currentMa = 500,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 2,
                consecutiveKicks = 1,
                lastKickElapsedMs = 10_000L,
                nowElapsedMs = 45_000L,
            ),
        )
        assertTrue(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = false,
                level = 60,
                currentMa = 500,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 2,
                consecutiveKicks = 1,
                lastKickElapsedMs = 10_000L,
                nowElapsedMs = 10_000L + BrightScreenFastChargeService.LOW_CURRENT_KICK_COOLDOWN_MS,
            ),
        )
        assertFalse(
            BrightScreenFastChargeService.shouldTriggerLowCurrentKick(
                isAcCharging = true,
                isFullChargeReleased = false,
                isOverheated = false,
                appliedBlocked = true,
                kickInProgress = false,
                level = 60,
                currentMa = 500,
                batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
                consecutiveLowSamples = 2,
                consecutiveKicks = BrightScreenFastChargeService.MAX_CONSECUTIVE_LOW_CURRENT_KICKS,
                lastKickElapsedMs = 10_000L,
                nowElapsedMs = 200_000L,
            ),
        )
    }

    @Test
    fun isOverheatedTripsOnChargerIcAndUsbPortHardLimitsWithHysteresis() {
        // 1. Battery cool (38.0°C), Charger IC reaches 56.0°C -> trips overheat
        val hotChgMetrics = FastChargeController.BatteryMetrics(
            currentMa = 3000,
            voltageMv = 4200,
            powerWatts = 12.6,
            temperatureTenths = 380,
            chgTempTenths = 560,
            usbTempTenths = 390,
        )
        assertTrue(FastChargeController.isOverheated(hotChgMetrics, cutoffCelsius = 44, previouslyOverheated = false))
        // Stays overheated at 53.0°C (> 52.0°C cooldown)
        assertTrue(
            FastChargeController.isOverheated(
                hotChgMetrics.copy(chgTempTenths = 530),
                cutoffCelsius = 44,
                previouslyOverheated = true,
            ),
        )
        // Recovers at 52.0°C (<= 52.0°C cooldown)
        assertFalse(
            FastChargeController.isOverheated(
                hotChgMetrics.copy(chgTempTenths = 520),
                cutoffCelsius = 44,
                previouslyOverheated = true,
            ),
        )

        // 2. Battery cool (38.0°C), USB port reaches 47.0°C -> trips overheat
        val hotUsbMetrics = FastChargeController.BatteryMetrics(
            currentMa = 3000,
            voltageMv = 4200,
            powerWatts = 12.6,
            temperatureTenths = 380,
            chgTempTenths = 450,
            usbTempTenths = 470,
        )
        assertTrue(FastChargeController.isOverheated(hotUsbMetrics, cutoffCelsius = 44, previouslyOverheated = false))
        // Stays overheated at 45.0°C (> 44.0°C cooldown)
        assertTrue(
            FastChargeController.isOverheated(
                hotUsbMetrics.copy(usbTempTenths = 450),
                cutoffCelsius = 44,
                previouslyOverheated = true,
            ),
        )
        // Recovers at 44.0°C (<= 44.0°C cooldown)
        assertFalse(
            FastChargeController.isOverheated(
                hotUsbMetrics.copy(usbTempTenths = 440),
                cutoffCelsius = 44,
                previouslyOverheated = true,
            ),
        )
    }
}
