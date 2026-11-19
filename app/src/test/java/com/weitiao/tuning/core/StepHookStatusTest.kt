package com.weitiao.tuning.core

import com.weitiao.tuning.WechatHooks
import com.weitiao.tuning.ui.screens.STEP_TARGET_PRESETS
import com.weitiao.tuning.ui.screens.WechatStepPreview
import com.weitiao.tuning.ui.screens.WechatStepState
import com.weitiao.tuning.ui.screens.buildWechatStepStateReadCommand
import com.weitiao.tuning.ui.screens.formatStepEventRecency
import com.weitiao.tuning.ui.screens.parseStepTargetInput
import com.weitiao.tuning.ui.screens.stepAfterWindowLabel
import com.weitiao.tuning.ui.screens.stepBackendLabel
import com.weitiao.tuning.ui.screens.stepCompactLoadLabel
import com.weitiao.tuning.ui.screens.stepConfigSyncStatusLine
import com.weitiao.tuning.ui.screens.stepDashboardStatusText
import com.weitiao.tuning.ui.screens.stepDiagnosticsCardSubtitle
import com.weitiao.tuning.ui.screens.stepHardCapWarning
import com.weitiao.tuning.ui.screens.stepHeartbeatSummary
import com.weitiao.tuning.ui.screens.stepHookDetail
import com.weitiao.tuning.ui.screens.stepMainTodaySnapshotLine
import com.weitiao.tuning.ui.screens.stepModeLabel
import com.weitiao.tuning.ui.screens.stepModeOptionLabel
import com.weitiao.tuning.ui.screens.stepModeSummary
import com.weitiao.tuning.ui.screens.stepMultiplierDisplay
import com.weitiao.tuning.ui.screens.stepParameterCardTitle
import com.weitiao.tuning.ui.screens.stepPreviewSummary
import com.weitiao.tuning.ui.screens.stepRestartGuidanceText
import com.weitiao.tuning.ui.screens.stepRuntimeStatusLines
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class StepHookStatusTest {
    @Test fun heartbeatBoundariesAndUnknownRoot() {
        val alive = 1_000_000L
        assertEquals(HeartbeatHealth.HEALTHY, heartbeatHealth(true, alive, alive + 299_999L))
        assertEquals(HeartbeatHealth.STALE, heartbeatHealth(true, alive, alive + 300_000L))
        assertEquals(HeartbeatHealth.STALE, heartbeatHealth(true, alive, alive + 899_999L))
        assertEquals(HeartbeatHealth.DEAD, heartbeatHealth(true, alive, alive + 900_000L))
        assertEquals(HeartbeatHealth.UNKNOWN, heartbeatHealth(false, alive, alive))
        assertEquals(HeartbeatHealth.UNKNOWN, heartbeatHealth(true, 0L, alive))
        assertEquals(HeartbeatHealth.UNKNOWN, heartbeatHealth(true, alive, alive - 1L))
    }

    @Test fun heartbeatDoesNotImplyStepEventOrModification() {
        val status = StepHookStatus(hookAliveAt = 100L, hookStatus = "LOADED")
        assertEquals(HeartbeatHealth.HEALTHY, status.heartbeatHealth(true, 101L))
        assertFalse(status.hasStepEvent)
        assertFalse(status.copy(lastOriginal = 10, lastEffective = 20).hasStepEvent)
        assertTrue(status.copy(lastEventAt = 100L, lastOriginal = 10, lastEffective = 10).hasStepEvent)
    }

    @Test fun valuesIncludeStringsLongsAndSyntheticMarker() {
        val status = StepHookStatus.fromValues(
            mapOf("hook_status" to "WAITING_FOR_SENSOR", "hook_version" to "1", "wechat_version" to "8",
                "last_error" to "old error", "last_backend" to "SENSOR", "backend_reason" to "unverified fallback"),
            mapOf("hook_alive_at" to 90L, "last_original" to 100L, "last_effective" to 110L,
                "last_event_at" to 80L, "last_error_at" to 70L),
            mapOf("last_event_synthetic" to true),
        )
        assertEquals("WAITING_FOR_SENSOR", status.hookStatus)
        assertEquals("1", status.hookVersion)
        assertEquals("8", status.wechatVersion)
        assertEquals("SENSOR", status.lastBackend)
        assertEquals("unverified fallback", status.backendReason)
        assertEquals(100L, status.lastOriginal)
        assertEquals(110L, status.lastEffective)
        assertEquals(70L, status.lastErrorAt)
        assertTrue(status.lastEventSynthetic)
        assertTrue(status.hasStepEvent)
        assertEquals(HeartbeatHealth.HEALTHY, status.heartbeatHealth(true, 100L))
    }

    @Test fun nonPlanModePreviewReadsDeliveredLeavesRealUnavailableAndIgnoresStalePlan() {
        val today = 20261118L
        val status = StepHookStatus()

        // PLAN mode validates plan_day + plan_gen and populates real, effective, target.
        val planCfg = TuningConfig(stepEnabled = true, stepMode = TuningConfig.MODE_PLAN, stepGeneration = 3L)
        val planState = WechatStepState(
            longs = mapOf(
                "plan_day" to today,
                "plan_gen" to 3L,
                "step_real_today" to 450L,
                WechatHooks.KEY_STATE_TODAY to today,
                WechatHooks.KEY_STATE_DELIVERED to 8_500L,
                "daily_target" to 21_000L,
            ),
            status = status,
        )
        assertEquals(WechatStepPreview(450L, 8_500L, 21_000L), planState.preview(planCfg, today))

        // Switching to MULTIPLIER before WeChat processes an event must NOT show stale PLAN snapshot.
        val multCfg = planCfg.copy(stepMode = TuningConfig.MODE_MULTIPLIER, stepAuto = false, stepGeneration = 4L)
        assertNull(planState.preview(multCfg, today))

        // Even if step_real_today residue remains on disk, non-PLAN mode ignores it and returns real = null.
        for (mode in listOf(TuningConfig.MODE_MULTIPLIER, TuningConfig.MODE_LEGACY_AUTO)) {
            val cfg = TuningConfig(stepEnabled = true, stepMode = mode, stepAuto = mode != TuningConfig.MODE_MULTIPLIER, stepGeneration = 4L)
            val nonPlanState = WechatStepState(
                longs = mapOf(
                    WechatHooks.KEY_STATE_TODAY to today,
                    WechatHooks.KEY_STATE_DELIVERED to 9_200L,
                    "plan_day" to 0L,
                    "plan_gen" to 4L,
                    "step_real_today" to 450L,
                    "daily_target" to 21_000L,
                ),
                status = status,
            )
            assertEquals(WechatStepPreview(real = null, effective = 9_200L, target = null), nonPlanState.preview(cfg, today))
        }

        // Switching back to PLAN before WeChat processes an event (plan_day = 0L) returns null.
        val nonPlanSnapshot = WechatStepState(
            longs = mapOf(
                WechatHooks.KEY_STATE_TODAY to today,
                WechatHooks.KEY_STATE_DELIVERED to 9_200L,
                "plan_day" to 0L,
                "plan_gen" to 4L,
            ),
            status = status,
        )
        assertNull(nonPlanSnapshot.preview(planCfg.copy(stepGeneration = 5L), today))
    }

    @Test fun engineModeSwitchAndCrossDayPreventStalePreview() {
        val storeMap = mutableMapOf<String, Long>()
        var nowMillis = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 18, 10, 0, 0)
        }.timeInMillis
        val today = 20261118L
        val store = object : WechatHooks.StepStateStore {
            override fun getInt(key: String, default: Int) = storeMap[key]?.toInt() ?: default
            override fun getLong(key: String, default: Long) = storeMap[key] ?: default
            override fun getFloat(key: String, default: Float) = storeMap[key]?.toFloat() ?: default
            override fun getBoolean(key: String, default: Boolean) = (storeMap[key] ?: 0L) != 0L
            override fun savePlan(values: Map<String, Long>) {
                values.forEach { (k, v) -> storeMap[k] = v }
                if (!values.containsKey("step_real_today")) storeMap.remove("step_real_today")
                if (!values.containsKey("daily_target")) storeMap.remove("daily_target")
            }
            override fun saveState(
                todayKey: Int,
                baseHardwareRaw: Float,
                lastHardwareRaw: Float,
                currentDelivered: Long,
                lastOutput: Float,
                baselineEstablished: Boolean,
            ) {
                storeMap[WechatHooks.KEY_STATE_TODAY] = todayKey.toLong()
                storeMap[WechatHooks.KEY_STATE_DELIVERED] = currentDelivered
            }
        }

        val engine = WechatHooks.WechatStepEngine(stateStore = store, clock = { nowMillis }) { _, _ -> }
        val planCfg = TuningConfig(
            stepEnabled = true,
            stepMode = TuningConfig.MODE_PLAN,
            stepTarget = 20_000,
            stepJitter = 0,
            stepWindowStart = 420,
            stepWindowEnd = 1230,
            stepGeneration = 1L,
        )
        engine.process(floatArrayOf(1_000f), isDetector = false, cfg = planCfg, isLiveWechatHook = true)
        engine.process(floatArrayOf(1_200f), isDetector = false, cfg = planCfg, isLiveWechatHook = true)
        val planPreview = WechatStepState(storeMap.toMap(), StepHookStatus()).preview(planCfg, today)
        assertNotNull(planPreview)
        assertEquals(200L, planPreview!!.real)

        // Switch to MULTIPLIER: before WeChat processes an event, preview must be null.
        val multCfg = planCfg.copy(stepMode = TuningConfig.MODE_MULTIPLIER, stepAuto = false, stepMultiplier = 5, stepGeneration = 2L)
        assertNull(WechatStepState(storeMap.toMap(), StepHookStatus()).preview(multCfg, today))

        // After WeChat processes a MULTIPLIER event, preview shows delivered steps with real = null.
        engine.process(floatArrayOf(1_300f), isDetector = false, cfg = multCfg, isLiveWechatHook = true)
        val multPreview = WechatStepState(storeMap.toMap(), StepHookStatus()).preview(multCfg, today)
        assertNotNull(multPreview)
        assertNull(multPreview!!.real)
        assertNull(multPreview.target)
        assertTrue(multPreview.effective >= planPreview.effective)

        // Cross-day: yesterday's snapshot must return null for both PLAN and MULTIPLIER.
        val tomorrow = 20261119L
        assertNull(WechatStepState(storeMap.toMap(), StepHookStatus()).preview(multCfg, tomorrow))
        assertNull(WechatStepState(storeMap.toMap(), StepHookStatus()).preview(planCfg.copy(stepGeneration = 2L), tomorrow))
    }

    @Test fun stepHookDetailDistinguishesMissingRootFromMissingStateFile() {
        assertEquals(
            "Hook 状态未知；需要 Root 读取微信状态。",
            stepHookDetail(status = null, hasRoot = false, isZh = true),
        )
        assertEquals(
            "Hook 状态未知；已获得 Root，但尚未生成或未能读取微信状态文件。",
            stepHookDetail(status = null, hasRoot = true, isZh = true),
        )
    }

    @Test fun wechatStepStateReadCommandIncludesMirrorAndRootMountFallback() {
        val cmd = buildWechatStepStateReadCommand()
        assertTrue(cmd.contains("/data_mirror/data_ce/null/"))
        assertTrue(cmd.contains("/data/user/"))
        assertTrue(cmd.contains("nsenter -t 1 -m"))
    }

    @Test fun stepHookDetailShowsActualWechatConfigAndLocalOnlyWarning() {
        val status = StepHookStatus.fromValues(
            strings = mapOf(
                "hook_status" to "LOADED",
                "hook_version" to "1.6.0",
                "wechat_version" to "8.0.80/3200",
                "last_backend" to "SENSOR",
                "backend_reason" to "精确模式当前仅支持每日计划，已回退传感器",
                "config_mode" to "LEGACY_AUTO",
                "config_backend" to "EXACT",
            ),
            longs = mapOf(
                "hook_alive_at" to 1_794_993_322_397L,
                "config_generation" to 23L,
            ),
            booleans = emptyMap(),
        )
        assertEquals("LEGACY_AUTO", status.configMode)
        assertEquals("EXACT", status.configBackend)
        assertEquals(23L, status.configGeneration)

        val expected = TuningConfig(
            stepEnabled = true,
            stepMode = TuningConfig.MODE_PLAN,
            stepBackend = TuningConfig.BACKEND_EXACT,
            stepGeneration = 32L,
        )
        assertFalse(status.isConfigApplied(expected))

        val detail = stepHookDetail(
            status = status,
            hasRoot = true,
            isZh = true,
            expectedConfig = expected,
            remoteSynced = false,
        )
        assertTrue(detail.contains("微信实际读取：mode=LEGACY_AUTO · backend=EXACT · gen=23"))
        assertTrue(detail.contains("仅本地保存，尚未同步到 LSPosed（本地：mode=PLAN · backend=EXACT · gen=32）"))

        // Synced to LSPosed, but WeChat still reports older config generation/mode.
        val pendingWechatDetail = stepHookDetail(
            status = status,
            hasRoot = true,
            isZh = true,
            expectedConfig = expected,
            remoteSynced = true,
        )
        assertTrue(pendingWechatDetail.contains("已同步到 LSPosed，微信待加载最新配置（本地：mode=PLAN · backend=EXACT · gen=32）"))
        assertFalse(pendingWechatDetail.contains("仅本地保存"))

        // WeChat loaded latest config, but today's plan has not yet rebuilt on a step read.
        val syncedStatus = status.copy(configMode = TuningConfig.MODE_PLAN, configGeneration = 32L)
        assertTrue(syncedStatus.isConfigApplied(expected))
        val today = 20261119L
        val planNotUpdatedState = WechatStepState(
            longs = mapOf("plan_day" to today, "plan_gen" to 31L),
            status = syncedStatus,
        )
        assertFalse(planNotUpdatedState.isTodayPlanUpdated(expected, today))
        val waitingReadDetail = stepHookDetail(
            status = syncedStatus,
            hasRoot = true,
            isZh = true,
            expectedConfig = expected,
            remoteSynced = true,
            planUpdated = planNotUpdatedState.isTodayPlanUpdated(expected, today),
        )
        assertTrue(waitingReadDetail.contains("最新配置已加载，等待下一次计步读取"))
        assertFalse(waitingReadDetail.contains("已同步到 LSPosed，微信待加载最新配置"))

        // Fully synced and today's plan updated -> none of the 4 sync warnings appear.
        val planUpdatedState = WechatStepState(
            longs = mapOf("plan_day" to today, "plan_gen" to 32L),
            status = syncedStatus,
        )
        assertTrue(planUpdatedState.isTodayPlanUpdated(expected, today))
        val syncedDetail = stepHookDetail(
            status = syncedStatus,
            hasRoot = true,
            isZh = true,
            expectedConfig = expected,
            remoteSynced = true,
            planUpdated = planUpdatedState.isTodayPlanUpdated(expected, today),
        )
        assertFalse(syncedDetail.contains("仅本地保存"))
        assertFalse(syncedDetail.contains("已同步到 LSPosed，微信待加载最新配置"))
        assertFalse(syncedDetail.contains("最新配置已加载，等待下一次计步读取"))
        assertFalse(syncedDetail.contains("配置同步状态未知"))
        assertTrue(syncedDetail.contains("微信实际读取：mode=PLAN · backend=EXACT · gen=32"))

        // Unknown sync state variants.
        val missingRemoteDetail = stepHookDetail(status = null, hasRoot = true, isZh = true, expectedConfig = expected, remoteSynced = false)
        assertTrue(missingRemoteDetail.contains("仅本地保存，尚未同步到 LSPosed"))
        assertTrue(stepHookDetail(status = syncedStatus, hasRoot = true, isZh = true, expectedConfig = expected, remoteSynced = null).contains("配置同步状态未知"))
        assertTrue(stepHookDetail(status = null, hasRoot = true, isZh = true, expectedConfig = expected, remoteSynced = true).contains("配置同步状态未知"))
        assertTrue(stepConfigSyncStatusLine(status.copy(configGeneration = null), hasRoot = true, isZh = true, expectedConfig = expected, remoteSynced = true).contains("配置同步状态未知"))
    }

    @Test fun exactVerificationAndPendingWordingAppearsInDiagnostics() {
        val now = 1_795_081_693_000L
        val verifiedReason = "Play 版微信 8.0.80 / 3200 已通过真机验证：计划重建、首读无突跳、窗口内渐进增长。"
        val exactStatus = StepHookStatus.fromValues(
            strings = mapOf(
                "hook_status" to "LOADED",
                "hook_version" to "1.6.0",
                "wechat_version" to "8.0.80/3200",
                "last_backend" to TuningConfig.BACKEND_EXACT,
                "backend_reason" to verifiedReason,
                "config_mode" to TuningConfig.MODE_PLAN,
                "config_backend" to TuningConfig.BACKEND_EXACT,
            ),
            longs = mapOf(
                "hook_alive_at" to now,
                "last_event_at" to now,
                "last_original" to 8_270L,
                "last_effective" to 8_583L,
                "config_generation" to 68L,
            ),
            booleans = emptyMap(),
        )
        val exactDetail = stepHookDetail(exactStatus, hasRoot = true, isZh = true)
        assertTrue(exactDetail.contains(verifiedReason))
        assertTrue(exactDetail.contains("上传入口、服务端排行榜及窗口结束后的行为待验证"))
        assertFalse(exactDetail.contains("尚未真机运行验证"))

        // Legacy in-memory string from older process is also normalized out.
        val legacyExactDetail = stepHookDetail(
            exactStatus.copy(backendReason = "已适配 Play 微信 8.0.80/3200；APK摘要及实例方法校验通过（尚未真机运行验证）"),
            hasRoot = true,
            isZh = true,
        )
        assertTrue(legacyExactDetail.contains(verifiedReason))
        assertFalse(legacyExactDetail.contains("尚未真机运行验证"))

        val sensorDetail = stepHookDetail(
            exactStatus.copy(lastBackend = TuningConfig.BACKEND_SENSOR, backendReason = "精确模式当前仅支持每日计划，已回退传感器"),
            hasRoot = true,
            isZh = true,
        )
        assertFalse(sensorDetail.contains("上传入口、服务端排行榜及窗口结束后的行为待验证"))
    }

    @Test fun chineseLabelsAndMultiplierDisplayAreLocalized() {
        assertEquals("自动", stepBackendLabel(TuningConfig.BACKEND_AUTO, true))
        assertEquals("精确", stepBackendLabel(TuningConfig.BACKEND_EXACT, true))
        assertEquals("传感器", stepBackendLabel(TuningConfig.BACKEND_SENSOR, true))
        assertEquals("Auto", stepBackendLabel(TuningConfig.BACKEND_AUTO, false))
        assertEquals("Exact", stepBackendLabel(TuningConfig.BACKEND_EXACT, false))
        assertEquals("Sensor", stepBackendLabel(TuningConfig.BACKEND_SENSOR, false))

        assertEquals("每日计划", stepModeLabel(TuningConfig.MODE_PLAN, true))
        assertEquals("真实步数倍率", stepModeLabel(TuningConfig.MODE_MULTIPLIER, true))
        assertEquals("旧版自动", stepModeLabel(TuningConfig.MODE_LEGACY_AUTO, true))

        assertEquals("自然增长", stepAfterWindowLabel(TuningConfig.AFTER_NATURAL, true))
        assertEquals("冻结", stepAfterWindowLabel(TuningConfig.AFTER_FREEZE, true))

        assertEquals("每日计划目标", stepParameterCardTitle(TuningConfig.MODE_PLAN, true))
        assertEquals("真实步数倍率", stepParameterCardTitle(TuningConfig.MODE_MULTIPLIER, true))
        assertEquals("旧版自动倍率", stepParameterCardTitle(TuningConfig.MODE_LEGACY_AUTO, true))
        assertNotEquals("真实步数倍率", stepParameterCardTitle(TuningConfig.MODE_LEGACY_AUTO, true))
        assertEquals("Daily Plan Target", stepParameterCardTitle(TuningConfig.MODE_PLAN, false))
        assertEquals("Real Step Multiplier", stepParameterCardTitle(TuningConfig.MODE_MULTIPLIER, false))
        assertEquals("Legacy Auto Multiplier", stepParameterCardTitle(TuningConfig.MODE_LEGACY_AUTO, false))

        assertEquals("3倍", stepMultiplierDisplay(3, true))
        assertEquals("3x", stepMultiplierDisplay(3, false))

        val multCfg = TuningConfig(stepMode = TuningConfig.MODE_MULTIPLIER, stepMultiplier = 3, stepMaximum = 28_000)
        assertTrue(stepModeSummary(multCfg, true).contains("真实步数倍率 · 3倍 · 硬上限 28000"))
    }

    @Test fun hardCapWarningTriggersOnlyWhenVariedTargetCanExceedHardCapWithoutModifyingCap() {
        // 7,000 + 10% = 7,700 <= 28,000 -> no warning
        assertNull(stepHardCapWarning(target = 7_000, jitter = 10, hardCap = 28_000, isZh = true))
        // Exact boundary: 20,000 + 10% = 22,000 <= 22,000 -> no warning
        assertNull(stepHardCapWarning(target = 20_000, jitter = 10, hardCap = 22_000, isZh = true))
        // Exceeds hard cap: 26,000 + 10% = 28,600 > 28,000 -> warn
        val warn = stepHardCapWarning(target = 26_000, jitter = 10, hardCap = 28_000, isZh = true)
        assertNotNull(warn)
        assertTrue(warn!!.contains("目标受上限限制"))
        assertTrue(warn.contains("28,000") || warn.contains("28000"))

        // Config itself keeps original hard cap when normalized/saved unchanged
        val cfg = TuningConfig(stepEnabled = true, stepMode = TuningConfig.MODE_PLAN, stepTarget = 26_000, stepJitter = 10, stepMaximum = 28_000, stepGeneration = 32L)
        val saved = cfg.prepareForSave(cfg)
        assertEquals(28_000, saved.stepMaximum)
        assertEquals(32L, saved.stepGeneration)
    }

    @Test fun runtimeStatusAndDetailsSeparateLoadReadUploadAndExactVsSensorLabels() {
        val now = 1_795_002_130_000L
        val exactStatus = StepHookStatus.fromValues(
            strings = mapOf(
                "hook_status" to "LOADED",
                "hook_version" to "1.6.0",
                "wechat_version" to "8.0.80/3200",
                "last_backend" to "EXACT",
                "backend_reason" to "已适配 Play 微信 8.0.80/3200；APK摘要及实例方法校验通过",
                "config_mode" to "PLAN",
                "config_backend" to "EXACT",
            ),
            longs = mapOf(
                "hook_alive_at" to now - 5_000L,
                "last_event_at" to now - 2_000L,
                "last_original" to 7_500L,
                "last_effective" to 7_500L,
                "config_generation" to 32L,
            ),
            booleans = mapOf("last_event_synthetic" to false),
        )

        val (loadLine, readLine, uploadLine) = stepRuntimeStatusLines(exactStatus, hasRoot = true, nowMillis = now, isZh = true)
        assertTrue(loadLine.contains("模块已加载"))
        assertFalse(loadLine.contains("修改成功"))
        assertTrue(readLine.contains("最近读取已执行"))
        assertFalse(readLine.contains("最近计步事件已处理"))
        assertTrue(readLine.contains("微信原始业务步数 7500 → 生效步数 7500"))
        assertFalse(readLine.contains("实际走路步数"))
        assertFalse(readLine.contains("传感器累计原始"))
        assertEquals("上报验证：尚未采集", uploadLine)

        val exactDetail = stepHookDetail(exactStatus, hasRoot = true, isZh = true)
        assertTrue(exactDetail.contains("Hook：LOADED"))
        assertTrue(exactDetail.contains("最近读取已执行"))
        assertFalse(exactDetail.contains("最近计步事件已处理"))
        assertTrue(exactDetail.contains("微信原始业务步数 7500 → 生效步数 7500"))
        assertTrue(exactDetail.contains("mode=PLAN · backend=EXACT · gen=32"))

        val exactPreview = stepPreviewSummary(WechatStepPreview(7_500L, 7_500L, 6_947L), exactStatus, hasRoot = true, isZh = true)
        assertTrue(exactPreview.contains("微信原始业务步数"))
        assertTrue(exactPreview.contains("生效步数"))
        assertTrue(exactPreview.contains("不代表服务器已接受"))

        // SENSOR backend keeps cumulative sensor terminology and uses "最近计步事件已处理"
        val sensorStatus = exactStatus.copy(
            lastBackend = TuningConfig.BACKEND_SENSOR,
            lastOriginal = 1_000L,
            lastEffective = 3_000L,
            lastEventSynthetic = false,
        )
        val (_, sensorReadLine, sensorUpload) = stepRuntimeStatusLines(sensorStatus, hasRoot = true, nowMillis = now, isZh = true)
        assertTrue(sensorReadLine.contains("最近计步事件已处理"))
        assertFalse(sensorReadLine.contains("最近读取已执行"))
        assertTrue(sensorReadLine.contains("传感器累计原始值 1000 → 输出值 3000"))
        assertEquals("上报验证：尚未采集", sensorUpload)

        val sensorDetail = stepHookDetail(sensorStatus, hasRoot = true, isZh = true)
        assertTrue(sensorDetail.contains("最近计步事件已处理"))
        assertFalse(sensorDetail.contains("最近读取已执行"))
        assertTrue(sensorDetail.contains("传感器累计原始值 1000 → 输出值 3000"))

        // Heartbeat alone must not claim step modification or server acceptance
        val heartbeatOnly = exactStatus.copy(lastEventAt = 0L, lastOriginal = null, lastEffective = null)
        val (hbLoad, hbRead, hbUpload) = stepRuntimeStatusLines(heartbeatOnly, hasRoot = true, nowMillis = now, isZh = true)
        assertTrue(hbLoad.contains("模块已加载"))
        assertTrue(hbRead.contains("尚未观察到读取事件"))
        assertEquals("上报验证：尚未采集", hbUpload)
        assertEquals("模块已加载", stepCompactLoadLabel(heartbeatOnly, true, HeartbeatHealth.HEALTHY, true))
        assertTrue(stepHeartbeatSummary(HeartbeatHealth.HEALTHY, true).contains("模块已加载"))
        assertFalse(stepHeartbeatSummary(HeartbeatHealth.HEALTHY, true).contains("修改成功"))
    }

    @Test fun dashboardStatusIncludesEventTimeAndRecencyForExactAndSensor() {
        val tz = java.util.TimeZone.getTimeZone("GMT+11:00")
        fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
            Calendar.getInstance(tz).apply {
                clear()
                set(year, month, day, hour, minute, second)
            }.timeInMillis

        val now = ts(2026, Calendar.NOVEMBER, 19, 18, 32, 20)
        assertEquals("18:32，刚刚", formatStepEventRecency(ts(2026, Calendar.NOVEMBER, 19, 18, 32, 0), now, isZh = true, timeZone = tz))
        assertEquals("18:32, just now", formatStepEventRecency(ts(2026, Calendar.NOVEMBER, 19, 18, 32, 0), now, isZh = false, timeZone = tz))
        assertEquals("17:58，33分钟前", formatStepEventRecency(ts(2026, Calendar.NOVEMBER, 19, 17, 58, 47), now, isZh = true, timeZone = tz))
        assertEquals("17:58, 33m ago", formatStepEventRecency(ts(2026, Calendar.NOVEMBER, 19, 17, 58, 47), now, isZh = false, timeZone = tz))
        assertEquals("15:32，3小时前", formatStepEventRecency(ts(2026, Calendar.NOVEMBER, 19, 15, 32, 0), now, isZh = true, timeZone = tz))
        assertEquals("15:32, 3h ago", formatStepEventRecency(ts(2026, Calendar.NOVEMBER, 19, 15, 32, 0), now, isZh = false, timeZone = tz))
        assertEquals("11-18 22:32，20小时前", formatStepEventRecency(ts(2026, Calendar.NOVEMBER, 18, 22, 32, 0), now, isZh = true, timeZone = tz))
        assertEquals("11-17 18:32，2天前", formatStepEventRecency(ts(2026, Calendar.NOVEMBER, 17, 18, 32, 0), now, isZh = true, timeZone = tz))
        assertEquals("18:35", formatStepEventRecency(ts(2026, Calendar.NOVEMBER, 19, 18, 35, 0), now, isZh = true, timeZone = tz))

        val baseExact = StepHookStatus.fromValues(
            strings = mapOf(
                "hook_status" to "LOADED",
                "last_backend" to TuningConfig.BACKEND_EXACT,
            ),
            longs = mapOf(
                "hook_alive_at" to ts(2026, Calendar.NOVEMBER, 19, 17, 58, 45),
                "last_event_at" to ts(2026, Calendar.NOVEMBER, 19, 17, 58, 47),
                "last_original" to 7332L,
                "last_effective" to 7332L,
            ),
            booleans = emptyMap(),
        )
        val exactText = stepDashboardStatusText("每日计划 · 目标 7000 ±10%", baseExact, hasRoot = true, nowMillis = now, isZh = true, timeZone = tz)
        val exactLines = exactText.lines()
        assertEquals(3, exactLines.size)
        assertEquals("每日计划 · 目标 7000 ±10%", exactLines[0])
        assertEquals("模块心跳超过 15 分钟未更新", exactLines[1])
        assertEquals("最近读取已执行（17:58，33分钟前）", exactLines[2])
        assertFalse(exactText.contains("修改成功"))
        assertFalse(exactText.contains("最近计步事件已处理"))

        val sensorStatus = baseExact.copy(
            lastBackend = TuningConfig.BACKEND_SENSOR,
            hookAliveAt = ts(2026, Calendar.NOVEMBER, 19, 18, 31, 0),
            lastEventAt = ts(2026, Calendar.NOVEMBER, 19, 18, 30, 0),
        )
        val sensorLines = stepDashboardStatusText("真实步数倍率 · 3倍 · 硬上限 28000", sensorStatus, hasRoot = true, nowMillis = now, isZh = true, timeZone = tz).lines()
        assertEquals(3, sensorLines.size)
        assertEquals("模块已加载（不代表步数已生效）", sensorLines[1])
        assertEquals("最近计步事件已处理（18:30，2分钟前）", sensorLines[2])

        val hbOnlyLines = stepDashboardStatusText("每日计划 · 目标 7000 ±10%", sensorStatus.copy(lastEventAt = 0L, lastOriginal = null, lastEffective = null), hasRoot = true, nowMillis = now, isZh = true, timeZone = tz).lines()
        assertEquals("尚无事件记录", hbOnlyLines[2])

        val noRootLines = stepDashboardStatusText("每日计划 · 目标 7000 ±10%", baseExact, hasRoot = false, nowMillis = now, isZh = true, timeZone = tz).lines()
        assertEquals("事件记录未知", noRootLines[2])

        val nullStatusLines = stepDashboardStatusText("每日计划 · 目标 7000 ±10%", null, hasRoot = true, nowMillis = now, isZh = true, timeZone = tz).lines()
        assertEquals("事件记录未知", nullStatusLines[2])
    }

    @Test fun uiV5TargetPresetsParserSnapshotAndRestartGuidance() {
        assertEquals(listOf(6_000, 10_000, 15_000, 20_000), STEP_TARGET_PRESETS)

        assertEquals(10_000, parseStepTargetInput("10000"))
        assertEquals(10_000, parseStepTargetInput(" 10000 "))
        assertEquals(1_000, parseStepTargetInput("1000"))
        assertEquals(100_000, parseStepTargetInput("100000"))
        assertNull(parseStepTargetInput("999"))
        assertNull(parseStepTargetInput("100001"))
        assertNull(parseStepTargetInput(""))
        assertNull(parseStepTargetInput("abc"))

        assertEquals("每日计划（推荐）", stepModeOptionLabel(TuningConfig.MODE_PLAN, true))
        assertEquals("真实步数倍率", stepModeOptionLabel(TuningConfig.MODE_MULTIPLIER, true))
        assertEquals("旧版自动", stepModeOptionLabel(TuningConfig.MODE_LEGACY_AUTO, true))

        assertNull(stepMainTodaySnapshotLine(null, isZh = true))
        val planLine = stepMainTodaySnapshotLine(WechatStepPreview(8_270L, 8_913L, 11_065L), isZh = true)!!
        assertTrue(planLine.contains("客户端"))
        assertTrue(planLine.contains("8,913") || planLine.contains("8913"))
        assertTrue(planLine.contains("11,065") || planLine.contains("11065"))
        assertFalse(planLine.contains("服务器已接受"))

        val multLine = stepMainTodaySnapshotLine(WechatStepPreview(null, 9_200L, null), isZh = true)!!
        assertTrue(multLine.contains("客户端"))
        assertTrue(multLine.contains("9,200") || multLine.contains("9200"))

        val rootGuidance = stepRestartGuidanceText(hasRoot = true, isZh = true)
        assertTrue(rootGuidance.contains("无需每次重启"))
        assertTrue(rootGuidance.contains("切换模式/底层方式"))
        assertTrue(rootGuidance.contains("不会自动执行"))
        assertTrue(rootGuidance.contains("中断"))

        val noRootGuidance = stepRestartGuidanceText(hasRoot = false, isZh = true)
        assertTrue(noRootGuidance.contains("切换了模式或底层方式"))

        val tz = java.util.TimeZone.getTimeZone("GMT+11:00")
        val now = Calendar.getInstance(tz).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 19, 21, 0, 15)
        }.timeInMillis
        val eventAt = Calendar.getInstance(tz).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 19, 20, 57, 46)
        }.timeInMillis
        val exactStatus = StepHookStatus(
            hookStatus = "LOADED",
            hookAliveAt = now - 5_000L,
            lastBackend = TuningConfig.BACKEND_EXACT,
            lastEventAt = eventAt,
            lastOriginal = 8_270L,
            lastEffective = 8_913L,
        )
        val exactSub = stepDiagnosticsCardSubtitle(exactStatus, true, HeartbeatHealth.HEALTHY, now, true, tz)
        assertTrue(exactSub.contains("模块已加载"))
        assertTrue(exactSub.contains("最近读取"))
        assertTrue(exactSub.contains("20:57"))

        val sensorSub = stepDiagnosticsCardSubtitle(exactStatus.copy(lastBackend = TuningConfig.BACKEND_SENSOR), true, HeartbeatHealth.HEALTHY, now, true, tz)
        assertTrue(sensorSub.contains("最近事件"))
        assertTrue(sensorSub.contains("20:57"))

        val noEventSub = stepDiagnosticsCardSubtitle(exactStatus.copy(lastEventAt = 0L, lastOriginal = null, lastEffective = null), true, HeartbeatHealth.HEALTHY, now, true, tz)
        assertEquals("模块已加载", noEventSub)
    }
}
