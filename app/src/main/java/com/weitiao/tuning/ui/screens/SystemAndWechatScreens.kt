package com.weitiao.tuning.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.weitiao.tuning.R
import com.weitiao.tuning.WechatHooks
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.App
import com.weitiao.tuning.core.InstalledAppsRepository
import com.weitiao.tuning.core.LsposedScopeHelper
import com.weitiao.tuning.core.StepHookStatus
import com.weitiao.tuning.core.HeartbeatHealth
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.TuningConfig
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.tuning.FastChargeTileService
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiCollapsibleCard
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.OneUiSegmentedControl
import java.text.NumberFormat
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.WindowBottomSheet
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

// ---------------------------------------------------------------------------
// 4. 微信增强详情页（步数设置 & 消息防撤回）
// ---------------------------------------------------------------------------

@Composable
internal fun WechatEnhancementDetailContent(
    initialSubTab: Int,
    tuning: TuningConfig,
    hasRoot: Boolean,
    isZh: Boolean,
    onSwitchSubPage: (Int) -> Unit,
    onUpdateTuning: (String?, (TuningConfig) -> TuningConfig) -> Unit,
    onRequestRestartWechat: () -> Unit,
) {
    val context = LocalContext.current
    val numberFmt = remember { NumberFormat.getIntegerInstance() }
    var multiplierDraft by remember(tuning.stepMultiplier) { mutableFloatStateOf(tuning.stepMultiplier.toFloat()) }
    var maxStepDraft by remember(tuning.stepMaximum) { mutableFloatStateOf(tuning.stepMaximum.toFloat()) }
    var targetInputText by remember(tuning.stepTarget) { mutableStateOf(tuning.stepTarget.toString()) }
    var targetInputError by remember(tuning.stepTarget) { mutableStateOf<String?>(null) }
    var jitterDraft by remember(tuning.stepJitter) { mutableFloatStateOf(tuning.stepJitter.toFloat()) }
    var startDraft by remember(tuning.stepWindowStart) { mutableFloatStateOf(tuning.stepWindowStart.toFloat()) }
    var endDraft by remember(tuning.stepWindowEnd) { mutableFloatStateOf(tuning.stepWindowEnd.toFloat()) }
    var preview by remember { mutableStateOf<WechatStepPreview?>(null) }
    var previewRefresh by remember { mutableStateOf(0) }
    var statusNow by remember { mutableStateOf(System.currentTimeMillis()) }
    var diagnostics by remember { mutableStateOf<StepHookStatus?>(null) }
    var wechatInScope by remember { mutableStateOf<Boolean?>(null) }
    var scopeMessage by remember { mutableStateOf("") }
    var remoteSynced by remember { mutableStateOf<Boolean?>(null) }
    var planUpdated by remember { mutableStateOf<Boolean?>(null) }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(hasRoot, tuning, previewRefresh) {
        val currentConfig = withContext(Dispatchers.IO) { TuningPrefs.load(context) }
        remoteSynced = withContext(Dispatchers.IO) { TuningPrefs.remoteSyncState(context, tuning) }
        val persisted = if (hasRoot) withContext(Dispatchers.IO) { readWechatStepState() } else null
        diagnostics = persisted?.status
        preview = persisted?.preview(currentConfig)
        planUpdated = persisted?.isTodayPlanUpdated(currentConfig)
        wechatInScope = withContext(Dispatchers.IO) { LsposedScopeHelper.isPackageInScope("com.tencent.mm") }
        statusNow = System.currentTimeMillis()
    }
    LaunchedEffect(hasRoot) {
        while (hasRoot) {
            kotlinx.coroutines.delay(30_000L)
            previewRefresh++
        }
    }

    OneUiSegmentedControl(
        items = listOf(0, 1),
        selectedItem = initialSubTab,
        onSelect = onSwitchSubPage,
        label = { idx ->
            if (idx == 0) {
                if (isZh) "运动步数" else "Step Counter"
            } else {
                if (isZh) "消息防撤回" else "Anti-Recall"
            }
        },
    )

    if (initialSubTab == 0) {
        val effectiveTargetForCap = parseStepTargetInput(targetInputText) ?: tuning.stepTarget
        val capWarning = if (tuning.stepEnabled && tuning.stepMode == TuningConfig.MODE_PLAN) {
            stepHardCapWarning(effectiveTargetForCap, jitterDraft.toInt(), maxStepDraft.toInt(), isZh)
        } else {
            null
        }
        val health = diagnostics?.heartbeatHealth(hasRoot, statusNow) ?: HeartbeatHealth.UNKNOWN

        OneUiIslandCard {
            OneUiItemRow(
                title = if (isZh) "启用微信步数修改" else "Enable WeChat Step Modifier",
                subtitle = if (tuning.stepEnabled) stepModeSummary(tuning, isZh) else {
                    if (isZh) "已关闭，微信运动读取系统原始计步数据"
                    else "Disabled. WeChat uses original step data"
                },
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                iconTint = IosGlassTokens.IosGreen,
                checked = tuning.stepEnabled,
                onCheckedChange = { enabled ->
                    if (enabled != tuning.stepEnabled) {
                        onUpdateTuning(
                            if (enabled) {
                                if (isZh) "已开启微信步数修改" else "WeChat step modifier enabled"
                            } else {
                                if (isZh) "已关闭微信步数修改" else "WeChat step modifier disabled"
                            },
                        ) { it.copy(stepEnabled = enabled) }
                    }
                },
            )
            if (remoteSynced == false) {
                Text(
                    text = if (isZh) "仅本地保存，尚未同步到 LSPosed（请检查 LSPosed 模块状态）"
                    else "Saved locally only; not synced to LSPosed (check LSPosed module status)",
                    fontSize = 11.5.sp,
                    color = IosGlassTokens.IosAmber,
                )
            }
            val todayLine = if (tuning.stepEnabled) stepMainTodaySnapshotLine(preview, isZh, numberFmt) else null
            if (todayLine != null) {
                Text(
                    text = todayLine,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = IosGlassTokens.IosGreen,
                )
                Text(
                    text = if (isZh) "仅客户端读取记录，不代表服务器已接受" else "Client read record only; does not imply server acceptance",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        if (tuning.stepEnabled) {
            val paramCardTitle = stepParameterCardTitle(tuning.stepMode, isZh)
            if (tuning.stepMode == TuningConfig.MODE_PLAN) {
                OneUiIslandCard(title = paramCardTitle) {
                    Text(
                        text = if (isZh) {
                            "当前目标中心：${numberFmt.format(tuning.stepTarget)} 步（按时间窗口平滑推进，真实步数更高时保留较高值）"
                        } else {
                            "Current target center: ${numberFmt.format(tuning.stepTarget)} (smooth progression in window; higher real steps preserved)"
                        },
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        STEP_TARGET_PRESETS.forEach { preset ->
                            val selected = tuning.stepTarget == preset
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) IosGlassTokens.activeTileBg() else MiuixTheme.colorScheme.secondaryContainer)
                                    .clickable {
                                        targetInputText = preset.toString()
                                        targetInputError = null
                                        if (preset != tuning.stepTarget) {
                                            onUpdateTuning(
                                                if (isZh) "已设为 ${numberFmt.format(preset)} 步"
                                                else "Target set to ${numberFmt.format(preset)}",
                                            ) { it.copy(stepTarget = preset) }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = numberFmt.format(preset),
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) IosGlassTokens.activeTileFg() else MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    TextField(
                        value = targetInputText,
                        onValueChange = { raw ->
                            targetInputText = raw.filter(Char::isDigit).take(6)
                            targetInputError = null
                        },
                        label = if (isZh) "自定义目标步数（如 10000）" else "Custom target steps (e.g. 10000)",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        insideMargin = DpSize(12.dp, 10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val isDirty = targetInputText != tuning.stepTarget.toString()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = if (isZh) "保存" else "Save",
                            enabled = isDirty,
                            onClick = {
                                val parsed = parseStepTargetInput(targetInputText)
                                when {
                                    parsed == null -> {
                                        targetInputError = if (isZh) {
                                            "请输入 1,000–100,000 之间的整数"
                                        } else {
                                            "Enter an integer between 1,000 and 100,000"
                                        }
                                    }
                                    parsed == tuning.stepTarget -> {
                                        targetInputText = tuning.stepTarget.toString()
                                        targetInputError = null
                                    }
                                    else -> {
                                        targetInputError = null
                                        onUpdateTuning(
                                            if (isZh) "已保存目标中心 ${numberFmt.format(parsed)} 步"
                                            else "Saved target ${numberFmt.format(parsed)}",
                                        ) { it.copy(stepTarget = parsed) }
                                    }
                                }
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = if (isZh) "取消" else "Cancel",
                            enabled = isDirty || targetInputError != null,
                            onClick = {
                                targetInputText = tuning.stepTarget.toString()
                                targetInputError = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (targetInputError != null) {
                        Text(
                            text = targetInputError!!,
                            fontSize = 11.5.sp,
                            color = IosGlassTokens.IosRed,
                        )
                    }
                    if (capWarning != null) {
                        Text(
                            text = capWarning,
                            fontSize = 11.5.sp,
                            color = IosGlassTokens.IosAmber,
                        )
                    }
                }
            } else {
                OneUiIslandCard(title = paramCardTitle) {
                    Text(
                        text = if (tuning.stepMode == TuningConfig.MODE_MULTIPLIER) {
                            if (isZh) "当前模式：真实步数倍率（按真实新增步数乘以指定倍率）。"
                            else "Current mode: Real Step Multiplier (multiplies new real steps by this factor)."
                        } else {
                            if (isZh) "当前模式：旧版自动（兼容旧版自动递增逻辑，按倍率参与计算）。"
                            else "Current mode: Legacy Auto (legacy auto-increment using the multiplier)."
                        },
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    StepSettingSlider(
                        label = if (isZh) "放大倍率" else "Multiplier",
                        display = stepMultiplierDisplay(multiplierDraft.toInt(), isZh),
                        value = multiplierDraft,
                        range = 1f..200f,
                        onChange = { multiplierDraft = it.roundToInt().toFloat() },
                        onFinish = {
                            val next = multiplierDraft.toInt()
                            if (next != tuning.stepMultiplier) {
                                onUpdateTuning(null) { it.copy(stepMultiplier = next) }
                            }
                        },
                    )
                    TextButton(
                        text = if (isZh) "切回「每日计划（推荐）」" else "Switch back to Daily Plan (Recommended)",
                        onClick = {
                            onUpdateTuning(
                                if (isZh) "已切换为每日计划" else "Switched to Daily Plan",
                            ) { it.copy(stepMode = TuningConfig.MODE_PLAN, stepAuto = true) }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        OneUiIslandCard(
            title = if (isZh) "重启微信" else "Restart WeChat",
        ) {
            Text(
                text = stepRestartGuidanceText(hasRoot, isZh),
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = if (isZh) "重启微信" else "Restart WeChat",
                enabled = hasRoot,
                onClick = onRequestRestartWechat,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val advancedSubtitle = if (isZh) {
            "模式：${stepModeLabel(tuning.stepMode, true)} · 底层：${stepBackendLabel(tuning.stepBackend, true)} · ${formatStepMinute(tuning.stepWindowStart)}–${formatStepMinute(tuning.stepWindowEnd)} · 上限 ${numberFmt.format(tuning.stepMaximum)}"
        } else {
            "Mode: ${stepModeLabel(tuning.stepMode, false)} · Backend: ${stepBackendLabel(tuning.stepBackend, false)} · ${formatStepMinute(tuning.stepWindowStart)}–${formatStepMinute(tuning.stepWindowEnd)} · Cap ${numberFmt.format(tuning.stepMaximum)}"
        }

        OneUiCollapsibleCard(
            title = if (isZh) "进阶参数" else "Advanced Settings",
            subtitle = advancedSubtitle,
            initiallyExpanded = false,
        ) {
            Text(
                text = if (isZh) "步数模式" else "Step Mode",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            val modeOptions = listOf(
                TuningConfig.MODE_PLAN to if (isZh) "按时间窗口平滑推进至每日目标，真实步数更高时保留较高值。" else "Smoothly progresses toward the daily target in the window.",
                TuningConfig.MODE_MULTIPLIER to if (isZh) "按真实新增步数乘以指定倍率。" else "Multiplies newly added real steps by the chosen factor.",
                TuningConfig.MODE_LEGACY_AUTO to if (isZh) "兼容旧版自动递增逻辑，按倍率参与计算。" else "Compatible legacy auto increment using the multiplier.",
            )
            modeOptions.forEach { (mode, desc) ->
                val selected = tuning.stepMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) IosGlassTokens.activeTileBg() else MiuixTheme.colorScheme.secondaryContainer)
                        .clickable {
                            if (mode != tuning.stepMode) {
                                onUpdateTuning(null) { it.copy(stepMode = mode, stepAuto = mode != TuningConfig.MODE_MULTIPLIER) }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) IosGlassTokens.activeTileFg()
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f),
                            ),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stepModeOptionLabel(mode, isZh),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) IosGlassTokens.activeTileFg() else MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = desc,
                            fontSize = 11.sp,
                            color = if (selected) IosGlassTokens.activeTileFg().copy(alpha = 0.85f) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = if (isZh) "底层方式" else "Step Backend",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            OneUiSegmentedControl(
                items = listOf(TuningConfig.BACKEND_AUTO, TuningConfig.BACKEND_EXACT, TuningConfig.BACKEND_SENSOR),
                selectedItem = tuning.stepBackend,
                onSelect = { backend ->
                    if (backend != tuning.stepBackend) {
                        onUpdateTuning(null) { it.copy(stepBackend = backend) }
                    }
                },
                label = { backend -> stepBackendLabel(backend, isZh) },
            )
            Text(
                text = if (isZh) "自动：优先精确适配，不匹配时回退传感器；精确：仅在已校验版本启用精确模式；传感器：使用计步传感器路径。切换底层方式后请重启微信。"
                else "Auto: prefers verified exact adapter and falls back to sensor; Exact: uses exact adapter when verified; Sensor: uses step sensor path. Restart WeChat after switching backend.",
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (tuning.stepMode == TuningConfig.MODE_PLAN) {
                Text(
                    text = if (isZh) "每日浮动" else "Daily Variation",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                StepSettingSlider(
                    label = if (isZh) "浮动幅度" else "Variation",
                    display = "±${jitterDraft.toInt()}%",
                    value = jitterDraft,
                    range = 0f..50f,
                    onChange = { jitterDraft = it.roundToInt().toFloat() },
                    onFinish = {
                        val next = jitterDraft.toInt()
                        if (next != tuning.stepJitter) {
                            onUpdateTuning(null) { it.copy(stepJitter = next) }
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            Text(
                text = if (isZh) "时间窗口与窗口后行为" else "Time Window & After Window",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            StepSettingSlider(
                label = if (isZh) "开始时间" else "Start Time",
                display = formatStepMinute(startDraft.toInt()),
                value = startDraft,
                range = 0f..1439f,
                onChange = { startDraft = it.roundToInt().coerceAtMost(endDraft.toInt() - 1).coerceAtLeast(0).toFloat() },
                onFinish = {
                    val next = startDraft.toInt()
                    if (next != tuning.stepWindowStart) {
                        onUpdateTuning(null) { it.copy(stepWindowStart = next) }
                    }
                },
            )
            StepSettingSlider(
                label = if (isZh) "结束时间" else "End Time",
                display = formatStepMinute(endDraft.toInt()),
                value = endDraft,
                range = 1f..1440f,
                onChange = { endDraft = it.roundToInt().coerceAtLeast(startDraft.toInt() + 1).coerceAtMost(1440).toFloat() },
                onFinish = {
                    val next = endDraft.toInt()
                    if (next != tuning.stepWindowEnd) {
                        onUpdateTuning(null) { it.copy(stepWindowEnd = next) }
                    }
                },
            )
            OneUiSegmentedControl(
                items = listOf(TuningConfig.AFTER_NATURAL, TuningConfig.AFTER_FREEZE),
                selectedItem = tuning.stepAfterWindow,
                onSelect = { after ->
                    if (after != tuning.stepAfterWindow) {
                        onUpdateTuning(null) { it.copy(stepAfterWindow = after) }
                    }
                },
                label = { after -> stepAfterWindowLabel(after, isZh) },
            )
            Text(
                text = if (isZh) "自然增长：窗口结束后仅随后续新增步数增长；冻结：保持窗口结束值，不再继续累加。"
                else "Natural: adds only subsequent steps after the window. Freeze: holds the window end value.",
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = if (isZh) "每日硬上限" else "Daily Hard Cap",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            StepSettingSlider(
                label = if (isZh) "硬上限" else "Hard Cap",
                display = if (isZh) "${numberFmt.format(maxStepDraft.toInt())} 步" else numberFmt.format(maxStepDraft.toInt()),
                value = maxStepDraft,
                range = 1000f..100000f,
                onChange = { maxStepDraft = (it / 100f).roundToInt() * 100f },
                onFinish = {
                    val next = maxStepDraft.toInt()
                    if (next != tuning.stepMaximum) {
                        onUpdateTuning(null) { it.copy(stepMaximum = next) }
                    }
                },
            )
            if (capWarning != null) {
                Text(
                    text = capWarning,
                    fontSize = 11.5.sp,
                    color = IosGlassTokens.IosAmber,
                )
            }
        }

        OneUiCollapsibleCard(
            title = if (isZh) "运行状态与排障" else "Runtime Status & Diagnostics",
            subtitle = stepDiagnosticsCardSubtitle(diagnostics, hasRoot, health, statusNow, isZh),
            initiallyExpanded = false,
        ) {
            Text(
                text = if (isZh) "运行状态" else "Runtime Status",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            val (loadLine, readLine, uploadLine) = stepRuntimeStatusLines(
                status = diagnostics,
                hasRoot = hasRoot,
                nowMillis = statusNow,
                isZh = isZh,
            )
            Text(
                text = loadLine,
                fontSize = 12.sp,
                color = when (health) {
                    HeartbeatHealth.HEALTHY -> IosGlassTokens.IosGreen
                    HeartbeatHealth.STALE -> IosGlassTokens.IosAmber
                    HeartbeatHealth.DEAD -> IosGlassTokens.IosRed
                    HeartbeatHealth.UNKNOWN -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Text(
                text = "$readLine\n$uploadLine",
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = stepPreviewSummary(preview, diagnostics, hasRoot, isZh, numberFmt),
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = if (isZh) "刷新状态" else "Refresh State",
                enabled = hasRoot,
                onClick = { previewRefresh++ },
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = if (isZh) "微信作用域检查" else "WeChat Scope Check",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = (if (isZh) "微信作用域：" else "WeChat scope: ") + when (wechatInScope) {
                    true -> if (isZh) "已勾选" else "Included"
                    false -> if (isZh) "未勾选" else "Missing"
                    null -> if (isZh) "未知（服务未连接）" else "Unknown (service unavailable)"
                } + if (scopeMessage.isBlank()) "" else "\n$scopeMessage",
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = if (isZh) "检查并修复微信作用域" else "Check / Repair WeChat Scope",
                onClick = {
                    coroutineScope.launch {
                        val alreadyInScope = withContext(Dispatchers.IO) {
                            LsposedScopeHelper.isPackageInScope("com.tencent.mm") == true
                        }
                        if (alreadyInScope) {
                            wechatInScope = true
                            scopeMessage = if (isZh) "微信已在 LSPosed 作用域中。" else "WeChat is already in LSPosed scope."
                            previewRefresh++
                        } else if (!LsposedScopeHelper.isServiceConnected()) {
                            scopeMessage = if (isZh) "LSPosed 服务未连接，无法自动申请作用域；请在 LSPosed 管理器中手动勾选微信。"
                            else "LSPosed service unavailable; please enable WeChat in LSPosed Manager manually."
                        } else {
                            scopeMessage = if (isZh) "正在通过框架申请微信作用域…" else "Requesting WeChat scope from framework…"
                            LsposedScopeHelper.requestScopesViaService(setOf("com.tencent.mm")) { approved ->
                                coroutineScope.launch {
                                    scopeMessage = if (approved) {
                                        if (isZh) "微信作用域申请已通过；请刷新确认并重启微信应用。"
                                        else "WeChat scope approved. Refresh to verify, then restart WeChat."
                                    } else {
                                        if (isZh) "作用域申请未通过；请在 LSPosed 管理器中手动勾选微信。"
                                        else "Scope request not approved; please enable WeChat in LSPosed Manager manually."
                                    }
                                    previewRefresh++
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = if (isZh) "诊断详情" else "Diagnostic Details",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = stepHookDetail(diagnostics, hasRoot, isZh, tuning, remoteSynced, planUpdated),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    } else {
        OneUiIslandCard {
            OneUiItemRow(
                title = if (isZh) "启用消息防撤回" else "Enable Message Anti-Recall",
                subtitle = if (tuning.antiRecall) {
                    if (isZh) "已开启，对方撤回的消息将继续保留在聊天窗口中"
                    else "Enabled. Recalled messages stay visible in chat"
                } else {
                    if (isZh) "已关闭，对方撤回消息后正常被移除"
                    else "Disabled. Recalled messages are removed normally"
                },
                icon = Icons.Default.Shield,
                iconTint = IosGlassTokens.IosBlue,
                checked = tuning.antiRecall,
                onCheckedChange = { enabled ->
                    if (enabled != tuning.antiRecall) {
                        onUpdateTuning(
                            if (enabled) {
                                if (isZh) "已开启微信消息防撤回" else "WeChat anti-recall enabled"
                            } else {
                                if (isZh) "已关闭微信消息防撤回" else "WeChat anti-recall disabled"
                            },
                        ) { it.copy(antiRecall = enabled) }
                    }
                },
            )
        }

        OneUiIslandCard(
            title = if (isZh) "功能说明" else "How It Works",
        ) {
            Text(
                text = if (isZh) "• 支持保留私聊与群聊中对方撤回的文字、图片、语音、视频和表情消息。\n• 拦截撤回指令后不会向对方发送任何提示，仅在本地保留原始消息记录。"
                else "• Preserves recalled text, photos, voice notes, videos, and stickers in both direct and group chats.\n• Works silently on your device without notifying the sender.",
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        OneUiIslandCard(
            title = if (isZh) "一键重启微信生效" else "Restart WeChat to Apply",
        ) {
            Text(
                text = (if (isZh) "微信作用域：" else "WeChat scope: ") + when (wechatInScope) {
                    true -> if (isZh) "已勾选" else "Included"
                    false -> if (isZh) "未勾选" else "Missing"
                    null -> if (isZh) "未知（服务未连接）" else "Unknown (service unavailable)"
                } + if (scopeMessage.isBlank()) "" else "\n$scopeMessage",
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = if (isZh) "检查并修复微信作用域" else "Check / Repair WeChat Scope",
                onClick = {
                    coroutineScope.launch {
                        val alreadyInScope = withContext(Dispatchers.IO) {
                            LsposedScopeHelper.isPackageInScope("com.tencent.mm") == true
                        }
                        if (alreadyInScope) {
                            wechatInScope = true
                            scopeMessage = if (isZh) "微信已在 LSPosed 作用域中。" else "WeChat is already in LSPosed scope."
                            previewRefresh++
                        } else if (!LsposedScopeHelper.isServiceConnected()) {
                            scopeMessage = if (isZh) "LSPosed 服务未连接，无法自动申请作用域；请在 LSPosed 管理器中手动勾选微信。"
                            else "LSPosed service unavailable; please enable WeChat in LSPosed Manager manually."
                        } else {
                            scopeMessage = if (isZh) "正在通过框架申请微信作用域…" else "Requesting WeChat scope from framework…"
                            LsposedScopeHelper.requestScopesViaService(setOf("com.tencent.mm")) { approved ->
                                coroutineScope.launch {
                                    scopeMessage = if (approved) {
                                        if (isZh) "微信作用域申请已通过；请刷新确认并重启微信应用。"
                                        else "WeChat scope approved. Refresh to verify, then restart WeChat."
                                    } else {
                                        if (isZh) "作用域申请未通过；请在 LSPosed 管理器中手动勾选微信。"
                                        else "Scope request not approved; please enable WeChat in LSPosed Manager manually."
                                    }
                                    previewRefresh++
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (hasRoot) {
                    if (isZh) "修改设置后需重启微信生效。点击下方按钮将强制关闭并重新启动微信，会中断当前聊天、通话或页面；不会自动执行。"
                    else "Restart WeChat to apply changes. Tapping below force-stops and relaunches WeChat, interrupting active chats or calls; it will never run automatically."
                } else {
                    if (isZh) "配置已自动保存；当前无 Root 权限，请手动划掉微信后台并重新打开以生效。"
                    else "Configuration saved. Without Root, close and reopen WeChat manually to apply."
                },
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = if (isZh) "一键重启微信生效" else "Restart WeChat to Apply",
                enabled = hasRoot,
                onClick = onRequestRestartWechat,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 5. 充电与供电详情页（亮屏快充 & 应用旁路供电）
// ---------------------------------------------------------------------------

@Composable
internal fun PowerOptimizationDetailContent(
    initialSubTab: Int,
    tuning: TuningConfig,
    selectedPassApps: Set<String>,
    hasRoot: Boolean,
    isZh: Boolean,
    onSwitchSubPage: (Int) -> Unit,
    onRequireRoot: ((Boolean) -> Unit) -> Unit,
    onUpdateTuning: (String?, (TuningConfig) -> TuningConfig) -> Unit,
    onUpdatePassApps: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var thermalDraft by remember(tuning.thermalCutoffCelsius) {
        mutableFloatStateOf(
            tuning.thermalCutoffCelsius.coerceIn(
                TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS,
                TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS,
            ).toFloat(),
        )
    }
    var passLimitDraft by remember(tuning.passThroughLimit) {
        mutableFloatStateOf(tuning.passThroughLimit.toFloat())
    }
    var showPassAppPicker by remember { mutableStateOf(false) }
    var batteryRestricted by remember {
        mutableStateOf(TuningPrefs.isBatteryOptimizationRestricted(context))
    }

    // 硬件节点可用性检测（仅当已获 Root 时检测，仅影响当前对应子功能）
    var fastChargeNodeSupported by remember { mutableStateOf(true) }
    var bypassNodeSupported by remember { mutableStateOf(true) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(hasRoot) {
        if (hasRoot) {
            withContext(Dispatchers.IO) {
                val (code, out) = RootShell.exec(
                    "if [ -e /sys/class/power_supply/battery/batt_temp ] || [ -e /sys/class/power_supply/battery/temp ]; then echo __FC_OK__; fi; " +
                        "if [ -e /sys/class/power_supply/battery/pass_through ] || [ -e /sys/class/power_supply/battery/batt_slate_mode ] || " +
                        "[ -e /sys/class/sec/battery/pass_through ] || [ -e /sys/class/power_supply/sec-battery/pass_through ]; then echo __BP_OK__; fi",
                    timeoutSeconds = 4,
                )
                if (code == 0) {
                    fastChargeNodeSupported = out.contains("__FC_OK__")
                    bypassNodeSupported = out.contains("__BP_OK__")
                }
            }
        }
    }

    OneUiSegmentedControl(
        items = listOf(0, 1),
        selectedItem = initialSubTab,
        onSelect = onSwitchSubPage,
        label = { idx ->
            if (idx == 0) {
                if (isZh) "亮屏快充" else "Fast Charge"
            } else {
                if (isZh) "应用旁路供电" else "Bypass Power"
            }
        },
    )

    if ((tuning.brightScreenFastCharge || tuning.passThrough) && batteryRestricted) {
        OneUiNoticeBanner(
            text = if (isZh) "建议将微调设为「电池无限制」，防止息屏或插电时后台温控守护服务被系统休眠。"
            else "Set battery usage to Unrestricted so the charging service stays active reliably.",
            icon = Icons.Default.BatteryAlert,
            contentColor = IosGlassTokens.IosAmber,
            actionText = if (isZh) "去设置" else "Allow",
            onActionClick = {
                TuningPrefs.requestIgnoreBatteryOptimization(context)
                batteryRestricted = TuningPrefs.isBatteryOptimizationRestricted(context)
            },
        )
    }

    if (initialSubTab == 0) {
        if (hasRoot && !fastChargeNodeSupported) {
            OneUiNoticeBanner(
                text = if (isZh) "当前设备内核未检测到电池温控节点，亮屏快充可能无法在您的机型上生效。"
                else "Battery thermal nodes were not found on this kernel; Screen-On Fast Charge may not be supported.",
                icon = Icons.Default.Warning,
                contentColor = IosGlassTokens.IosAmber,
            )
        }

        OneUiIslandCard {
            OneUiItemRow(
                title = if (isZh) "启用亮屏快充" else "Enable Screen-On Fast Charge",
                subtitle = if (tuning.brightScreenFastCharge) {
                    if (isZh) "已开启 · AC 有线充电生效，电池达 ${tuning.thermalCutoffCelsius}°C、充满或拔线自动恢复"
                    else "Enabled · Active on AC charger; auto-restores at ${tuning.thermalCutoffCelsius}°C battery cutoff, full charge, or unplug"
                } else {
                    if (isZh) "已关闭，亮屏边玩边充时保持三星默认限流策略"
                    else "Disabled. Uses Samsung default screen-on charging limit"
                },
                icon = Icons.Default.Bolt,
                iconTint = IosGlassTokens.IosAmber,
                checked = tuning.brightScreenFastCharge,
                onCheckedChange = { targetEnabled ->
                    if (!targetEnabled) {
                        onUpdateTuning(
                            if (isZh) "已关闭亮屏快充并恢复系统默认温控" else "Screen-on fast charge disabled",
                        ) { it.copy(brightScreenFastCharge = false) }
                    } else {
                        ensureNotificationPermission()
                        onRequireRoot { granted ->
                            if (granted) {
                                onUpdateTuning(
                                    if (isZh) "已开启亮屏快充（${tuning.thermalCutoffCelsius}°C 自动过热保护）"
                                    else "Screen-on fast charge enabled",
                                ) { it.copy(brightScreenFastCharge = true) }
                            }
                        }
                    }
                },
            )
        }

        if (!tuning.brightScreenFastCharge) {
            OneUiNoticeBanner(
                text = if (isZh) "开启后接入 AC 有线充电器即全面解除三星温控限流并支持低电流自动重协商，达到电池安全温度阈值、充满 100% 或拔线时自动还原系统温控。"
                else "Unlocks Samsung thermal charging limits while on AC power with low-current auto-recovery, automatically restoring at battery cutoff, full charge, or unplug.",
                icon = Icons.Default.Bolt,
                contentColor = IosGlassTokens.IosAmber,
            )
        } else {
            OneUiIslandCard(
                title = if (isZh) "安全温控阈值（38°C ~ 48°C，推荐 44°C）" else "Thermal Safety Cutoff (38°C ~ 48°C, Recommended 44°C)",
                subtitle = if (isZh) "电池温度达到该阈值时立即停止快充解锁并恢复系统温控保护"
                else "Immediately restores system thermal protection when battery reaches this temperature",
            ) {
                val currentDraftCelsius = thermalDraft.roundToInt().coerceIn(
                    TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS,
                    TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isZh) "温控恢复温度" else "Cutoff Temperature",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${currentDraftCelsius}°C",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (currentDraftCelsius >= 46) IosGlassTokens.IosAmber else IosGlassTokens.IosGreen,
                    )
                }
                Slider(
                    value = currentDraftCelsius.toFloat(),
                    onValueChange = {
                        thermalDraft = it.roundToInt().coerceIn(
                            TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS,
                            TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS,
                        ).toFloat()
                    },
                    onValueChangeFinished = {
                        val nextTemp = thermalDraft.roundToInt().coerceIn(
                            TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS,
                            TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS,
                        )
                        thermalDraft = nextTemp.toFloat()
                        onUpdateTuning(null) { it.copy(thermalCutoffCelsius = nextTemp) }
                    },
                    valueRange = TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS.toFloat()..TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS.toFloat(),
                    steps = TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS - TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS - 1,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS}°C",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "${TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS}°C",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (tuning.thermalCutoffCelsius != TuningConfig.DEFAULT_THERMAL_CUTOFF_CELSIUS) {
                    TextButton(
                        text = if (isZh) "恢复推荐设置 (44°C)" else "Restore Recommended (44°C)",
                        onClick = {
                            thermalDraft = TuningConfig.DEFAULT_THERMAL_CUTOFF_CELSIUS.toFloat()
                            onUpdateTuning(context.getString(R.string.saved_toast)) {
                                it.copy(thermalCutoffCelsius = TuningConfig.DEFAULT_THERMAL_CUTOFF_CELSIUS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        OneUiIslandCard {
            OneUiItemRow(
                title = if (isZh) "快捷控制中心磁贴" else "Quick Settings Tile",
                subtitle = if (isZh) "一键添加「亮屏快充」磁贴到下拉面板，支持直接点击开关"
                else "Add the Fast Charge tile to Quick Settings for one-tap toggle",
                icon = Icons.Default.Bolt,
                iconTint = IosGlassTokens.IosBlue,
                showChevron = true,
                onClick = { FastChargeTileService.requestAddTile(context) },
            )
        }
    } else {
        if (hasRoot && !bypassNodeSupported) {
            OneUiNoticeBanner(
                text = if (isZh) "当前设备内核未检测到旁路供电节点（pass_through），此功能仅在支持的三星机型上生效。"
                else "Bypass power sysfs node was not found on this kernel; this feature only works on supported Samsung devices.",
                icon = Icons.Default.Warning,
                contentColor = IosGlassTokens.IosAmber,
            )
        }

        OneUiIslandCard {
            OneUiItemRow(
                title = if (isZh) "启用应用旁路供电" else "Enable App Bypass Power",
                subtitle = if (tuning.passThrough) {
                    if (isZh) "已开启 · 已选 ${selectedPassApps.size} 个应用 · 电量高于 ${tuning.passThroughLimit}% 时生效"
                    else "Enabled · ${selectedPassApps.size} app(s) · Active above ${tuning.passThroughLimit}% battery"
                } else {
                    if (isZh) "已关闭，所有应用充电时正常向电池充电"
                    else "Disabled. Normal battery charging in all apps"
                },
                icon = Icons.Default.Power,
                iconTint = IosGlassTokens.IosGreen,
                checked = tuning.passThrough,
                onCheckedChange = { targetEnabled ->
                    if (!targetEnabled) {
                        onUpdateTuning(
                            if (isZh) "已关闭应用旁路供电" else "App bypass power disabled",
                        ) { it.copy(passThrough = false) }
                    } else {
                        ensureNotificationPermission()
                        onRequireRoot { granted ->
                            if (granted) {
                                onUpdateTuning(
                                    if (isZh) "已开启应用旁路供电" else "App bypass power enabled",
                                ) { it.copy(passThrough = true) }
                            }
                        }
                    }
                },
            )
        }

        if (!tuning.passThrough) {
            OneUiNoticeBanner(
                text = if (isZh) "开启后，在插着支持 PPS 的充电器运行指定游戏或高负载应用时，由充电器直接给主板供电，暂停电池充放电以大幅降低机身发热。"
                else "When enabled, powers the motherboard directly from the charger while running selected apps, reducing heat and battery wear.",
                icon = Icons.Default.Power,
                contentColor = IosGlassTokens.IosGreen,
            )
        } else {
            OneUiIslandCard(
                title = if (isZh) "生效应用与电量阈值" else "Target Apps & Battery Threshold",
            ) {
                OneUiItemRow(
                    title = if (isZh) "选择启用旁路供电的游戏 / 应用" else "Select Apps for Bypass Power",
                    subtitle = if (selectedPassApps.isEmpty()) {
                        if (isZh) "当前未选择应用，点击选择需要开启旁路供电的游戏"
                        else "No apps selected yet. Tap to choose games or apps"
                    } else {
                        if (isZh) "已选择 ${selectedPassApps.size} 个应用，进入前台且插电时自动开启旁路供电"
                        else "${selectedPassApps.size} app(s) selected"
                    },
                    icon = Icons.Default.Apps,
                    iconTint = IosGlassTokens.IosBlue,
                    trailingValue = if (isZh) "${selectedPassApps.size} 个" else "${selectedPassApps.size}",
                    showChevron = true,
                    onClick = { showPassAppPicker = true },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isZh) "最低触发电量阈值（推荐 20%）" else "Minimum Battery Level (Recommended 20%)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${passLimitDraft.toInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosGlassTokens.IosGreen,
                    )
                }
                Text(
                    text = if (isZh) "当手机电量高于该百分比时才启用旁路供电；低于该电量时优先给电池充电。"
                    else "Bypass power activates only when battery level is above this percentage.",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Slider(
                    value = passLimitDraft,
                    onValueChange = { passLimitDraft = (it / 5f).toInt() * 5f },
                    onValueChangeFinished = {
                        val nextLimit = passLimitDraft.toInt().coerceIn(5, 80)
                        onUpdateTuning(null) { it.copy(passThroughLimit = nextLimit) }
                    },
                    valueRange = 5f..80f,
                )
                if (tuning.passThroughLimit != 20) {
                    TextButton(
                        text = if (isZh) "恢复推荐设置 (20%)" else "Restore Recommended (20%)",
                        onClick = {
                            passLimitDraft = 20f
                            onUpdateTuning(context.getString(R.string.saved_toast)) {
                                it.copy(passThroughLimit = 20)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showPassAppPicker) {
        PassThroughAppPickerSheet(
            show = true,
            initial = selectedPassApps,
            onDismiss = { showPassAppPicker = false },
            onSave = { updated ->
                onUpdatePassApps(updated)
                showPassAppPicker = false
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 6. One UI 常用隐藏功能详情页
// ---------------------------------------------------------------------------

@Composable
internal fun OneUiHiddenFeaturesDetailContent(
    csc: CscConfig,
    isZh: Boolean,
    onUpdateCsc: (String?, (CscConfig) -> CscConfig) -> Unit,
    onOpenCustomCscFeature: () -> Unit,
) {
    val context = LocalContext.current

    OneUiIslandCard(
        title = if (isZh) "常用隐藏功能一键开关" else "One-Tap Hidden Features",
        subtitle = if (isZh) "无需记忆代码，开启后重启对应三星系统应用（如电话、相机、系统界面）即可生效"
        else "Toggle built-in Samsung One UI features without writing raw XML keys",
    ) {
        ONEUI_HIDDEN_PRESETS.forEachIndexed { index, preset ->
            val isEnabled = csc.customCscFeatures[preset.key].equals(preset.activeValue, ignoreCase = true)
            OneUiItemRow(
                title = if (isZh) preset.titleZh else preset.titleEn,
                subtitle = if (isZh) preset.descZh else preset.descEn,
                icon = Icons.Default.Stars,
                iconTint = IosGlassTokens.IosAmber,
                checked = isEnabled,
                onCheckedChange = { checked ->
                    onUpdateCsc(context.getString(R.string.saved_toast)) { cur ->
                        val nextMap = cur.customCscFeatures.toMutableMap()
                        if (checked) {
                            nextMap[preset.key] = preset.activeValue
                        } else {
                            nextMap.remove(preset.key)
                        }
                        cur.copy(
                            spoofSemCscFeature = true,
                            customCscFeatures = nextMap,
                        )
                    }
                },
            )
            if (index < ONEUI_HIDDEN_PRESETS.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }

    OneUiCollapsibleCard(
        title = if (isZh) "高级入口：自定义 CscFeature 键值" else "Advanced: Custom CscFeature Key-Values",
        subtitle = if (isZh) "已配置 ${csc.customCscFeatures.size} 项自定义 CscFeature 参数"
        else "${csc.customCscFeatures.size} custom CscFeature override(s) configured",
        initiallyExpanded = false,
    ) {
        Text(
            text = if (isZh) "如果你知道具体的三星 CscFeature_ 键名，可前往自定义键值页面按列表添加或通过 JSON 批量导入。"
            else "If you need other CscFeature_ keys, open the custom key-value editor.",
            fontSize = 11.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextButton(
            text = if (isZh) "打开自定义 CscFeature 编辑器" else "Open Custom CscFeature Editor",
            onClick = onOpenCustomCscFeature,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// 7. 自定义键值页（自定义 CscFeature / 自定义 SystemProperties，带完整未保存离开保护）
// ---------------------------------------------------------------------------

@Composable
internal fun CustomKeyValueFeaturePage(
    isCscFeature: Boolean,
    title: String,
    subtitle: String,
    csc: CscConfig,
    xposedReady: Boolean,
    isZh: Boolean,
    onShowGuide: () -> Unit,
    onSaveMap: (Map<String, String>) -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedMap = if (isCscFeature) csc.customCscFeatures else csc.customProps

    var draftMap by remember(savedMap) { mutableStateOf(savedMap) }
    var newKeyInput by rememberSaveable { mutableStateOf("") }
    var newValueInput by rememberSaveable { mutableStateOf("") }
    var jsonText by remember(savedMap) { mutableStateOf(JSONObject(savedMap).toString(2)) }
    var jsonError by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val canonicalDraftJson = remember(draftMap) { JSONObject(draftMap).toString(2) }
    val hasPendingItemInput = newKeyInput.isNotBlank() || newValueInput.isNotBlank()
    val hasUnsavedJsonEdits = jsonText.trim() != canonicalDraftJson.trim()
    val hasUnsavedChanges = draftMap != savedMap || hasPendingItemInput || hasUnsavedJsonEdits

    fun resolveFinalMapOrReportError(): Map<String, String>? {
        val baseMap = if (hasUnsavedJsonEdits) {
            val parsed = runCatching {
                val obj = JSONObject(jsonText.ifBlank { "{}" })
                buildMap {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, obj.optString(k))
                    }
                }
            }.getOrNull()
            if (parsed == null) {
                jsonError = true
                scope.launch {
                    snackbar.showSnackbar(
                        if (isZh) "高级 JSON 格式有误，请修正后再保存"
                        else "Invalid JSON syntax in Advanced Editor. Please fix before saving.",
                    )
                }
                return null
            }
            parsed
        } else {
            draftMap
        }

        return if (newKeyInput.trim().isNotEmpty()) {
            baseMap.toMutableMap().apply {
                put(newKeyInput.trim(), newValueInput.trim())
            }
        } else {
            baseMap
        }
    }

    fun requestBack() {
        if (hasUnsavedChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = hasUnsavedChanges) {
        showUnsavedDialog = true
    }

    MiuixPageScaffold(
        title = title,
        subtitle = subtitle,
        onBack = ::requestBack,
        snackbarHostState = snackbar,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!xposedReady) {
                OneUiNoticeBanner(
                    text = if (isZh) "模块尚未激活：此功能需在 LSPosed 中启用本模块后生效"
                    else "Module not active: enable WeiTiao in LSPosed to use this feature",
                    icon = Icons.Default.Warning,
                    contentColor = IosGlassTokens.IosAmber,
                    actionText = if (isZh) "查看激活步骤" else "How to Activate",
                    onActionClick = onShowGuide,
                )
            }

            OneUiIslandCard(
                title = if (isZh) "添加或修改键值" else "Add or Update Key-Value",
                subtitle = if (isCscFeature) {
                    if (isZh) "示例键名：CscFeature_VoiceCall_ConfigRecording，示例值：RecordingAllowed"
                    else "Example: CscFeature_VoiceCall_ConfigRecording = RecordingAllowed"
                } else {
                    if (isZh) "示例键名：ro.product.Board，示例值：kalama"
                    else "Example: ro.product.Board = kalama"
                },
            ) {
                TextField(
                    value = newKeyInput,
                    onValueChange = { newKeyInput = it },
                    label = if (isZh) "键名 (Key)" else "Key",
                    singleLine = true,
                    insideMargin = DpSize(12.dp, 10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = newValueInput,
                    onValueChange = { newValueInput = it },
                    label = if (isZh) "键值 (Value)" else "Value",
                    singleLine = true,
                    insideMargin = DpSize(12.dp, 10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = if (isZh) "加入列表" else "Add to List",
                    enabled = newKeyInput.trim().isNotEmpty(),
                    onClick = {
                        val k = newKeyInput.trim()
                        val v = newValueInput.trim()
                        val next = draftMap.toMutableMap().apply { put(k, v) }
                        draftMap = next
                        jsonText = JSONObject(next).toString(2)
                        jsonError = false
                        newKeyInput = ""
                        newValueInput = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OneUiIslandCard(
                title = if (isZh) "当前键值列表 (${draftMap.size})" else "Configured Items (${draftMap.size})",
            ) {
                if (draftMap.isEmpty()) {
                    Text(
                        text = if (isZh) "当前没有任何自定义项，可在上方输入键名与键值后点击「加入列表」。"
                        else "No custom overrides yet. Add a key and value above.",
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    val entries = draftMap.entries.toList()
                    entries.forEachIndexed { idx, (k, v) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = k,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = v.ifEmpty { if (isZh) "(空字符串)" else "(empty)" },
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = IosGlassTokens.IosBlue,
                                )
                            }
                            FeatureActionPill(
                                text = if (isZh) "编辑" else "Edit",
                                color = IosGlassTokens.IosBlue,
                                onClick = {
                                    newKeyInput = k
                                    newValueInput = v
                                },
                            )
                            FeatureActionPill(
                                text = if (isZh) "删除" else "Delete",
                                color = IosGlassTokens.IosRed,
                                onClick = {
                                    val next = draftMap.toMutableMap().apply { remove(k) }
                                    draftMap = next
                                    jsonText = JSONObject(next).toString(2)
                                    jsonError = false
                                },
                            )
                        }
                        if (idx < entries.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }

            OneUiIslandCard {
                TextButton(
                    text = if (hasUnsavedChanges) {
                        if (isZh) "保存修改（有未保存更改）" else "Save Changes (Unsaved Edits)"
                    } else {
                        if (isZh) "保存配置" else "Save Configuration"
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val finalMap = resolveFinalMapOrReportError() ?: return@TextButton
                        draftMap = finalMap
                        newKeyInput = ""
                        newValueInput = ""
                        jsonText = JSONObject(finalMap).toString(2)
                        jsonError = false
                        onSaveMap(finalMap)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OneUiCollapsibleCard(
                title = if (isZh) "高级：JSON 批量编辑 / 导入导出" else "Advanced: Raw JSON Batch Editor",
                subtitle = if (isZh) "支持直接粘贴或修改 JSON 格式键值对" else "Paste or edit raw JSON object",
                initiallyExpanded = false,
            ) {
                TextField(
                    value = jsonText,
                    onValueChange = {
                        jsonText = it
                        jsonError = false
                    },
                    label = "JSON",
                    singleLine = false,
                    textStyle = MiuixTheme.textStyles.body2.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                    ),
                    insideMargin = DpSize(12.dp, 10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (jsonError) {
                    Text(
                        text = if (isZh) "JSON 格式有误，请检查大括号、引号与逗号是否成对"
                        else "Invalid JSON format. Please check syntax.",
                        fontSize = 11.sp,
                        color = IosGlassTokens.IosRed,
                    )
                }
                TextButton(
                    text = if (isZh) "解析 JSON 并同步到列表" else "Parse JSON into List",
                    onClick = {
                        val parsed = runCatching {
                            val obj = JSONObject(jsonText.ifBlank { "{}" })
                            buildMap {
                                val keys = obj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    put(k, obj.optString(k))
                                }
                            }
                        }.getOrNull()
                        if (parsed == null) {
                            jsonError = true
                        } else {
                            jsonError = false
                            draftMap = parsed
                            jsonText = JSONObject(parsed).toString(2)
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (isZh) "JSON 解析成功，请点击「保存修改」使其生效"
                                    else "JSON parsed. Tap Save Changes to persist.",
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    WindowDialog(
        show = showUnsavedDialog,
        title = if (isZh) "有未保存的修改" else "Unsaved Changes",
        onDismissRequest = { showUnsavedDialog = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (isZh) "当前页面有尚未保存的键值或 JSON 修改，直接离开将丢失这些更改。"
                else "You have unsaved key-value or JSON changes on this page.",
                fontSize = 12.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = if (isZh) "放弃修改" else "Discard",
                    onClick = {
                        showUnsavedDialog = false
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (isZh) "保存并返回" else "Save & Exit",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val finalMap = resolveFinalMapOrReportError()
                        if (finalMap != null) {
                            onSaveMap(finalMap)
                            showUnsavedDialog = false
                            onBack()
                        } else {
                            showUnsavedDialog = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun PassThroughAppPickerSheet(
    show: Boolean,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(InstalledAppsRepository.peekCachedApps().orEmpty()) }
    LaunchedEffect(show) {
        if (apps.isEmpty()) {
            apps = InstalledAppsRepository.getInstalledLauncherApps(context)
        }
    }

    val filteredApps = remember(searchQuery, apps) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter {
            it.labelLower.contains(q) || it.pkgLower.contains(q)
        }
    }

    var selected by remember(initial, show) { mutableStateOf(initial.toSet()) }

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.choose_pass_through_apps),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = stringResource(R.string.search_app_hint),
                useLabelAsPlaceholder = true,
                singleLine = true,
                insideMargin = DpSize(12.dp, 10.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(16.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (apps.isEmpty()) {
                val isLoadingZh = remember { java.util.Locale.getDefault().language.startsWith("zh") }
                Text(
                    text = if (isLoadingZh) "正在加载已安装应用列表…" else "Loading installed apps…",
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .overScrollVertical(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = app.packageName in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selected = selected.toMutableSet().apply {
                                    if (!add(app.packageName)) remove(app.packageName)
                                }.toSet()
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (app.iconBitmap != null) {
                            Image(
                                bitmap = app.iconBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MiuixTheme.colorScheme.secondaryContainer),
                            )
                        }

                        Column(Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = app.packageName,
                                fontSize = 10.5.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }

                        Checkbox(
                            state = ToggleableState(isChecked),
                            onClick = {
                                selected = selected.toMutableSet().apply {
                                    if (!add(app.packageName)) remove(app.packageName)
                                }.toSet()
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.save_app_selection, selected.size),
                    onClick = { onSave(selected) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}


internal val STEP_TARGET_PRESETS = listOf(6_000, 10_000, 15_000, 20_000)

internal fun parseStepTargetInput(raw: String, min: Int = 1_000, max: Int = 100_000): Int? =
    raw.trim().toIntOrNull()?.takeIf { it in min..max }

internal fun stepModeLabel(mode: String, isZh: Boolean): String = when (mode) {
    TuningConfig.MODE_MULTIPLIER -> if (isZh) "真实步数倍率" else "Real Step Multiplier"
    TuningConfig.MODE_LEGACY_AUTO -> if (isZh) "旧版自动" else "Legacy Auto"
    else -> if (isZh) "每日计划" else "Daily Plan"
}

internal fun stepModeOptionLabel(mode: String, isZh: Boolean): String = when (mode) {
    TuningConfig.MODE_PLAN -> if (isZh) "每日计划（推荐）" else "Daily Plan (Recommended)"
    TuningConfig.MODE_MULTIPLIER -> if (isZh) "真实步数倍率" else "Real Step Multiplier"
    TuningConfig.MODE_LEGACY_AUTO -> if (isZh) "旧版自动" else "Legacy Auto"
    else -> stepModeLabel(mode, isZh)
}

internal fun stepMainTodaySnapshotLine(
    preview: WechatStepPreview?,
    isZh: Boolean,
    numberFmt: NumberFormat = NumberFormat.getIntegerInstance(),
): String? {
    if (preview == null) return null
    return if (preview.target != null) {
        if (isZh) {
            "今日客户端生效 ${numberFmt.format(preview.effective)} 步 · 计划目标 ${numberFmt.format(preview.target)} 步"
        } else {
            "Client today effective ${numberFmt.format(preview.effective)} · Plan target ${numberFmt.format(preview.target)}"
        }
    } else {
        if (isZh) "今日客户端生效 ${numberFmt.format(preview.effective)} 步"
        else "Client today effective ${numberFmt.format(preview.effective)}"
    }
}

internal fun stepRestartGuidanceText(hasRoot: Boolean, isZh: Boolean): String = if (hasRoot) {
    if (isZh) {
        "调整目标、浮动、时间窗口或硬上限通常无需每次重启，微信收到同步配置后会在下次读取时生效；仅在首次启用、切换模式/底层方式，或状态提示「微信待加载最新配置」时，再手动点击重启。重启会中断当前聊天、通话或页面，不会自动执行。"
    } else {
        "Changes to target, variation, window, or hard cap usually apply on the next read without restarting. Restart manually after first enabling, switching mode/backend, or when diagnostics show WeChat is pending the latest config. Restarting interrupts active chats or calls and never runs automatically."
    }
} else {
    if (isZh) "配置已保存；若切换了模式或底层方式，或微信尚未加载最新配置，请手动划掉微信后台并重新打开。"
    else "Configuration saved. If you switched mode/backend or WeChat has not loaded the latest config, force-close and reopen WeChat manually."
}

internal fun stepParameterCardTitle(mode: String, isZh: Boolean): String = when (mode) {
    TuningConfig.MODE_PLAN -> if (isZh) "每日计划目标" else "Daily Plan Target"
    TuningConfig.MODE_MULTIPLIER -> if (isZh) "真实步数倍率" else "Real Step Multiplier"
    TuningConfig.MODE_LEGACY_AUTO -> if (isZh) "旧版自动倍率" else "Legacy Auto Multiplier"
    else -> if (isZh) "步数倍率" else "Step Multiplier"
}

internal fun stepBackendLabel(backend: String, isZh: Boolean): String = when (backend) {
    TuningConfig.BACKEND_AUTO -> if (isZh) "自动" else "Auto"
    TuningConfig.BACKEND_EXACT -> if (isZh) "精确" else "Exact"
    else -> if (isZh) "传感器" else "Sensor"
}

internal fun stepAfterWindowLabel(afterWindow: String, isZh: Boolean): String =
    if (afterWindow == TuningConfig.AFTER_FREEZE) {
        if (isZh) "冻结" else "Freeze"
    } else {
        if (isZh) "自然增长" else "Natural"
    }

internal fun stepMultiplierDisplay(multiplier: Int, isZh: Boolean): String =
    if (isZh) "${multiplier}倍" else "${multiplier}x"

internal fun stepModeSummary(config: TuningConfig, isZh: Boolean): String {
    val mode = stepModeLabel(config.stepMode, isZh)
    return if (config.stepMode == TuningConfig.MODE_PLAN) {
        if (isZh) "$mode · 目标 ${config.stepTarget} ±${config.stepJitter}%"
        else "$mode · Target ${config.stepTarget} ±${config.stepJitter}%"
    } else {
        if (isZh) "$mode · ${stepMultiplierDisplay(config.stepMultiplier, true)} · 硬上限 ${config.stepMaximum}"
        else "$mode · ${stepMultiplierDisplay(config.stepMultiplier, false)} · Cap ${config.stepMaximum}"
    }
}

/** Warn without silently raising or removing the hard cap when varied target can exceed it. */
internal fun stepHardCapWarning(target: Int, jitter: Int, hardCap: Int, isZh: Boolean): String? {
    val base = target.coerceIn(1, 1_000_000).toLong()
    val span = base * jitter.coerceIn(0, 50) / 100L
    val maxVaried = base + span
    val cap = hardCap.coerceAtLeast(1).toLong()
    if (maxVaried <= cap) return null
    val fmt = NumberFormat.getIntegerInstance()
    return if (isZh) {
        "目标受上限限制：浮动最高可达 ${fmt.format(maxVaried)} 步，超过当前硬上限 ${fmt.format(cap)} 步，实际目标将按硬上限截断。"
    } else {
        "Target capped by hard limit: varied maximum ${fmt.format(maxVaried)} exceeds current hard cap ${fmt.format(cap)}."
    }
}

private fun formatStepMinute(minute: Int): String =
    java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

@Composable
private fun StepSettingSlider(
    label: String, display: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, onFinish: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurface)
        Text(text = display, fontSize = 12.5.sp, color = IosGlassTokens.IosGreen)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
        onValueChangeFinished = onFinish, valueRange = range)
}

internal data class WechatStepPreview(val real: Long?, val effective: Long, val target: Long?)

internal data class WechatStepState(val longs: Map<String, Long>, val status: StepHookStatus) {
    fun isTodayPlanUpdated(config: TuningConfig, today: Long = currentDayLong()): Boolean {
        if (config.stepMode != TuningConfig.MODE_PLAN) return true
        return longs["plan_day"] == today && longs["plan_gen"] == config.stepGeneration
    }

    fun preview(config: TuningConfig, today: Long = currentDayLong()): WechatStepPreview? {
        return if (config.stepMode == TuningConfig.MODE_PLAN) {
            if (longs["plan_day"] != today || longs["plan_gen"] != config.stepGeneration) return null
            val real = longs["step_real_today"]?.takeIf { it >= 0L } ?: return null
            val effective = longs[WechatHooks.KEY_STATE_DELIVERED]?.takeIf { it >= 0L } ?: return null
            val target = longs["daily_target"]?.takeIf { it > 0L } ?: return null
            WechatStepPreview(real = real, effective = effective, target = target)
        } else {
            if (longs[WechatHooks.KEY_STATE_TODAY] != today) return null
            // Do not display a PLAN snapshot from today as a MULTIPLIER/LEGACY_AUTO preview before WeChat applies the mode switch.
            if (longs["plan_day"] == today) return null
            if (longs.containsKey("plan_gen") && longs["plan_gen"] != config.stepGeneration) return null
            val effective = longs[WechatHooks.KEY_STATE_DELIVERED]?.takeIf { it >= 0L } ?: return null
            WechatStepPreview(real = null, effective = effective, target = null)
        }
    }

    private companion object {
        fun currentDayLong(): Long = java.util.Calendar.getInstance().let {
            (it.get(java.util.Calendar.YEAR) * 10000 +
                (it.get(java.util.Calendar.MONTH) + 1) * 100 +
                it.get(java.util.Calendar.DAY_OF_MONTH)).toLong()
        }
    }
}

/** Read one Root XML snapshot for both diagnostics and daily preview. */
internal fun readWechatStepHookStatus(): StepHookStatus? = readWechatStepState()?.status

internal fun buildWechatStepStateReadCommand(): String =
    "wt_preview_user=\$(am get-current-user); case \"\$wt_preview_user\" in ''|*[!0-9]*) false;; *) " +
        "wt_rel=\"com.tencent.mm/shared_prefs/weitiao_step_state.xml\"; " +
        "if [ -f \"/data_mirror/data_ce/null/\$wt_preview_user/\$wt_rel\" ]; then cat \"/data_mirror/data_ce/null/\$wt_preview_user/\$wt_rel\"; " +
        "elif [ -f \"/data/user/\$wt_preview_user/\$wt_rel\" ]; then cat \"/data/user/\$wt_preview_user/\$wt_rel\"; " +
        "else nsenter -t 1 -m -- cat \"/data/user/\$wt_preview_user/\$wt_rel\"; fi;; esac"

private fun readWechatStepState(): WechatStepState? = runCatching {
    val (code, xml) = RootShell.exec(
        buildWechatStepStateReadCommand(),
        timeoutSeconds = 4,
    )
    if (code != 0 || xml.isBlank()) return null
    val parser = android.util.Xml.newPullParser()
    parser.setInput(java.io.StringReader(xml))
    val longs = mutableMapOf<String, Long>()
    val strings = mutableMapOf<String, String>()
    val booleans = mutableMapOf<String, Boolean>()
    while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
            val name = parser.getAttributeValue(null, "name")
            if (name != null) when (parser.name) {
                "long", "int" -> parser.getAttributeValue(null, "value")?.toLongOrNull()?.let { longs[name] = it }
                "string" -> strings[name] = parser.nextText()
                "boolean" -> booleans[name] = parser.getAttributeValue(null, "value") == "true"
            }
        }
        parser.next()
    }
    WechatStepState(longs, StepHookStatus.fromValues(strings, longs, booleans))
}.getOrNull()

internal fun stepHeartbeatSummary(health: HeartbeatHealth, isZh: Boolean): String = when (health) {
    HeartbeatHealth.UNKNOWN -> if (isZh) "模块状态未知" else "Module status unknown"
    HeartbeatHealth.HEALTHY -> if (isZh) "模块已加载（不代表步数已生效）" else "Module loaded (does not confirm modified steps)"
    HeartbeatHealth.STALE -> if (isZh) "模块心跳超过 5 分钟未更新" else "Module heartbeat older than 5 minutes"
    HeartbeatHealth.DEAD -> if (isZh) "模块心跳超过 15 分钟未更新" else "Module heartbeat older than 15 minutes"
}

/** Format event timestamp and relative age without allowing negative durations under clock skew. */
internal fun formatStepEventRecency(
    eventAtMillis: Long,
    nowMillis: Long,
    isZh: Boolean,
    timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): String {
    val eventCal = java.util.Calendar.getInstance(timeZone).apply { timeInMillis = eventAtMillis }
    val nowCal = java.util.Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
    val sameDay = eventCal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
        eventCal.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    val timeStr = java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT).apply {
        this.timeZone = timeZone
    }.format(java.util.Date(eventAtMillis))
    if (nowMillis < eventAtMillis) return timeStr
    val diffMs = nowMillis - eventAtMillis
    val rel = when {
        diffMs < 60_000L -> if (isZh) "刚刚" else "just now"
        diffMs < 3_600_000L -> {
            val mins = diffMs / 60_000L
            if (isZh) "${mins}分钟前" else "${mins}m ago"
        }
        diffMs < 86_400_000L -> {
            val hours = diffMs / 3_600_000L
            if (isZh) "${hours}小时前" else "${hours}h ago"
        }
        else -> {
            val days = diffMs / 86_400_000L
            if (isZh) "${days}天前" else "${days}d ago"
        }
    }
    return if (isZh) "$timeStr，$rel" else "$timeStr, $rel"
}

internal fun stepDashboardStatusText(
    summaryLine: String,
    status: StepHookStatus?,
    hasRoot: Boolean,
    nowMillis: Long,
    isZh: Boolean,
    timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): String {
    val health = status?.heartbeatHealth(hasRoot, nowMillis) ?: HeartbeatHealth.UNKNOWN
    val heartbeatLine = stepHeartbeatSummary(health, isZh)
    val eventLine = when {
        !hasRoot || status == null -> if (isZh) "事件记录未知" else "Event record unknown"
        !status.hasStepEvent -> if (isZh) "尚无事件记录" else "No event recorded yet"
        status.lastBackend == TuningConfig.BACKEND_EXACT -> {
            val recency = formatStepEventRecency(status.lastEventAt, nowMillis, isZh, timeZone)
            if (isZh) "最近读取已执行（$recency）" else "Recent read executed ($recency)"
        }
        else -> {
            val recency = formatStepEventRecency(status.lastEventAt, nowMillis, isZh, timeZone)
            if (isZh) "最近计步事件已处理（$recency）" else "Recent step event processed ($recency)"
        }
    }
    return "$summaryLine\n$heartbeatLine\n$eventLine"
}

internal fun stepCompactLoadLabel(
    status: StepHookStatus?,
    hasRoot: Boolean,
    health: HeartbeatHealth,
    isZh: Boolean,
): String = when {
    !hasRoot || status == null -> if (isZh) "状态未知" else "Unknown"
    status.hookStatus == "DISABLED" -> if (isZh) "步数已关闭" else "Step Off"
    health == HeartbeatHealth.HEALTHY -> if (isZh) "模块已加载" else "Module Loaded"
    health == HeartbeatHealth.STALE -> if (isZh) "心跳滞后" else "Stale"
    health == HeartbeatHealth.DEAD -> if (isZh) "心跳超时" else "Timed Out"
    else -> if (isZh) "状态未知" else "Unknown"
}

internal fun stepDiagnosticsCardSubtitle(
    status: StepHookStatus?,
    hasRoot: Boolean,
    health: HeartbeatHealth,
    nowMillis: Long,
    isZh: Boolean,
    timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): String {
    val load = stepCompactLoadLabel(status, hasRoot, health, isZh)
    if (hasRoot && status?.hasStepEvent == true) {
        val recency = formatStepEventRecency(status.lastEventAt, nowMillis, isZh, timeZone)
        val ev = if (status.lastBackend == TuningConfig.BACKEND_EXACT) {
            if (isZh) "最近读取" else "Last read"
        } else {
            if (isZh) "最近事件" else "Last event"
        }
        return "$load · $ev $recency"
    }
    return load
}

/**
 * Separate module load status, recent read execution, and client upload record so heartbeats
 * are never conflated with modified steps or server acceptance.
 */
internal fun stepRuntimeStatusLines(
    status: StepHookStatus?,
    hasRoot: Boolean,
    nowMillis: Long,
    isZh: Boolean,
): Triple<String, String, String> {
    fun time(value: Long) = if (value > 0L) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(value)) else "—"
    val health = status?.heartbeatHealth(hasRoot, nowMillis) ?: HeartbeatHealth.UNKNOWN
    val loadLine = when {
        !hasRoot -> if (isZh) "模块已加载状态：未知（需要 Root 权限读取微信状态）"
        else "Module status: Unknown (Root required to read WeChat state)"
        status == null -> if (isZh) "模块已加载状态：未知（尚未生成或未能读取微信状态文件）"
        else "Module status: Unknown (state file not yet created or unreadable)"
        status.hookStatus == "DISABLED" -> if (isZh) "模块已加载（当前步数修改已关闭）"
        else "Module loaded (step modifier disabled)"
        status.hookStatus == "WAITING_FOR_SENSOR" && health == HeartbeatHealth.HEALTHY ->
            if (isZh) "模块已加载（等待传感器事件，不代表步数已生效）"
            else "Module loaded (waiting for sensor; does not confirm modified steps)"
        health == HeartbeatHealth.HEALTHY -> if (isZh) "模块已加载（心跳正常，不代表步数已生效或服务器已接受）"
        else "Module loaded (heartbeat active; does not imply modified steps or server acceptance)"
        health == HeartbeatHealth.STALE -> if (isZh) "模块心跳超过 5 分钟未更新"
        else "Module heartbeat older than 5 minutes"
        health == HeartbeatHealth.DEAD -> if (isZh) "模块心跳超过 15 分钟未更新"
        else "Module heartbeat older than 15 minutes"
        else -> if (isZh) "模块已加载状态：未知" else "Module status: Unknown"
    }

    val readLine = when {
        !hasRoot || status == null -> if (isZh) "最近读取记录：未知" else "Recent read: Unknown"
        !status.hasStepEvent -> if (isZh) "最近读取记录：尚未观察到读取事件（心跳独立于计步事件）"
        else "Recent read: No read event observed yet (heartbeat is independent)"
        status.lastBackend == TuningConfig.BACKEND_EXACT -> if (isZh) {
            "最近读取已执行（${time(status.lastEventAt)}）：微信原始业务步数 ${status.lastOriginal} → 生效步数 ${status.lastEffective}"
        } else {
            "Recent read executed (${time(status.lastEventAt)}): WeChat original business steps ${status.lastOriginal} → effective steps ${status.lastEffective}"
        }
        else -> if (isZh) {
            "最近计步事件已处理（${time(status.lastEventAt)} · ${if (status.lastEventSynthetic) "合成事件" else "真实传感器"}）：传感器累计原始值 ${status.lastOriginal} → 输出值 ${status.lastEffective}"
        } else {
            "Recent step event processed (${time(status.lastEventAt)} · ${if (status.lastEventSynthetic) "synthetic" else "sensor"}): Cumulative sensor original ${status.lastOriginal} → output ${status.lastEffective}"
        }
    }

    val uploadLine = if (isZh) "上报验证：尚未采集" else "Upload verification: Not captured yet"
    return Triple(loadLine, readLine, uploadLine)
}

internal fun stepPreviewSummary(
    preview: WechatStepPreview?,
    status: StepHookStatus?,
    hasRoot: Boolean,
    isZh: Boolean,
    numberFmt: NumberFormat = NumberFormat.getIntegerInstance(),
): String = when {
    !hasRoot -> if (isZh) "客户端今日快照：不可用（未获得 Root 权限）" else "Client snapshot: unavailable (Root required)"
    preview == null -> if (isZh) "客户端今日快照：尚无今日有效状态，或微信尚未应用当前设置" else "Client snapshot: no valid state for today, or settings not yet applied in WeChat"
    else -> {
        val realText = preview.real?.let {
            if (isZh) "${numberFmt.format(it)} 步" else numberFmt.format(it)
        } ?: if (isZh) "不可用" else "Unavailable"
        val target = preview.target?.let { numberFmt.format(it) }
            ?: if (isZh) "不适用" else "N/A"
        if (status?.lastBackend == TuningConfig.BACKEND_EXACT) {
            if (isZh) "客户端今日快照：微信原始业务步数 $realText → 生效步数 ${numberFmt.format(preview.effective)} 步\n今日浮动目标 $target（仅客户端记录，不代表服务器已接受）"
            else "Client snapshot: WeChat original business steps $realText → effective steps ${numberFmt.format(preview.effective)}\nToday's target $target (client record only; does not imply server acceptance)"
        } else {
            if (isZh) "客户端今日快照：传感器累计原始值 $realText → 输出值 ${numberFmt.format(preview.effective)} 步\n今日目标 $target（仅客户端记录，不代表服务器已接受）"
            else "Client snapshot: Cumulative sensor original $realText → output ${numberFmt.format(preview.effective)}\nToday's target $target (client record only; does not imply server acceptance)"
        }
    }
}

internal fun stepConfigSyncStatusLine(
    status: StepHookStatus?,
    hasRoot: Boolean,
    isZh: Boolean,
    expectedConfig: TuningConfig?,
    remoteSynced: Boolean?,
    planUpdated: Boolean? = null,
): String {
    val localSummary = expectedConfig?.let {
        if (isZh) "（本地：mode=${it.stepMode} · backend=${it.stepBackend} · gen=${it.stepGeneration}）"
        else " (Local: mode=${it.stepMode} · backend=${it.stepBackend} · gen=${it.stepGeneration})"
    }.orEmpty()
    return when {
        remoteSynced == false -> if (isZh) {
            "仅本地保存，尚未同步到 LSPosed$localSummary"
        } else {
            "Saved locally only; not synced to LSPosed$localSummary"
        }
        remoteSynced == null -> if (isZh) "配置同步状态未知" else "Config sync status unknown"
        expectedConfig == null -> ""
        !hasRoot || status == null -> if (isZh) "配置同步状态未知" else "Config sync status unknown"
        status.configMode.isBlank() || status.configBackend.isBlank() || status.configGeneration == null ->
            if (isZh) "配置同步状态未知" else "Config sync status unknown"
        !status.isConfigApplied(expectedConfig) -> if (isZh) {
            "已同步到 LSPosed，微信待加载最新配置$localSummary"
        } else {
            "Synced to LSPosed; WeChat pending latest config$localSummary"
        }
        expectedConfig.stepMode == TuningConfig.MODE_PLAN && planUpdated == false -> if (isZh) {
            "最新配置已加载，等待下一次计步读取"
        } else {
            "Latest config loaded; waiting for next step read"
        }
        else -> ""
    }
}

internal fun stepHookDetail(
    status: StepHookStatus?,
    hasRoot: Boolean,
    isZh: Boolean,
    expectedConfig: TuningConfig? = null,
    remoteSynced: Boolean? = true,
    planUpdated: Boolean? = null,
): String {
    val syncLine = stepConfigSyncStatusLine(status, hasRoot, isZh, expectedConfig, remoteSynced, planUpdated)
    val syncSuffix = if (syncLine.isBlank()) "" else "\n$syncLine"
    if (!hasRoot) {
        return (if (isZh) "Hook 状态未知；需要 Root 读取微信状态。" else "Hook status unknown; Root is required to read WeChat state.") + syncSuffix
    }
    if (status == null) {
        return (if (isZh) "Hook 状态未知；已获得 Root，但尚未生成或未能读取微信状态文件。" else "Hook status unknown; Root granted, but WeChat state file is not yet created or could not be read.") + syncSuffix
    }
    fun time(value: Long) = if (value > 0L) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(value)) else "—"
    val event = if (status.hasStepEvent) {
        if (status.lastBackend == TuningConfig.BACKEND_EXACT) {
            if (isZh) "最近读取已执行：${time(status.lastEventAt)} · EXACT\n微信原始业务步数 ${status.lastOriginal} → 生效步数 ${status.lastEffective}"
            else "Last read executed: ${time(status.lastEventAt)} · EXACT\nWeChat original business steps ${status.lastOriginal} → effective steps ${status.lastEffective}"
        } else {
            if (isZh) "最近计步事件已处理：${time(status.lastEventAt)} · ${if (status.lastEventSynthetic) "合成" else "传感器"}\n传感器累计原始值 ${status.lastOriginal} → 输出值 ${status.lastEffective}"
            else "Last step event processed: ${time(status.lastEventAt)} · ${if (status.lastEventSynthetic) "synthetic" else "sensor"}\nCumulative sensor original ${status.lastOriginal} → output ${status.lastEffective}"
        }
    } else if (isZh) "尚未观察到计步事件；心跳独立于计步事件。" else "No step event observed; heartbeat runs independently of step events."
    val displayReason = if (status.lastBackend == TuningConfig.BACKEND_EXACT && status.backendReason.contains("尚未真机运行验证")) {
        if (isZh) "Play 版微信 8.0.80 / 3200 已通过真机验证：计划重建、首读无突跳、窗口内渐进增长。"
        else "Play WeChat 8.0.80 / 3200 verified on device: plan rebuild, zero initial jump, and smooth in-window progression."
    } else {
        status.backendReason
    }
    val exactPendingLine = if (status.lastBackend == TuningConfig.BACKEND_EXACT) {
        if (isZh) "\n上传入口、服务端排行榜及窗口结束后的行为待验证"
        else "\nPending verification: upload entry, server leaderboard, and post-window behavior"
    } else {
        ""
    }
    val actualMode = status.configMode.ifBlank { if (isZh) "未知" else "Unknown" }
    val actualBackend = status.configBackend.ifBlank { if (isZh) "未知" else "Unknown" }
    val actualGen = status.configGeneration?.toString() ?: if (isZh) "未知" else "Unknown"
    val configLine = if (isZh) {
        "\n微信实际读取：mode=$actualMode · backend=$actualBackend · gen=$actualGen"
    } else {
        "\nWeChat config read: mode=$actualMode · backend=$actualBackend · gen=$actualGen"
    }
    val newerActivity = maxOf(status.lastEventAt, status.hookAliveAt) > status.lastErrorAt && status.lastErrorAt > 0L
    val error = if (status.lastError.isBlank()) "" else if (isZh) {
        "\n最近历史异常（${time(status.lastErrorAt)}）：${status.lastError}" +
            if (newerActivity) "\n异常后仍有心跳或事件记录。" else "\n尚无更新活动记录，请刷新检查。"
    } else "\nLast recorded error (${time(status.lastErrorAt)}): ${status.lastError}" +
        if (newerActivity) "\nA heartbeat or event was recorded after this error." else "\nNo newer activity recorded; refresh to check."
    return if (isZh) "Hook：${status.hookStatus.ifBlank { "未知" }} · 心跳 ${time(status.hookAliveAt)}\n模块 ${status.hookVersion.ifBlank { "未知" }} · 微信 ${status.wechatVersion.ifBlank { "未知" }}\n后端 ${status.lastBackend.ifBlank { "未知" }} · $displayReason$exactPendingLine$configLine$syncSuffix\n$event$error"
    else "Hook: ${status.hookStatus.ifBlank { "Unknown" }} · Heartbeat ${time(status.hookAliveAt)}\nModule ${status.hookVersion.ifBlank { "Unknown" }} · WeChat ${status.wechatVersion.ifBlank { "Unknown" }}\nBackend ${status.lastBackend.ifBlank { "Unknown" }} · $displayReason$exactPendingLine$configLine$syncSuffix\n$event$error"
}
