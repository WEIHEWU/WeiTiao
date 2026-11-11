package com.weitiao.tuning.ui.screens

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.TuningConfig
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiCollapsibleCard
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.OneUiSegmentedControl
import java.text.NumberFormat
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
    var endHourDraft by remember(tuning.stepEndHour) { mutableFloatStateOf(tuning.stepEndHour.toFloat()) }

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
        OneUiIslandCard {
            OneUiItemRow(
                title = if (isZh) "启用微信步数修改" else "Enable WeChat Step Modifier",
                subtitle = if (tuning.stepEnabled) {
                    if (isZh) {
                        val modeLabel = if (tuning.stepAuto) "自动递增" else "固定放大"
                        "已开启 · $modeLabel · ${tuning.stepMultiplier}倍（上限 ${numberFmt.format(tuning.stepMaximum)} 步）"
                    } else {
                        "Active · ${tuning.stepMultiplier}x (Cap ${numberFmt.format(tuning.stepMaximum)})"
                    }
                } else {
                    if (isZh) "已关闭，微信运动读取系统原始计步传感器步数"
                    else "Disabled. WeChat reads real step sensor data"
                },
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                iconTint = IosGlassTokens.IosGreen,
                checked = tuning.stepEnabled,
                onCheckedChange = { enabled ->
                    onUpdateTuning(
                        if (enabled) {
                            if (isZh) "已开启微信步数修改" else "WeChat step modifier enabled"
                        } else {
                            if (isZh) "已关闭微信步数修改" else "WeChat step modifier disabled"
                        },
                    ) { it.copy(stepEnabled = enabled) }
                },
            )
        }

        // 常用档位始终可见：支持一键点选直接开启并套用档位参数
        OneUiIslandCard(
            title = if (isZh) "常用档位一键选择" else "Quick Step Presets",
            subtitle = if (tuning.stepEnabled) {
                if (isZh) "自动匹配推荐倍率与每日封顶步数" else "Automatically sets recommended multiplier and daily cap"
            } else {
                if (isZh) "点击任意档位即可直接开启步数修改并套用参数" else "Tap any preset below to enable and apply in one step"
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                STEP_QUICK_TIERS.forEach { tier ->
                    val isSelected = tuning.stepEnabled &&
                        tuning.stepMultiplier == tier.multiplier &&
                        tuning.stepMaximum == tier.recommendedCap
                    FeatureSelectTile(
                        title = if (isZh) tier.titleZh else tier.titleEn,
                        subtitle = if (isZh) "上限 ${tier.recommendedCap / 10000.0}万" else "Cap ${tier.recommendedCap}",
                        selected = isSelected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            multiplierDraft = tier.multiplier.toFloat()
                            maxStepDraft = tier.recommendedCap.toFloat()
                            onUpdateTuning(context.getString(R.string.saved_toast)) {
                                it.copy(
                                    stepEnabled = true,
                                    stepMultiplier = tier.multiplier,
                                    stepMaximum = tier.recommendedCap,
                                )
                            }
                        },
                    )
                }
            }
        }

        if (tuning.stepEnabled) {
            OneUiIslandCard(
                title = if (isZh) "生效方式与每日上限" else "Mode & Daily Cap",
            ) {
                OneUiItemRow(
                    title = if (isZh) "随时间平滑递增（推荐）" else "Smooth Time-Based Auto Increment",
                    subtitle = if (tuning.stepAuto) {
                        if (isZh) "根据全天时间进度结合真实走路自动平滑累加步数，更自然"
                        else "Smoothly increments steps throughout the day alongside real walking"
                    } else {
                        if (isZh) "当前为固定倍率模式：每次真实走动直接乘上倍率"
                        else "Fixed multiplier mode: multiplies each real step directly"
                    },
                    checked = tuning.stepAuto,
                    onCheckedChange = { auto ->
                        onUpdateTuning(null) { it.copy(stepAuto = auto) }
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isZh) "每日最高步数封顶" else "Daily Step Cap",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (isZh) "${numberFmt.format(maxStepDraft.toInt())} 步"
                        else "${numberFmt.format(maxStepDraft.toInt())} steps",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosGlassTokens.IosGreen,
                    )
                }
                Slider(
                    value = maxStepDraft,
                    onValueChange = { maxStepDraft = (it / 1000f).toInt() * 1000f },
                    onValueChangeFinished = {
                        onUpdateTuning(null) { it.copy(stepMaximum = maxStepDraft.toInt().coerceIn(5000, 98800)) }
                    },
                    valueRange = 5000f..98800f,
                )
            }

            OneUiCollapsibleCard(
                title = if (isZh) "高级设置：自定义倍率与截止时间" else "Advanced: Multiplier & Cutoff Hour",
                subtitle = if (isZh) "当前倍率 ${tuning.stepMultiplier}x · 每日 ${tuning.stepEndHour}:00 停止递增"
                else "Multiplier ${tuning.stepMultiplier}x · Stops at ${tuning.stepEndHour}:00",
                initiallyExpanded = false,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isZh) "步数放大倍率" else "Step Multiplier",
                        fontSize = 12.5.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${multiplierDraft.toInt()}x",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosGlassTokens.IosBlue,
                    )
                }
                Slider(
                    value = multiplierDraft,
                    onValueChange = { multiplierDraft = it.toInt().toFloat() },
                    onValueChangeFinished = {
                        onUpdateTuning(null) { it.copy(stepMultiplier = multiplierDraft.toInt().coerceIn(1, 200)) }
                    },
                    valueRange = 1f..200f,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isZh) "每日停止增长时间" else "Daily Stop Hour",
                        fontSize = 12.5.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${endHourDraft.toInt()}:00",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosGlassTokens.IosBlue,
                    )
                }
                Slider(
                    value = endHourDraft,
                    onValueChange = { endHourDraft = it.toInt().toFloat() },
                    onValueChangeFinished = {
                        onUpdateTuning(null) { it.copy(stepEndHour = endHourDraft.toInt().coerceIn(18, 24)) }
                    },
                    valueRange = 18f..24f,
                )
            }
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
                    onUpdateTuning(
                        if (enabled) {
                            if (isZh) "已开启微信消息防撤回" else "WeChat anti-recall enabled"
                        } else {
                            if (isZh) "已关闭微信消息防撤回" else "WeChat anti-recall disabled"
                        },
                    ) { it.copy(antiRecall = enabled) }
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
    }

    OneUiIslandCard(
        title = if (isZh) "生效操作" else "Apply to WeChat",
    ) {
        Text(
            text = if (hasRoot) {
                if (isZh) "请确保已在 LSPosed 作用域中勾选「微信 (com.tencent.mm)」。点击下方按钮可通过 Root 立即重启微信生效。"
                else "Ensure WeChat (com.tencent.mm) is checked in LSPosed scope. Tap below to restart WeChat via Root."
            } else {
                if (isZh) "配置已自动保存；当前无 Root 权限，请确认已在 LSPosed 中勾选微信，然后手动划掉微信后台并重新打开即可生效。"
                else "Configuration saved. Without Root, ensure WeChat is checked in LSPosed and restart WeChat manually."
            },
            fontSize = 11.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextButton(
            text = if (isZh) "立即重启微信" else "Restart WeChat Now",
            enabled = hasRoot,
            onClick = onRequestRestartWechat,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
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
        mutableFloatStateOf(tuning.thermalCutoffCelsius.toFloat())
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
                    if (isZh) "已开启 · 电池达到 ${tuning.thermalCutoffCelsius}°C 或息屏时自动恢复系统温控"
                    else "Enabled · Auto-restores system thermal control at ${tuning.thermalCutoffCelsius}°C or screen off"
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
                text = if (isZh) "开启后可在亮屏使用手机时解除三星充电功率限制，内置温控熔断保护，达到安全温度阈值或息屏后立即还原系统温控。"
                else "Unlocks full fast-charge speed while screen is on, with automatic thermal safety cutoff.",
                icon = Icons.Default.Bolt,
                contentColor = IosGlassTokens.IosAmber,
            )
        } else {
            OneUiIslandCard(
                title = if (isZh) "安全温控阈值（推荐 44°C）" else "Thermal Safety Cutoff (Recommended 44°C)",
                subtitle = if (isZh) "电池温度达到该阈值时立即停止快充解锁并恢复系统温控保护"
                else "Immediately restores system thermal protection when battery reaches this temperature",
            ) {
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
                        text = "${thermalDraft.toInt()}°C",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (thermalDraft.toInt() >= 46) IosGlassTokens.IosAmber else IosGlassTokens.IosGreen,
                    )
                }
                Slider(
                    value = thermalDraft,
                    onValueChange = { thermalDraft = it.toInt().toFloat() },
                    onValueChangeFinished = {
                        val nextTemp = thermalDraft.toInt().coerceIn(38, 48)
                        onUpdateTuning(null) { it.copy(thermalCutoffCelsius = nextTemp) }
                    },
                    valueRange = 38f..48f,
                )
                if (tuning.thermalCutoffCelsius != 44) {
                    TextButton(
                        text = if (isZh) "恢复推荐设置 (44°C)" else "Restore Recommended (44°C)",
                        onClick = {
                            thermalDraft = 44f
                            onUpdateTuning(context.getString(R.string.saved_toast)) {
                                it.copy(thermalCutoffCelsius = 44)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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

    PassThroughAppPickerSheet(
        show = showPassAppPicker,
        initial = selectedPassApps,
        onDismiss = { showPassAppPicker = false },
        onSave = { updated ->
            onUpdatePassApps(updated)
            showPassAppPicker = false
        },
    )
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

private data class PassAppPickerEntry(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

@Composable
internal fun PassThroughAppPickerSheet(
    show: Boolean,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                PassAppPickerEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = runCatching { it.loadIcon(pm) }.getOrNull(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    val filteredApps = remember(searchQuery, apps) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
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
                        if (app.icon != null) {
                            Image(
                                bitmap = app.icon.toBitmap(84, 84).asImageBitmap(),
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
