package com.weitiao.tuning.tuning

import android.app.NotificationManager
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
    fun isOverheatedTripsOnBatteryOrChipAndHoldsUntilCooledDown() {
        // Case 1: Battery is only 38.0°C, but Charger IC / AP chip hits 50.0°C -> MUST trip immediately!
        val hotChipMetrics = FastChargeController.BatteryMetrics(
            currentMa = 3000,
            voltageMv = 4200,
            powerWatts = 12.6,
            temperatureTenths = 380,
            maxChipTempTenths = 500,
        )
        assertTrue(
            "Chip at 50.0°C must trigger thermal cutoff even when battery is 38.0°C",
            FastChargeController.isOverheated(hotChipMetrics, cutoffCelsius = 44, previouslyOverheated = false),
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
        val hotBatt = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 440, maxChipTempTenths = 450)
        assertTrue(FastChargeController.isOverheated(hotBatt, cutoffCelsius = 44, previouslyOverheated = false))

        val warmBatt = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 420, maxChipTempTenths = 450)
        assertTrue(FastChargeController.isOverheated(warmBatt, cutoffCelsius = 44, previouslyOverheated = true))

        val cooledBatt = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 405, maxChipTempTenths = 450)
        assertFalse(FastChargeController.isOverheated(cooledBatt, cutoffCelsius = 44, previouslyOverheated = true))
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

        // 3. 充电中息屏 -> 仍在 LIVE 态原地更新为「息屏安全充」，重复调用去重为 NO_OP
        val screenOffText = FastChargeNotifier.formatChargingStatus(
            isScreenOn = false,
            isOverheated = false,
            level = 66,
            metrics = sampleMetrics,
        )
        assertEquals("息屏安全充 · 电量:66%", screenOffText)
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
    fun parseNodeBlockReportHandlesAllBlockedMixedOpenAndSocLocked() {
        // 1. All existing charging nodes 0000, SoC nodes 0644/0755 -> blocked=true, any=true
        val blockedReport = FastChargeController.parseNodeBlockReport(
            """
            __UID__0
            0:/sys/class/power_supply/battery/temp
            0000:/sys/class/power_supply/battery/batt_temp
            644:/sys/class/sec/sec-ap-thermistor/temperature
            755:/sys/class/thermal
            __DONE__
            """.trimIndent(),
        )
        assertEquals(true, blockedReport?.allChargingBlocked)
        assertEquals(true, blockedReport?.anyNodeBlocked)

        // 2. Partial reset by thermal daemon (temp=644, batt_temp=0) -> allChargingBlocked=false, anyNodeBlocked=true
        val partialReport = FastChargeController.parseNodeBlockReport(
            """
            __UID__0
            644:/sys/class/power_supply/battery/temp
            0:/sys/class/power_supply/battery/batt_temp
            644:/sys/class/sec/sec-ap-thermistor/temperature
            __DONE__
            """.trimIndent(),
        )
        assertEquals(false, partialReport?.allChargingBlocked)
        assertEquals(true, partialReport?.anyNodeBlocked)

        // 3. SoC safety node accidentally locked -> allChargingBlocked=false, anyNodeBlocked=true
        val socLockedReport = FastChargeController.parseNodeBlockReport(
            """
            __UID__0
            0:/sys/class/power_supply/battery/temp
            0:/sys/class/sec/sec-ap-thermistor/temperature
            __DONE__
            """.trimIndent(),
        )
        assertEquals(false, socLockedReport?.allChargingBlocked)
        assertEquals(true, socLockedReport?.anyNodeBlocked)

        // 4. All restored -> allChargingBlocked=false, anyNodeBlocked=false
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

        // 5. Missing root or incomplete output -> null
        assertNull(FastChargeController.parseNodeBlockReport("__UID__10508\n__DONE__"))
        assertNull(FastChargeController.parseNodeBlockReport("__UID__0\n0:/sys/class/power_supply/battery/temp"))
    }
}
