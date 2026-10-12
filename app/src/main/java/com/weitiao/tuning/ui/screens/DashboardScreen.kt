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
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
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
import com.weitiao.tuning.core.NativeDevice
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
import com.weitiao.tuning.ui.components.MiuixSectionTitle
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.theme.OneUiTokens
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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

private data class CompactPreset(
    val flag: String,
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
    CompactPreset("🇭🇰", "香港", "HK", R.string.preset_hk_name, "TGY", "454", "HK", "45406", "SmarTone", "06"),
    CompactPreset("🇺🇸", "美国", "US", R.string.preset_us_name, "XAA", "310", "US", "310260", "T-Mobile", "260"),
    CompactPreset("🇪🇺", "欧洲", "EU", R.string.preset_eu_name, "EUX", "262", "DE", "26201", "T-Mobile", "01"),
    CompactPreset("🇹🇼", "台湾", "TW", R.string.preset_tw_name, "BRI", "466", "TW", "46688", "Telecom", "88"),
    CompactPreset("🇰🇷", "韩国", "KR", R.string.preset_kr_name, "KOO", "450", "KR", "45005", "SKT", "05"),
    CompactPreset("🇨🇳", "大陆", "CN", R.string.preset_cn_name, "CHC", "460", "CN", "46000", "CHINA MOBILE", "00"),
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

    val nativeInfo = remember { NativeDevice.detect(context) }
    var csc by remember { mutableStateOf(CscPrefs.load(context)) }
    var tuning by remember { mutableStateOf(TuningPrefs.load(context)) }

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

    var rawSalesCode by remember { mutableStateOf(nativeInfo.salesCode) }
    var rawSimOperator by remember { mutableStateOf(nativeInfo.simMcc) }

    // 实时电池温度与充电状态监听
    var batteryTemp by remember { mutableFloatStateOf(34.5f) }
    var batteryLevel by remember { mutableIntStateOf(80) }
    var isPlugged by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 345)
                batteryTemp = tempTenths / 10f
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 80)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                batteryLevel = (level * 100) / scale
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                isPlugged = plugged != 0
            }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            batteryTemp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 345) / 10f
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
        val info = NativeDevice.detect(context)
        rawSalesCode = info.salesCode
        rawSimOperator = info.simMcc
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

            // 第一层：顶部紧凑状态大卡（融合实时电池温度与电量，无需再单独堆叠重复看板卡）
            val modeLabel = if (moduleState == ModuleState.ACTIVE) {
                if (isZh) "LSPosed 推荐模式" else "LSPosed Mode"
            } else {
                if (isZh) "Root 硬件直控" else "Root Mode"
            }
            val powerTag = if (isPlugged) {
                if (isZh) "⚡ 充电中 $batteryLevel%" else "⚡ Charging $batteryLevel%"
            } else {
                if (isZh) "🔋 电量 $batteryLevel%" else "🔋 Battery $batteryLevel%"
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

            // 第二层：全应用唯一的 3×2 功能总开关磁贴（下方不再重复放置任何同功能开关）
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
                            if (csc.deviceName.isNotBlank()) {
                                updateCsc { it.copy(spoofDevice = !it.spoofDevice) }
                            } else {
                                showDeviceModelPicker = true
                            }
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "微信计步" else "Steps",
                        statusText = if (tuning.stepEnabled) enabledText else disabledText,
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        active = tuning.stepEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
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
                            val next = !tuning.brightScreenFastCharge
                            updateTuning { it.copy(brightScreenFastCharge = next) }
                            if (next) requestNotificationPermissionIfNeeded()
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "旁路供电" else "Bypass",
                        statusText = if (tuning.passThrough) enabledText else disabledText,
                        icon = Icons.Default.Power,
                        active = tuning.passThrough,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val next = !tuning.passThrough
                            updateTuning { it.copy(passThrough = next) }
                            if (next) requestNotificationPermissionIfNeeded()
                        },
                    )
                }
            }

            // 第三层：区域与机型参数配置（无重复开关，直接选地区与机型）
            OneUiIslandCard(
                title = if (isZh) "区域与机型参数" else "Region & Model Parameters",
                subtitle = if (csc.enabled && csc.salesCode.isNotBlank()) {
                    if (isZh) "当前伪装: ${csc.salesCode} · ${csc.countryCode} (MCC ${csc.simMcc}) | 出厂: $rawSalesCode"
                    else "Spoofed: ${csc.salesCode} · ${csc.countryCode} (MCC ${csc.simMcc}) | Factory: $rawSalesCode"
                } else {
                    if (isZh) "当前出厂原生区域: $rawSalesCode (MCC $rawSimOperator)"
                    else "Native factory region: $rawSalesCode (MCC $rawSimOperator)"
                },
            ) {
                // 6 大热门地区 3×2 紧凑一键预设
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
                                        text = "${preset.flag} ${if (isZh) preset.labelZh else preset.labelEn}",
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
                    trailingValue = csc.salesCode.ifBlank { rawSalesCode },
                    showChevron = true,
                    onClick = onOpenSearch,
                )

                OneUiItemRow(
                    title = stringResource(R.string.action_restore_factory),
                    subtitle = stringResource(R.string.factory_baseline_format, rawSalesCode, rawSimOperator),
                    icon = Icons.Default.Restore,
                    iconTint = IosGlassTokens.IosAmber,
                    showChevron = true,
                    onClick = {
                        haptics.confirm()
                        StoreCleaner.restoreNativeTelephonyProps()
                        updateCsc(context.getString(R.string.restore_factory_success, rawSalesCode)) {
                            it.copy(
                                enabled = false,
                                salesCode = nativeInfo.salesCode,
                                countryCode = nativeInfo.countryCode,
                                simMcc = nativeInfo.simMcc,
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                OneUiItemRow(
                    title = if (isZh) "选择伪装机型 (126款)" else "Target Device Model (126)",
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

            // 第四层：计步与供电参数配置（无重复开关，直接调整参数与阈值）
            OneUiIslandCard(
                title = if (isZh) "计步与供电参数" else "Steps & Power Parameters",
                subtitle = if (isZh) "亮屏快充超过 44.0°C 自动安全熔断恢复原厂温控" else "Auto 44.0°C thermal safety cutoff for fast charge",
            ) {
                OneUiItemRow(
                    title = stringResource(R.string.step_auto_mode),
                    subtitle = stringResource(R.string.step_auto_mode_desc),
                    icon = Icons.AutoMirrored.Filled.DirectionsRun,
                    iconTint = IosGlassTokens.IosBlue,
                    trailingValue = if (tuning.stepAuto) {
                        if (isZh) "自动递增" else "Auto"
                    } else {
                        if (isZh) "固定倍率" else "Fixed"
                    },
                    showChevron = true,
                    onClick = {
                        updateTuning { it.copy(stepAuto = !it.stepAuto) }
                    },
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
                        label = if (isZh) "倍率" else "Rate",
                        singleLine = true,
                        insideMargin = DpSize(10.dp, 10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.9f),
                    )

                    TextField(
                        value = maximum,
                        onValueChange = { raw ->
                            val digits = raw.filter(Char::isDigit).take(7)
                            maximum = digits
                            digits.toIntOrNull()?.let { v ->
                                updateTuning { it.copy(stepMaximum = v.coerceIn(1, 1_000_000)) }
                            }
                        },
                        label = if (isZh) "每日封顶步数" else "Daily Cap",
                        singleLine = true,
                        insideMargin = DpSize(10.dp, 10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1.3f),
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
                        label = if (isZh) "截止(h)" else "End(h)",
                        singleLine = true,
                        insideMargin = DpSize(10.dp, 10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.9f),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isZh) "旁路供电触发阈值" else "Bypass Power Threshold",
                        fontSize = 12.5.sp,
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
            }

            // 第五层：快捷维护双列动作卡
            MiuixSectionTitle(
                title = if (isZh) "快捷维护" else "Quick Actions",
                trailingText = if (isZh) "点击立即执行" else "Tap to run",
            )

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

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 地区预设切换确认弹窗
    val preset = showPresetConfirmDialog ?: lastConfirmPreset
    val presetName = stringResource(preset.nameRes)
    WindowDialog(
        show = showPresetConfirmDialog != null,
        title = "${preset.flag} ${stringResource(R.string.preset_confirm_title, presetName)}",
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
