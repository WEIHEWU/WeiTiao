package com.weitiao.tuning.ui.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weitiao.tuning.App
import com.weitiao.tuning.BuildConfig
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.HookLiveness
import com.weitiao.tuning.core.LsposedScopeHelper
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.StoreCleaner
import com.weitiao.tuning.core.TuningConfig
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.data.CscEntry
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixHeroStatusCard
import com.weitiao.tuning.ui.components.MiuixMetricCard
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.MiuixQuickTile
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.OneUiSegmentedControl
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.theme.OneUiTokens
import com.weitiao.tuning.tuning.FastChargeController
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.WindowBottomSheet
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private enum class ModuleState { NOT_CONNECTED, NO_SCOPE, ACTIVE }

private const val PANEL_SPOOF = 0
private const val PANEL_STEPS = 1
private const val PANEL_POWER = 2

private data class CompactPreset(
    val labelZh: String,
    val labelEn: String,
    val nameRes: Int,
    val salesCode: String,
    val simMcc: String,
    val countryCode: String,
    val numeric: String,
    val operator: String,
    val mnc: String,
)

private val QUICK_REGION_PRESETS = listOf(
    CompactPreset("香港", "HK", R.string.preset_hk_name, "TGY", "454", "HK", "45406", "SmarTone", "06"),
    CompactPreset("美国", "US", R.string.preset_us_name, "XAA", "310", "US", "310260", "T-Mobile", "260"),
    CompactPreset("欧洲", "EU", R.string.preset_eu_name, "EUX", "262", "DE", "26201", "T-Mobile", "01"),
    CompactPreset("台湾", "TW", R.string.preset_tw_name, "BRI", "466", "TW", "46688", "Telecom", "88"),
    CompactPreset("韩国", "KR", R.string.preset_kr_name, "KOO", "450", "KR", "45005", "SKT", "05"),
    CompactPreset("大陆", "CN", R.string.preset_cn_name, "CHC", "460", "CN", "46000", "CHINA MOBILE", "00"),
)

private data class StepTierPreset(
    val multiplier: Int,
    val recommendedCap: Int,
    val titleZh: String,
    val titleEn: String,
)

private val STEP_TIER_PRESETS = listOf(
    StepTierPreset(multiplier = 3, recommendedCap = 28000, titleZh = "3x 轻度", titleEn = "3x Light"),
    StepTierPreset(multiplier = 10, recommendedCap = 50000, titleZh = "10x 日常", titleEn = "10x Daily"),
    StepTierPreset(multiplier = 25, recommendedCap = 68000, titleZh = "25x 进阶", titleEn = "25x Boost"),
    StepTierPreset(multiplier = 50, recommendedCap = 88888, titleZh = "50x 极速", titleEn = "50x Max"),
)

