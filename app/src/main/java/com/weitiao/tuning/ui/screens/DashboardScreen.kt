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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.weitiao.tuning.App
import com.weitiao.tuning.BuildConfig
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.LsposedScopeHelper
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.StoreCleaner
import com.weitiao.tuning.core.TuningConfig
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.ui.components.ActivationGuideDialog
import com.weitiao.tuning.ui.components.ConfirmRestartAppsDialog
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixHeroStatusCard
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.MiuixSectionTitle
import com.weitiao.tuning.ui.components.OneUiHomeFeatureCard
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/**
 * 首页：仅承担「查看环境状态」与「使用 4 项最常用功能」
 * - 单个紧凑的环境状态卡 + 按需下一步操作引导（不重复堆叠相同状态卡）
 * - 4 张纵向常用功能卡片（商店跨区、机型伪装、微信增强、充电优化）
 */
@Composable
fun DashboardScreen(
    refreshTrigger: Int = 0,
    bottomBar: (@Composable () -> Unit)? = null,
    onOpenFeature: (FeaturePageId) -> Unit,
    onOpenSettings: () -> Unit,
    onConfigChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isZh = remember { Locale.getDefault().language.startsWith("zh") }

    var csc by remember { mutableStateOf(CscPrefs.load(context)) }
    var tuning by remember { mutableStateOf(TuningPrefs.load(context)) }
    var selectedPassApps by remember { mutableStateOf(TuningPrefs.selectedApps(context)) }
    var regionCleanupPending by remember { mutableStateOf(RegionApplyState.isPending(context)) }

    var xposedReady by remember {
        mutableStateOf(App.getXposedService() != null || App.getRemotePrefs() != null)
    }
    var hasRoot by remember { mutableStateOf(false) }
    var batteryRestricted by remember {
        mutableStateOf(TuningPrefs.isBatteryOptimizationRestricted(context))
    }
    var batteryTempTenths by remember { mutableIntStateOf(0) }

    var showActivationGuide by rememberSaveable { mutableStateOf(false) }
    var showClearStoreConfirm by remember { mutableStateOf(false) }

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

    fun recheckEnvironment(showToast: Boolean = false) {
        scope.launch {
            val rootOk = withContext(Dispatchers.IO) { RootShell.hasRoot(forceRefresh = true) }
            val xpOk = App.getXposedService() != null || App.getRemotePrefs() != null
            hasRoot = rootOk
            xposedReady = xpOk
            batteryRestricted = TuningPrefs.isBatteryOptimizationRestricted(context)
            if (showToast) {
                val msg = if (isZh) {
                    "环境已刷新：LSPosed ${if (xpOk) "已激活" else "未激活"} · Root ${if (rootOk) "已授权" else "未授权"}"
                } else {
                    "Status updated: LSPosed ${if (xpOk) "Active" else "Inactive"} · Root ${if (rootOk) "Granted" else "Unavailable"}"
                }
                snackbar.showSnackbar(msg)
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        csc = CscPrefs.load(context)
        tuning = TuningPrefs.load(context)
        selectedPassApps = TuningPrefs.selectedApps(context)
        regionCleanupPending = RegionApplyState.isPending(context)
        xposedReady = App.getXposedService() != null || App.getRemotePrefs() != null
        batteryRestricted = TuningPrefs.isBatteryOptimizationRestricted(context)
        hasRoot = withContext(Dispatchers.IO) { RootShell.hasRoot() }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2500L)
            xposedReady = App.getXposedService() != null || App.getRemotePrefs() != null
            batteryRestricted = TuningPrefs.isBatteryOptimizationRestricted(context)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent != null) {
                    batteryTempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                }
            }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            batteryTempTenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
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

    fun updateTuning(toastMessage: String? = null, block: (TuningConfig) -> TuningConfig) {
        val next = block(tuning)
        tuning = next
        TuningPrefs.save(context, next)
        onConfigChanged()
        toastMessage?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    }

    val storeActive = csc.enabled && csc.salesCode.isNotBlank()
    val deviceActive = csc.spoofDevice && (csc.deviceName.isNotBlank() || csc.perAppDeviceModels.isNotEmpty())
    val wechatActive = tuning.stepEnabled || tuning.antiRecall
    val powerActive = tuning.brightScreenFastCharge || tuning.passThrough

    val activeFeatureCount = listOf(storeActive, deviceActive, wechatActive, powerActive).count { it }

    val heroTitle = when {
        xposedReady && hasRoot -> if (isZh) "运行环境已就绪" else "Environment Ready"
        xposedReady -> if (isZh) "模块已激活（无 Root）" else "Module Active (No Root)"
        hasRoot -> if (isZh) "模块尚未在 LSPosed 激活" else "LSPosed Module Inactive"
        else -> if (isZh) "需要完成初始环境激活" else "Setup Required"
    }

    val heroLine1 = if (isZh) {
        "LSPosed 模块：${if (xposedReady) "已激活" else "未激活"}  ·  Root 权限：${if (hasRoot) "已授权" else "未授权"}"
    } else {
        "LSPosed: ${if (xposedReady) "Active" else "Inactive"}  ·  Root: ${if (hasRoot) "Granted" else "Unavailable"}"
    }

    val heroLine2 = if (isZh) {
        if (activeFeatureCount > 0) "已启用 $activeFeatureCount 项常用功能 · 点击本卡片可查看环境指引"
        else "当前暂未启用功能 · 点击下方卡片即可一键开启或配置"
    } else {
        if (activeFeatureCount > 0) "$activeFeatureCount core feature(s) active · Tap for setup guide"
        else "No features active yet · Tap any card below to configure"
    }

    MiuixPageScaffold(
        title = stringResource(R.string.app_name),
        subtitle = if (isZh) "三星 One UI 跨区、机型伪装与系统增强工具" else "Samsung One UI Region, Model & System Enhancer",
        bottomBar = bottomBar,
        snackbarHostState = snackbar,
        bottomNavigationPadding = 24.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. 简洁的环境状态区域（单一卡片，不重复堆叠）
            MiuixHeroStatusCard(
                statusTitle = heroTitle,
                pillText = "v${BuildConfig.VERSION_NAME}",
                line1Text = heroLine1,
                line2Text = heroLine2,
                isWarning = !xposedReady || !hasRoot,
                onClick = { showActivationGuide = true },
            )

            // 2. 未满足条件或待生效操作的明确引导（仅在需要用户处理时展示）
            if (!xposedReady) {
                OneUiNoticeBanner(
                    text = if (isZh) "模块尚未激活：商店跨区、机型伪装和微信增强需在 LSPosed 中启用本模块"
                    else "Module not active: enable WeiTiao in LSPosed to use region, model & WeChat features",
                    icon = Icons.Default.Warning,
                    contentColor = IosGlassTokens.IosAmber,
                    actionText = if (isZh) "查看激活步骤" else "Activation Guide",
                    onActionClick = { showActivationGuide = true },
                )
            }

            if (powerActive && !hasRoot) {
                OneUiNoticeBanner(
                    text = if (isZh) "充电优化需要 Root 权限才能控制充电节点，请授权后重新检测"
                    else "Charging optimization requires Root access to control power nodes",
                    icon = Icons.Default.Warning,
                    contentColor = IosGlassTokens.IosRed,
                    actionText = if (isZh) "去授权" else "Grant Root",
                    onActionClick = { recheckEnvironment(showToast = true) },
                )
            }

            if (storeActive && regionCleanupPending && hasRoot) {
                OneUiNoticeBanner(
                    text = if (isZh) "商店地区已改为 ${formatRegionDisplayName(csc, true)}，刷新商店缓存后立即生效"
                    else "Store region changed to ${formatRegionDisplayName(csc, false)}. Refresh store to apply.",
                    icon = Icons.Default.Refresh,
                    contentColor = IosGlassTokens.IosBlue,
                    actionText = if (isZh) "立即刷新商店" else "Refresh Store",
                    onActionClick = { showClearStoreConfirm = true },
                )
            }

            if (powerActive && batteryRestricted) {
                OneUiNoticeBanner(
                    text = if (isZh) "已开启充电优化，建议将电池权限设为「不受限制」以防后台温控服务被系统休眠"
                    else "Set battery usage to Unrestricted to keep charging protection active in background",
                    icon = Icons.Default.BatteryAlert,
                    contentColor = IosGlassTokens.IosAmber,
                    actionText = if (isZh) "去设置" else "Configure",
                    onActionClick = {
                        TuningPrefs.requestIgnoreBatteryOptimization(context)
                        batteryRestricted = TuningPrefs.isBatteryOptimizationRestricted(context)
                    },
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
            MiuixSectionTitle(
                title = if (isZh) "常用功能" else "Common Features",
                trailingText = if (isZh) "点击卡片进入设置" else "Tap card to configure",
            )

            // 3. 四张纵向常用功能卡片
            // (1) 商店跨区
            val storeStatusText = when {
                !storeActive -> if (isZh) "未开启 · 使用手机默认地区" else "Off · System default region"
                regionCleanupPending -> if (isZh) "当前：${formatRegionDisplayName(csc, true)}（待刷新商店）"
                else "Active: ${formatRegionDisplayName(csc, false)} (Refresh pending)"
                else -> if (isZh) "当前：${formatRegionDisplayName(csc, true)}"
                else "Active: ${formatRegionDisplayName(csc, false)}"
            }
            OneUiHomeFeatureCard(
                title = if (isZh) "商店跨区" else "Store Region Unlock",
                description = if (isZh) "切换三星应用商店与主题商店地区，下载港版/韩版/美版应用"
                else "Switch Galaxy Store & Theme Store region without affecting SIM signal",
                statusText = storeStatusText,
                icon = Icons.Default.Public,
                accentColor = IosGlassTokens.IosBlue,
                isActive = storeActive,
                isWarning = storeActive && (!xposedReady || regionCleanupPending),
                checked = storeActive,
                onCheckedChange = { targetEnabled ->
                    if (!targetEnabled) {
                        updateCsc(if (isZh) "已关闭商店跨区" else "Store region unlock disabled") {
                            it.copy(enabled = false)
                        }
                    } else {
                        val defaultPreset = REGION_QUICK_PRESETS.first()
                        val defaultPresetName = context.getString(defaultPreset.nameRes)
                        updateCsc(
                            if (csc.salesCode.isBlank()) {
                                if (isZh) "已开启商店跨区（默认设为 ${defaultPreset.labelZh} TGY，点击卡片可切换地区）"
                                else "Enabled Store Region Unlock (Default: Hong Kong TGY)"
                            } else {
                                if (isZh) "已开启商店跨区：${formatRegionDisplayName(csc.copy(enabled = true), true)}"
                                else "Store region unlock enabled"
                            },
                        ) { cur ->
                            if (cur.salesCode.isBlank()) {
                                cur.copy(
                                    enabled = true,
                                    salesCode = defaultPreset.salesCode,
                                    countryCode = defaultPreset.countryCode,
                                    simMcc = defaultPreset.simMcc,
                                    simMnc = defaultPreset.mnc,
                                    simNumeric = defaultPreset.numeric,
                                    simOperatorName = defaultPreset.operator,
                                    simCountryIso = defaultPreset.countryCode.lowercase(),
                                    marketUnlockRaw = "${defaultPreset.numeric},${defaultPreset.operator},${defaultPreset.countryCode.lowercase()},${defaultPresetName}",
                                    spoofSimInfo = true,
                                )
                            } else {
                                cur.copy(enabled = true)
                            }
                        }
                    }
                },
                onClick = { onOpenFeature(FeaturePageId.STORE_REGION) },
            )

            // (2) 机型伪装
            val perAppCount = csc.perAppDeviceModels.size
            val deviceStatusText = when {
                !deviceActive -> if (isZh) "未开启 · 本机型号 ${Build.MODEL}" else "Off · Real model ${Build.MODEL}"
                csc.deviceName.isNotBlank() && perAppCount > 0 -> {
                    if (isZh) "默认：${csc.deviceName} · 另有 $perAppCount 个分应用机型"
                    else "Default: ${csc.deviceName} · +$perAppCount per-app"
                }
                csc.deviceName.isNotBlank() -> {
                    if (isZh) "当前伪装：${csc.deviceName}" else "Spoofing as: ${csc.deviceName}"
                }
                else -> {
                    if (isZh) "已为 $perAppCount 个应用单独设置机型" else "$perAppCount app(s) configured"
                }
            }
            OneUiHomeFeatureCard(
                title = if (isZh) "机型伪装" else "Device Model Spoofing",
                description = if (isZh) "修改社交软件尾巴型号或解锁游戏高帧率，支持全局与分应用机型"
                else "Spoof device model globally or per-app for social tails & high FPS gaming",
                statusText = deviceStatusText,
                icon = Icons.Default.Smartphone,
                accentColor = IosGlassTokens.IosPurple,
                isActive = deviceActive,
                isWarning = deviceActive && !xposedReady,
                checked = deviceActive,
                onCheckedChange = { targetEnabled ->
                    if (!targetEnabled) {
                        updateCsc(if (isZh) "已关闭机型伪装" else "Device spoofing disabled") {
                            it.copy(spoofDevice = false)
                        }
                    } else {
                        val fallbackModel = RECOMMENDED_DEVICE_MODELS.first()
                        val useFallback = csc.deviceName.isBlank() && csc.perAppDeviceModels.isEmpty()
                        updateCsc(
                            if (useFallback) {
                                if (isZh) "已开启机型伪装（默认设为 $fallbackModel，点击卡片可更换机型）"
                                else "Enabled Device Spoofing ($fallbackModel)"
                            } else {
                                if (isZh) "已开启机型伪装" else "Device spoofing enabled"
                            },
                        ) { cur ->
                            cur.copy(
                                spoofDevice = true,
                                deviceName = if (useFallback) fallbackModel else cur.deviceName,
                            )
                        }
                    }
                },
                onClick = { onOpenFeature(FeaturePageId.DEVICE_SPOOF) },
            )

            // (3) 微信增强
            val wechatStatusText = when {
                tuning.stepEnabled && tuning.antiRecall -> {
                    if (isZh) "已开启：步数 ${tuning.stepMultiplier}倍 + 消息防撤回"
                    else "On: ${tuning.stepMultiplier}x Steps + Anti-Recall"
                }
                tuning.stepEnabled -> {
                    if (isZh) "已开启：步数修改（${tuning.stepMultiplier}倍 · 上限 ${tuning.stepMaximum}）"
                    else "On: Step Modifier (${tuning.stepMultiplier}x)"
                }
                tuning.antiRecall -> {
                    if (isZh) "已开启：消息防撤回（步数修改未开启）"
                    else "On: Message Anti-Recall"
                }
                else -> if (isZh) "未开启 · 支持微信运动步数修改与防撤回" else "Off · Step counter & message anti-recall"
            }
            OneUiHomeFeatureCard(
                title = if (isZh) "微信增强" else "WeChat Enhancement",
                description = if (isZh) "自动递增或放大今日微信运动步数，并阻止对方撤回聊天消息"
                else "Modify daily WeChat steps and prevent others from recalling messages",
                statusText = wechatStatusText,
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                accentColor = IosGlassTokens.IosGreen,
                isActive = wechatActive,
                isWarning = wechatActive && !xposedReady,
                onClick = {
                    if (!tuning.stepEnabled && tuning.antiRecall) {
                        onOpenFeature(FeaturePageId.WECHAT_ANTI_RECALL)
                    } else {
                        onOpenFeature(FeaturePageId.WECHAT_STEPS)
                    }
                },
            )

            // (4) 充电优化
            val tempText = if (batteryTempTenths > 0) String.format(Locale.US, "%.1f°C", batteryTempTenths / 10f) else "--°C"
            val powerStatusText = when {
                tuning.brightScreenFastCharge && tuning.passThrough -> {
                    if (isZh) "亮屏快充 (${tuning.thermalCutoffCelsius}°C保护) + 旁路供电 (${selectedPassApps.size}应用) · 当前 $tempText"
                    else "Fast Charge (${tuning.thermalCutoffCelsius}°C) + Bypass (${selectedPassApps.size} apps) · $tempText"
                }
                tuning.brightScreenFastCharge -> {
                    if (isZh) "已开启亮屏快充（${tuning.thermalCutoffCelsius}°C 自动恢复温控 · 当前 $tempText）"
                    else "Screen-On Fast Charge (${tuning.thermalCutoffCelsius}°C cutoff · Now $tempText)"
                }
                tuning.passThrough -> {
                    if (isZh) "已开启应用旁路供电（已选 ${selectedPassApps.size} 个应用 · >${tuning.passThroughLimit}% 生效）"
                    else "Bypass Power On (${selectedPassApps.size} apps · >${tuning.passThroughLimit}%)"
                }
                else -> if (isZh) "未开启 · 亮屏快充解锁与游戏旁路直供（当前电池 $tempText）"
                else "Off · Screen-on fast charge & gaming bypass power (Battery $tempText)"
            }
            OneUiHomeFeatureCard(
                title = if (isZh) "充电优化" else "Charging Optimization",
                description = if (isZh) "解除亮屏边玩边充限流（含过热自动保护），支持游戏旁路直供减少发热"
                else "Unlock screen-on fast charge with thermal protection & gaming bypass power",
                statusText = powerStatusText,
                icon = Icons.Default.Bolt,
                accentColor = IosGlassTokens.IosAmber,
                isActive = powerActive,
                isWarning = powerActive && (!hasRoot || batteryRestricted),
                checked = tuning.brightScreenFastCharge,
                onCheckedChange = { targetEnabled ->
                    if (!targetEnabled) {
                        updateTuning(
                            if (isZh) "已关闭亮屏快充并恢复系统温控" else "Screen-on fast charge disabled",
                        ) { it.copy(brightScreenFastCharge = false) }
                    } else {
                        ensureNotificationPermission()
                        scope.launch {
                            val rootOk = withContext(Dispatchers.IO) { RootShell.hasRoot(forceRefresh = true) }
                            hasRoot = rootOk
                            if (rootOk) {
                                updateTuning(
                                    if (isZh) "已开启亮屏快充（${tuning.thermalCutoffCelsius}°C 自动过热保护）"
                                    else "Screen-on fast charge enabled",
                                ) { it.copy(brightScreenFastCharge = true) }
                            } else {
                                snackbar.showSnackbar(
                                    if (isZh) "开启失败：亮屏快充需要 Root 权限，请在 Magisk / KernelSU 中授权"
                                    else "Root access is required for Screen-On Fast Charge",
                                )
                            }
                        }
                    }
                },
                onClick = {
                    if (!tuning.brightScreenFastCharge && tuning.passThrough) {
                        onOpenFeature(FeaturePageId.BYPASS_POWER)
                    } else {
                        onOpenFeature(FeaturePageId.FAST_CHARGE)
                    }
                },
            )
        }
    }

    ConfirmRestartAppsDialog(
        show = showClearStoreConfirm,
        title = if (isZh) "刷新三星商店缓存？" else "Refresh Galaxy Store Cache?",
        message = if (isZh) {
            "将强制关闭三星应用商店与主题商店并清理其地区缓存，重新打开商店即可加载新地区内容。"
        } else {
            "Galaxy Store & Theme Store will be closed and their cache cleared so the new region loads immediately."
        },
        confirmText = if (isZh) "立即刷新" else "Refresh Now",
        cancelText = stringResource(R.string.cancel),
        onDismiss = { showClearStoreConfirm = false },
        onConfirm = {
            showClearStoreConfirm = false
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

    ActivationGuideDialog(
        show = showActivationGuide,
        isZh = isZh,
        xposedReady = xposedReady,
        rootReady = hasRoot,
        onOpenLsposed = {
            scope.launch(Dispatchers.IO) { LsposedScopeHelper.launchLsposedManager(context) }
        },
        onRetest = { recheckEnvironment(showToast = true) },
        onDismiss = { showActivationGuide = false },
    )
}