private data class LaunchableAppEntry(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

@Composable
fun DashboardScreen(
    refreshTrigger: Int = 0,
    pendingSelection: CscEntry? = null,
    onConsumeSelection: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onConfigChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isZh = remember { Locale.getDefault().language.startsWith("zh") }
    val numFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }

    var csc by remember { mutableStateOf(CscPrefs.load(context)) }
    var tuning by remember { mutableStateOf(TuningPrefs.load(context)) }

    // 下方参数面板分段切换（避免把所有卡片同时堆叠在一个长页面）
    var activePanel by rememberSaveable { mutableIntStateOf(PANEL_SPOOF) }

    var multiplier by remember(tuning.stepMultiplier) { mutableStateOf(tuning.stepMultiplier.toString()) }
    var maximum by remember(tuning.stepMaximum) { mutableStateOf(tuning.stepMaximum.toString()) }
    var endHour by remember(tuning.stepEndHour) { mutableStateOf(tuning.stepEndHour.toString()) }
    var passThroughSlider by remember(tuning.passThroughLimit) {
        mutableFloatStateOf(tuning.passThroughLimit.toFloat())
    }
    var selectedPassApps by remember { mutableStateOf(TuningPrefs.selectedApps(context)) }

    var showDeviceModelPicker by remember { mutableStateOf(false) }
    var showDeviceTargetPicker by remember { mutableStateOf(false) }
    var showPassAppPicker by remember { mutableStateOf(false) }
    var showPresetConfirmDialog by remember { mutableStateOf<CompactPreset?>(null) }
    var lastConfirmPreset by remember { mutableStateOf(QUICK_REGION_PRESETS.first()) }
    showPresetConfirmDialog?.let { lastConfirmPreset = it }

    val initialConnected = App.getXposedService() != null || App.getRemotePrefs() != null
    var moduleState by remember {
        mutableStateOf(if (initialConnected) ModuleState.ACTIVE else ModuleState.NOT_CONNECTED)
    }
    var liveness by remember { mutableStateOf<Set<String>?>(null) }
    var hasRoot by remember { mutableStateOf(false) }
    var regionCleanupPending by remember { mutableStateOf(RegionApplyState.isPending(context)) }

    // 实时电池温度与充电状态监听
    var batteryTemp by remember { mutableFloatStateOf(34.5f) }
    var batteryLevel by remember { mutableIntStateOf(80) }
    var isPlugged by remember { mutableStateOf(false) }

    fun refreshHardwareMetrics() {
        scope.launch(Dispatchers.IO) {
            val metrics = FastChargeController.readBatteryMetrics(context)
            if (metrics.temperatureTenths > 0) {
                batteryTemp = metrics.temperatureTenths / 10f
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                if (tempTenths > 0) {
                    batteryTemp = tempTenths / 10f
                } else {
                    refreshHardwareMetrics()
                }
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 80)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                batteryLevel = (level * 100) / scale
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                isPlugged = plugged != 0
            }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            val initTemp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            if (initTemp > 0) {
                batteryTemp = initTemp / 10f
            } else {
                refreshHardwareMetrics()
            }
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, 80)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            batteryLevel = (level * 100) / scale
            isPlugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val batteryRestricted by remember(resumeTick) {
        mutableStateOf(TuningPrefs.isBatteryOptimizationRestricted(context))
    }

    LaunchedEffect(resumeTick, isPlugged, tuning.brightScreenFastCharge) {
        withContext(Dispatchers.IO) {
            val metrics = FastChargeController.readBatteryMetrics(context)
            if (metrics.temperatureTenths > 0) {
                batteryTemp = metrics.temperatureTenths / 10f
            }
        }
        if (isPlugged || tuning.brightScreenFastCharge) {
            while (isActive) {
                delay(4000)
                val metrics = withContext(Dispatchers.IO) { FastChargeController.readBatteryMetrics(context) }
                if (metrics.temperatureTenths > 0) {
                    batteryTemp = metrics.temperatureTenths / 10f
                }
            }
        }
    }

    fun refreshModuleState() {
        if (App.getXposedService() == null && App.getRemotePrefs() == null) {
            moduleState = ModuleState.NOT_CONNECTED
            return
        }
        val pkgs = liveness
        moduleState = if (pkgs != null && pkgs.isEmpty()) ModuleState.NO_SCOPE else ModuleState.ACTIVE
    }

    fun refreshState() {
        csc = CscPrefs.load(context)
        tuning = TuningPrefs.load(context)
        selectedPassApps = TuningPrefs.selectedApps(context)
        regionCleanupPending = RegionApplyState.isPending(context)
        refreshModuleState()
    }

    fun updateTuning(block: (TuningConfig) -> TuningConfig) {
        val next = block(tuning)
        tuning = next
        TuningPrefs.save(context, next)
        onConfigChanged()
    }

    fun updateCsc(toastMessage: String? = null, block: (CscConfig) -> CscConfig) {
        val prev = csc
        val next = block(csc)
        csc = next
        CscPrefs.save(context, next)
        if (prev.enabled != next.enabled || prev.salesCode != next.salesCode || prev.simMcc != next.simMcc) {
            RegionApplyState.markPending(context)
            regionCleanupPending = true
        }
        onConfigChanged()
        toastMessage?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    }

    LaunchedEffect(refreshTrigger) {
        refreshState()
    }

    LaunchedEffect(Unit) {
        hasRoot = RootShell.hasRoot()
        withContext(Dispatchers.IO) {
            val pkgs = HookLiveness.recentHookedPackages()
            withContext(Dispatchers.Main) {
                liveness = pkgs
                refreshModuleState()
            }
        }
    }

    // 从全球 80 国检索库返回后自动应用并清理商店
    LaunchedEffect(pendingSelection) {
        pendingSelection?.let { entry ->
            activePanel = PANEL_SPOOF
            updateCsc(context.getString(R.string.preset_applied_toast, "${entry.country} (${entry.code})")) {
                it.copy(
                    enabled = true,
                    salesCode = entry.code,
                    countryCode = entry.countryCode,
                    simMcc = entry.mcc,
                    simMnc = if (entry.numeric.length > 3) entry.numeric.drop(3) else "01",
                    simNumeric = entry.numeric,
                    simOperatorName = entry.operator,
                    simCountryIso = entry.countryCode.lowercase(),
                    marketUnlockRaw = "${entry.numeric},${entry.operator},${entry.countryCode.lowercase()},${entry.country}",
                    spoofSimInfo = true,
                    spoofSalesCode = true,
                    spoofCountryCode = true,
                    spoofRilSalesCode = true,
                    spoofOmcCodes = true,
                    spoofSemCscFeature = true,
                )
            }
            val cleaned = withContext(Dispatchers.IO) { StoreCleaner.clearStoreDataRoot() }
            if (cleaned) {
                RegionApplyState.markApplied(context)
                regionCleanupPending = false
            }
            onConsumeSelection()
        }
    }

    val isServiceReady = moduleState == ModuleState.ACTIVE || hasRoot
    val enabledText = if (isZh) "已启用" else "On"
    val disabledText = if (isZh) "未启用" else "Off"

    MiuixPageScaffold(
        title = stringResource(R.string.topbar_title),
        snackbarHostState = snackbar,
        actions = {
            IconButton(
                onClick = {
                    haptics.tap()
                    onOpenSettings()
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (regionCleanupPending) {
                OneUiNoticeBanner(
                    text = stringResource(R.string.region_changed_banner),
                    icon = Icons.Default.CleaningServices,
                    containerColor = OneUiTokens.WarningAmberContainerLight,
                    contentColor = IosGlassTokens.IosAmber,
                    actionText = stringResource(R.string.action_clear_now),
                    onActionClick = {
                        haptics.confirm()
                        scope.launch {
                            val success = withContext(Dispatchers.IO) { StoreCleaner.clearStoreDataRoot() }
                            if (success) {
                                RegionApplyState.markApplied(context)
                                regionCleanupPending = false
                                snackbar.showSnackbar(context.getString(R.string.clear_store_success))
                            } else {
                                snackbar.showSnackbar(context.getString(R.string.clear_store_fail))
                            }
                        }
                    },
                )
            }

            if ((tuning.brightScreenFastCharge || tuning.passThrough) && batteryRestricted) {
                OneUiNoticeBanner(
                    text = stringResource(R.string.battery_optimization_warning),
                    icon = Icons.Default.BatteryAlert,
                    containerColor = OneUiTokens.WarningAmberContainerLight,
                    contentColor = IosGlassTokens.IosAmber,
                    actionText = stringResource(R.string.battery_optimization_action),
                    onActionClick = {
                        haptics.confirm()
                        TuningPrefs.requestIgnoreBatteryOptimization(context)
                    },
                )
            }

            // 第一层：顶部紧凑状态大卡（融合实时温度与电量）
            val modeLabel = if (moduleState == ModuleState.ACTIVE) {
                if (isZh) "LSPosed 推荐模式" else "LSPosed Mode"
            } else {
                if (isZh) "Root 硬件直控" else "Root Mode"
            }
            val powerTag = if (isPlugged) {
                if (isZh) "充电中 $batteryLevel%" else "Charging $batteryLevel%"
            } else {
                if (isZh) "电量 $batteryLevel%" else "Battery $batteryLevel%"
            }
            MiuixHeroStatusCard(
                statusTitle = when {
                    moduleState == ModuleState.ACTIVE -> if (isZh) "运行中" else "Active"
                    hasRoot -> if (isZh) "Root 就绪" else "Root Ready"
                    else -> if (isZh) "待激活" else "Standby"
                },
                pillText = "v${BuildConfig.VERSION_NAME}",
                line1Text = "$modeLabel · ${String.format(Locale.US, "%.1f", batteryTemp)}°C · $powerTag",
                line2Text = if (isZh) {
                    "物理 SIM 与基带 100% 原生免注入"
                } else {
                    "Physical SIM & baseband 100% native"
                },
                isWarning = !isServiceReady,
                onClick = onOpenSettings,
            )

            // 第二层：全应用唯一的 3×2 功能总开关磁贴（点击切换开关，并自动联动下方配置面板）
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiuixQuickTile(
                        label = if (isZh) "商店跨区" else "Store",
                        statusText = if (csc.enabled && csc.salesCode.isNotBlank()) {
                            "$enabledText · ${csc.salesCode}"
                        } else {
                            disabledText
                        },
                        icon = Icons.Default.Public,
                        active = csc.enabled && csc.salesCode.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            activePanel = PANEL_SPOOF
                            if (csc.salesCode.isNotBlank()) {
                                updateCsc { it.copy(enabled = !it.enabled) }
                            } else {
                                onOpenSearch()
                            }
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "机型伪装" else "Model",
                        statusText = if (csc.spoofDevice) enabledText else disabledText,
                        icon = Icons.Default.Smartphone,
                        active = csc.spoofDevice,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            activePanel = PANEL_SPOOF
                            if (csc.deviceName.isNotBlank()) {
                                updateCsc { it.copy(spoofDevice = !it.spoofDevice) }
                            } else {
                                showDeviceModelPicker = true
                            }
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "微信计步" else "Steps",
                        statusText = if (tuning.stepEnabled) {
                            "$enabledText · ${tuning.stepMultiplier}x"
                        } else {
                            disabledText
                        },
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        active = tuning.stepEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            activePanel = PANEL_STEPS
                            updateTuning { it.copy(stepEnabled = !it.stepEnabled) }
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiuixQuickTile(
                        label = if (isZh) "防撤回" else "Recall",
                        statusText = if (tuning.antiRecall) enabledText else disabledText,
                        icon = Icons.Default.Shield,
                        active = tuning.antiRecall,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            updateTuning { it.copy(antiRecall = !it.antiRecall) }
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "亮屏快充" else "Charge",
                        statusText = if (tuning.brightScreenFastCharge) enabledText else disabledText,
                        icon = Icons.Default.Bolt,
                        active = tuning.brightScreenFastCharge,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            activePanel = PANEL_POWER
                            val next = !tuning.brightScreenFastCharge
                            updateTuning { it.copy(brightScreenFastCharge = next) }
                            if (next) requestNotificationPermissionIfNeeded()
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "旁路供电" else "Bypass",
                        statusText = if (tuning.passThrough) {
                            "$enabledText · ${tuning.passThroughLimit}%"
                        } else {
                            disabledText
                        },
                        icon = Icons.Default.Power,
                        active = tuning.passThrough,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            activePanel = PANEL_POWER
                            val next = !tuning.passThrough
                            updateTuning { it.copy(passThrough = next) }
                            if (next) requestNotificationPermissionIfNeeded()
                        },
                    )
                }
            }

            // 第三层：轻量三段式面板切换器（每次仅展示 1 张参数卡片，彻底告别超长堆叠页面）
            OneUiSegmentedControl(
                items = listOf(PANEL_SPOOF, PANEL_STEPS, PANEL_POWER),
                selectedItem = activePanel,
                onSelect = { activePanel = it },
                label = { panel ->
                    when (panel) {
                        PANEL_SPOOF -> if (isZh) "区域与机型" else "Region & Model"
                        PANEL_STEPS -> if (isZh) "微信计步计算" else "Step Calculator"
                        else -> if (isZh) "供电与维护" else "Power & Tools"
                    }
                },
            )

            // 根据当前选中的分段展示对应单张参数卡（一屏到底，清爽不堆叠）
            when (activePanel) {
                PANEL_SPOOF -> {
                    OneUiIslandCard(
                        title = if (isZh) "区域与机型参数" else "Region & Model Parameters",
                        subtitle = if (csc.enabled && csc.salesCode.isNotBlank()) {
                            if (isZh) "当前伪装: ${csc.salesCode} · ${csc.countryCode} (MCC ${csc.simMcc})"
                            else "Spoofed: ${csc.salesCode} · ${csc.countryCode} (MCC ${csc.simMcc})"
                        } else {
                            if (isZh) "未开启伪装 · 商店遵循物理 SIM 卡与网络自然识别"
                            else "Spoofing disabled · Follows physical SIM & network"
                        },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            QUICK_REGION_PRESETS.chunked(3).forEach { rowPresets ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    rowPresets.forEach { preset ->
                                        val isCurrent = csc.enabled && csc.salesCode.equals(preset.salesCode, ignoreCase = true)
                                        val bgColor = if (isCurrent) {
                                            IosGlassTokens.activeTileBg()
                                        } else {
                                            MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                        }
                                        val fgColor = if (isCurrent) {
                                            IosGlassTokens.activeTileFg()
                                        } else {
                                            MiuixTheme.colorScheme.onSurface
                                        }
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(bgColor)
                                                .clickable {
                                                    haptics.tap()
                                                    showPresetConfirmDialog = preset
                                                }
                                                .padding(horizontal = 8.dp, vertical = 7.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(1.dp),
                                        ) {
                                            Text(
                                                text = if (isZh) preset.labelZh else preset.labelEn,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = fgColor,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = "${preset.salesCode} · ${preset.simMcc}",
                                                fontSize = 10.sp,
                                                color = fgColor.copy(alpha = 0.78f),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        OneUiItemRow(
                            title = stringResource(R.string.search_all_csc_btn),
                            subtitle = stringResource(R.string.search_all_csc_desc),
                            icon = Icons.Default.Search,
                            iconTint = IosGlassTokens.IosPurple,
                            trailingValue = csc.salesCode.ifBlank { if (isZh) "未配置" else "Not set" },
                            showChevron = true,
                            onClick = onOpenSearch,
                        )

                        if (csc.enabled) {
                            OneUiItemRow(
                                title = stringResource(R.string.action_disable_spoofing),
                                subtitle = stringResource(R.string.disable_spoofing_desc),
                                icon = Icons.Default.Close,
                                iconTint = IosGlassTokens.IosRed,
                                showChevron = false,
                                onClick = {
                                    haptics.confirm()
                                    StoreCleaner.restoreNativeTelephonyProps()
                                    updateCsc(context.getString(R.string.disable_spoofing_success)) {
                                        it.copy(
                                            enabled = false,
                                            salesCode = "",
                                            countryCode = "",
                                            simMcc = "",
                                            simMnc = "",
                                            simNumeric = "",
                                            simOperatorName = "",
                                            simCountryIso = "",
                                            marketUnlockRaw = "",
                                            spoofSimInfo = false,
                                            spoofDevice = false,
                                        )
                                    }
                                },
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        OneUiItemRow(
                            title = if (isZh) "选择伪装机型 (${com.weitiao.tuning.core.DeviceModels.ALL.size}款)" else "Target Device Model (${com.weitiao.tuning.core.DeviceModels.ALL.size})",
                            subtitle = if (isZh) "修改 Build.MODEL / DEVICE / PRODUCT" else "Spoof Build.MODEL / DEVICE / PRODUCT",
                            icon = Icons.Default.Smartphone,
                            iconTint = IosGlassTokens.IosBlue,
                            trailingValue = csc.deviceName.ifBlank { Build.MODEL },
                            showChevron = true,
                            onClick = { showDeviceModelPicker = true },
                        )

                        OneUiItemRow(
                            title = if (isZh) "机型伪装生效应用" else "Model Spoof Target Apps",
                            subtitle = if (csc.deviceTargetFilterEnabled && csc.deviceTargetPackages.isNotEmpty()) {
                                stringResource(R.string.apps_count_applied, csc.deviceTargetPackages.size)
                            } else {
                                stringResource(R.string.all_scopes_applied)
                            },
                            icon = Icons.Default.Apps,
                            iconTint = IosGlassTokens.IosTeal,
                            trailingValue = if (csc.deviceTargetFilterEnabled && csc.deviceTargetPackages.isNotEmpty()) {
                                if (isZh) "${csc.deviceTargetPackages.size} 个应用" else "${csc.deviceTargetPackages.size} Apps"
                            } else {
                                if (isZh) "全部应用" else "All Apps"
                            },
                            showChevron = true,
                            onClick = { showDeviceTargetPicker = true },
                        )
                    }
                }

                PANEL_STEPS -> {
                    val currentMult = tuning.stepMultiplier.coerceIn(1, 1000)
                    val currentCap = tuning.stepMaximum.coerceIn(1, 1_000_000)
                    val calc1k = (1000 * currentMult).coerceAtMost(currentCap)
                    val calc3k = (3000 * currentMult).coerceAtMost(currentCap)
                    val calc5k = (5000 * currentMult).coerceAtMost(currentCap)

                    OneUiIslandCard(
                        title = stringResource(R.string.step_simulator_title),
                        subtitle = if (tuning.stepEnabled) {
                            stringResource(R.string.step_auto_mode_desc)
                        } else {
                            stringResource(R.string.wechat_steps_off_hint)
                        },
                    ) {
                        // 模式切换：固定倍率 vs 自动递增
                        OneUiSegmentedControl(
                            items = listOf(false, true),
                            selectedItem = tuning.stepAuto,
                            onSelect = { auto ->
                                updateTuning { it.copy(stepAuto = auto) }
                            },
                            label = { auto ->
                                if (auto) {
                                    if (isZh) "自动递增模式" else "Auto Increment"
                                } else {
                                    if (isZh) "固定倍率模式" else "Fixed Multiplier"
                                }
                            },
                        )

                        // 4 档常用倍率快捷选择（点击即切换倍率并实时刷新下方步数换算）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            STEP_TIER_PRESETS.forEach { tier ->
                                val isSelected = currentMult == tier.multiplier
                                val bgColor = if (isSelected) {
                                    IosGlassTokens.activeTileBg()
                                } else {
                                    MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                }
                                val fgColor = if (isSelected) {
                                    IosGlassTokens.activeTileFg()
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(bgColor)
                                        .clickable {
                                            haptics.tap()
                                            multiplier = tier.multiplier.toString()
                                            maximum = tier.recommendedCap.toString()
                                            updateTuning {
                                                it.copy(
                                                    stepEnabled = true,
                                                    stepMultiplier = tier.multiplier,
                                                    stepMaximum = tier.recommendedCap,
                                                )
                                            }
                                        }
                                        .padding(vertical = 7.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    Text(
                                        text = if (isZh) tier.titleZh else tier.titleEn,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = fgColor,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = if (tuning.stepAuto) {
                                            "+${tier.multiplier}/${if (isZh) "次" else "ev"}"
                                        } else {
                                            "1k→${(tier.multiplier).coerceAtMost(tier.recommendedCap / 1000)}k"
                                        },
                                        fontSize = 9.5.sp,
                                        color = fgColor.copy(alpha = 0.80f),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }

                        // 实时步数换算预览卡（展示不同档位/实际步数对应的微信步数结果）
                        val primaryCalcText = if (tuning.stepAuto) {
                            stringResource(R.string.step_simulator_auto_calc, currentMult, currentCap)
                        } else {
                            stringResource(R.string.step_simulator_calc, calc1k)
                        }
                        val multiTierCalcText = if (tuning.stepAuto) {
                            val eventsFor10k = (10000 / currentMult).coerceAtLeast(1)
                            val eventsForCap = (currentCap / currentMult).coerceAtLeast(1)
                            if (isZh) {
                                "$primaryCalcText\n· 累计触发 ${numFmt.format(eventsFor10k)} 次 ≈ 10,000 步 ｜ 触发 ${numFmt.format(eventsForCap)} 次达封顶 ${numFmt.format(currentCap)} 步"
                            } else {
                                "$primaryCalcText\n· ${numFmt.format(eventsFor10k)} events ≈ 10k steps | ${numFmt.format(eventsForCap)} events hits cap ${numFmt.format(currentCap)}"
                            }
                        } else {
                            if (isZh) {
                                "$primaryCalcText\n· 实走 3,000 步 ≈ ${numFmt.format(calc3k)} 步 ｜ 实走 5,000 步 ≈ ${numFmt.format(calc5k)} 步（上限 ${numFmt.format(currentCap)}）"
                            } else {
                                "$primaryCalcText\n· Walk 3k ≈ ${numFmt.format(calc3k)} | Walk 5k ≈ ${numFmt.format(calc5k)} (Cap ${numFmt.format(currentCap)})"
                            }
                        }
                        OneUiNoticeBanner(
                            text = multiTierCalcText,
                            contentColor = IosGlassTokens.IosBlue,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextField(
                                value = multiplier,
                                onValueChange = { raw ->
                                    val digits = raw.filter(Char::isDigit).take(4)
                                    multiplier = digits
                                    digits.toIntOrNull()?.let { v ->
                                        updateTuning { it.copy(stepMultiplier = v.coerceIn(1, 1000)) }
                                    }
                                },
                                label = stringResource(R.string.step_multiplier),
                                singleLine = true,
                                insideMargin = DpSize(10.dp, 10.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )

                            TextField(
                                value = endHour,
                                onValueChange = { raw ->
                                    val digits = raw.filter(Char::isDigit).take(2)
                                    endHour = digits
                                    digits.toIntOrNull()?.let { v ->
                                        updateTuning { it.copy(stepEndHour = v.coerceIn(0, 24)) }
                                    }
                                },
                                label = if (isZh) "截止时间(0-24点)" else "Stop Hour(0-24)",
                                singleLine = true,
                                insideMargin = DpSize(10.dp, 10.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }

                        TextField(
                            value = maximum,
                            onValueChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(7)
                                maximum = digits
                                digits.toIntOrNull()?.let { v ->
                                    updateTuning { it.copy(stepMaximum = v.coerceIn(1, 1_000_000)) }
                                }
                            },
                            label = stringResource(R.string.step_maximum),
                            singleLine = true,
                            insideMargin = DpSize(10.dp, 10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OneUiNoticeBanner(
                            text = stringResource(R.string.step_cap_desc),
                            contentColor = IosGlassTokens.IosGreen,
                        )
                    }
                }

                PANEL_POWER -> {
                    OneUiIslandCard(
                        title = if (isZh) "供电参数与快捷维护" else "Power & Quick Maintenance",
                        subtitle = if (isZh) "亮屏快充超过 44.0°C 自动恢复原厂温控 · 旁路供电绕过电池直供主板"
                        else "Fast charge 44.0°C safety cutoff · Bypass power feeds board directly",
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.pass_through_threshold_desc, passThroughSlider.toInt()),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${passThroughSlider.toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = IosGlassTokens.IosBlue,
                            )
                        }

                        Slider(
                            value = passThroughSlider,
                            onValueChange = { passThroughSlider = it },
                            onValueChangeFinished = {
                                val limit = passThroughSlider.toInt().coerceIn(0, 100)
                                updateTuning { it.copy(passThroughLimit = limit) }
                            },
                            valueRange = 0f..100f,
                            steps = 19,
                            showKeyPoints = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                        )

                        OneUiItemRow(
                            title = stringResource(R.string.choose_pass_through_apps),
                            subtitle = if (selectedPassApps.isEmpty()) stringResource(R.string.choose_apps_none)
                            else stringResource(R.string.choose_apps_count, selectedPassApps.size),
                            icon = Icons.Default.BatteryChargingFull,
                            iconTint = IosGlassTokens.IosGreen,
                            trailingValue = if (selectedPassApps.isEmpty()) {
                                if (isZh) "未选择" else "None"
                            } else {
                                if (isZh) "${selectedPassApps.size} 个应用" else "${selectedPassApps.size} Apps"
                            },
                            showChevron = true,
                            onClick = { showPassAppPicker = true },
                        )

                        if (tuning.brightScreenFastCharge) {
                            OneUiNoticeBanner(
                                text = stringResource(R.string.fast_charge_warning),
                                containerColor = OneUiTokens.WarningAmberContainerLight,
                                contentColor = IosGlassTokens.IosAmber,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MiuixMetricCard(
                            valueText = if (isZh) "重置商店" else "Reset Store",
                            labelText = if (isZh) "清理缓存重判区" else "Clear store cache",
                            icon = Icons.Default.CleaningServices,
                            accentColor = IosGlassTokens.IosPurple,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptics.confirm()
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { StoreCleaner.clearStoreDataRoot() }
                                    if (ok) {
                                        RegionApplyState.markApplied(context)
                                        regionCleanupPending = false
                                        snackbar.showSnackbar(context.getString(R.string.clear_store_success))
                                    } else {
                                        snackbar.showSnackbar(context.getString(R.string.clear_store_fail))
                                    }
                                }
                            },
                        )

                        MiuixMetricCard(
                            valueText = if (isZh) "重启微信" else "Restart WeChat",
                            labelText = if (isZh) "强停并重新拉起" else "Force stop & launch",
                            icon = Icons.Default.Refresh,
                            accentColor = IosGlassTokens.IosTeal,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptics.confirm()
                                scope.launch {
                                    val code = withContext(Dispatchers.IO) {
                                        RootShell.exec(
                                            "am force-stop com.tencent.mm && monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1"
                                        ).first
                                    }
                                    val msg = if (code == 0) {
                                        context.getString(R.string.restart_wechat_done)
                                    } else {
                                        context.getString(R.string.restart_wechat_failed)
                                    }
                                    snackbar.showSnackbar(msg)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 地区预设切换确认弹窗
    val preset = showPresetConfirmDialog ?: lastConfirmPreset
    val presetName = stringResource(preset.nameRes)
    WindowDialog(
        show = showPresetConfirmDialog != null,
        title = stringResource(R.string.preset_confirm_title, presetName),
        onDismissRequest = { showPresetConfirmDialog = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OneUiNoticeBanner(
                text = stringResource(
                    R.string.preset_confirm_auto,
                    preset.salesCode,
                    preset.simMcc,
                    preset.countryCode,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showPresetConfirmDialog = null },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.action_apply_and_clear),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        haptics.confirm()
                        updateCsc(context.getString(R.string.preset_applied_toast, presetName)) {
                            it.copy(
                                enabled = true,
                                salesCode = preset.salesCode,
                                countryCode = preset.countryCode,
                                simMcc = preset.simMcc,
                                simMnc = preset.mnc,
                                simNumeric = preset.numeric,
                                simOperatorName = preset.operator,
                                simCountryIso = preset.countryCode.lowercase(),
                                marketUnlockRaw = "${preset.numeric},${preset.operator},${preset.countryCode.lowercase()},${presetName}",
                                spoofSimInfo = true,
                                spoofSalesCode = true,
                                spoofCountryCode = true,
                                spoofRilSalesCode = true,
                                spoofOmcCodes = true,
                                spoofSemCscFeature = true,
                            )
                        }
                        scope.launch {
                            val cleaned = withContext(Dispatchers.IO) { StoreCleaner.clearStoreDataRoot() }
                            if (cleaned) {
                                RegionApplyState.markApplied(context)
                                regionCleanupPending = false
                                snackbar.showSnackbar(context.getString(R.string.clear_store_success))
                            } else {
                                snackbar.showSnackbar(context.getString(R.string.clear_store_fail))
                            }
                        }
                        showPresetConfirmDialog = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // 126 款机型选择半屏抽屉
    DeviceModelPicker(
        show = showDeviceModelPicker,
        initialSelection = csc.deviceName,
        onDismiss = { showDeviceModelPicker = false },
        onSelect = { selected ->
            updateCsc(selected) { it.copy(spoofDevice = true, deviceName = selected) }
            showDeviceModelPicker = false
        },
    )

    // 机型伪装生效应用选择半屏抽屉
    DeviceTargetPicker(
        show = showDeviceTargetPicker,
        initialSelection = csc.deviceTargetPackages,
        onDismiss = { showDeviceTargetPicker = false },
        onSave = { updated ->
            updateCsc {
                it.copy(
                    deviceTargetFilterEnabled = updated.isNotEmpty(),
                    deviceTargetPackages = updated,
                )
            }
            showDeviceTargetPicker = false

            scope.launch(Dispatchers.IO) {
                val synced = if (updated.isNotEmpty()) {
                    LsposedScopeHelper.syncScopesWithRoot(context, updated)
                } else false
                val launched = LsposedScopeHelper.launchLsposedManager(context)
                val msg = when {
                    synced && launched -> if (isZh) "已通过 Root 自动同步作用域，已调出 LSPosed 供确认" else "Synced scope via Root, opened LSPosed for confirmation"
                    launched -> if (isZh) "已保存目标应用，已调出 LSPosed 请确认勾选作用域" else "Saved target apps, opened LSPosed to verify scope"
                    synced -> if (isZh) "已通过 Root 自动同步作用域至 LSPosed" else "Synced scope to LSPosed via Root"
                    else -> if (isZh) "已保存目标应用，请在 LSPosed 中勾选对应作用域" else "Saved target apps (Please check scope in LSPosed)"
                }
                snackbar.showSnackbar(msg)
            }
        },
    )

    // 旁路供电生效游戏选择半屏抽屉
    PassThroughAppPickerSheet(
        show = showPassAppPicker,
        initial = selectedPassApps,
        onDismiss = { showPassAppPicker = false },
        onSave = { updated ->
            selectedPassApps = updated
            TuningPrefs.saveSelectedApps(context, updated)
            TuningPrefs.save(context, tuning)
            onConfigChanged()
            scope.launch { snackbar.showSnackbar(context.getString(R.string.saved_toast)) }
            showPassAppPicker = false
        },
    )
}

@Composable
private fun PassThroughAppPickerSheet(
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
                LaunchableAppEntry(
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

    var selected by remember(initial) { mutableStateOf(initial.toSet()) }

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
                        modifier = Modifier.padding(start = 12.dp).size(16.dp),
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
