package com.weitiao.tuning.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weitiao.tuning.App
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
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class FeaturePageId {
    STORE_REGION,
    DEVICE_SPOOF,
    PER_APP_MODEL,
    WECHAT_STEPS,
    WECHAT_ANTI_RECALL,
    FAST_CHARGE,
    BYPASS_POWER,
    ONEUI_HIDDEN,
    CUSTOM_CSC_FEATURE,
    CUSTOM_SYS_PROPS,
}

internal data class RegionQuickPreset(
    val labelZh: String,
    val labelEn: String,
    val tagZh: String,
    val tagEn: String,
    val nameRes: Int,
    val salesCode: String,
    val simMcc: String,
    val countryCode: String,
    val numeric: String,
    val operator: String,
    val mnc: String,
)

internal val REGION_QUICK_PRESETS = listOf(
    RegionQuickPreset("中国香港", "Hong Kong", "港版商店 · Good Lock", "HK Store", R.string.preset_hk_name, "TGY", "454", "HK", "45406", "SmarTone", "06"),
    RegionQuickPreset("美国公开版", "US Unlocked", "美区商店 · 完整生态", "US Store", R.string.preset_us_name, "XAA", "310", "US", "310260", "T-Mobile", "260"),
    RegionQuickPreset("欧洲通用版", "Europe Open", "欧版商店 · 纯净无捆绑", "EU Store", R.string.preset_eu_name, "EUX", "262", "DE", "26201", "T-Mobile", "01"),
    RegionQuickPreset("中国台湾", "Taiwan", "台版商店 · 繁体应用", "TW Store", R.string.preset_tw_name, "BRI", "466", "TW", "46688", "Telecom", "88"),
    RegionQuickPreset("韩国官方版", "South Korea", "韩版商店 · 首发更新", "KR Store", R.string.preset_kr_name, "KOO", "450", "KR", "45005", "SKT", "05"),
    RegionQuickPreset("中国大陆", "Mainland China", "国行商店 · 本地服务", "CN Store", R.string.preset_cn_name, "CHC", "460", "CN", "46000", "CHINA MOBILE", "00"),
)

internal val RECOMMENDED_DEVICE_MODELS = listOf(
    "iPhone 17 Pro Max",
    "iPhone Duo",
    "Galaxy S26 Ultra",
    "Galaxy Z Fold7",
    "Xiaomi 17 Ultra",
    "HUAWEI Mate XT Ultimate Design",
    "ROG Phone 9 Pro",
    "Xperia 1 VIII",
)

internal data class StepQuickTier(
    val multiplier: Int,
    val recommendedCap: Int,
    val titleZh: String,
    val titleEn: String,
)

internal val STEP_QUICK_TIERS = listOf(
    StepQuickTier(multiplier = 3, recommendedCap = 28000, titleZh = "3倍 轻度", titleEn = "3x Light"),
    StepQuickTier(multiplier = 10, recommendedCap = 50000, titleZh = "10倍 日常", titleEn = "10x Daily"),
    StepQuickTier(multiplier = 25, recommendedCap = 68000, titleZh = "25倍 进阶", titleEn = "25x Boost"),
    StepQuickTier(multiplier = 50, recommendedCap = 88888, titleZh = "50倍 快速", titleEn = "50x Fast"),
)

internal data class OneUiHiddenPreset(
    val key: String,
    val activeValue: String,
    val titleZh: String,
    val titleEn: String,
    val descZh: String,
    val descEn: String,
)

internal val ONEUI_HIDDEN_PRESETS = listOf(
    OneUiHiddenPreset(
        key = "CscFeature_VoiceCall_ConfigRecording",
        activeValue = "RecordingAllowed",
        titleZh = "原生通话自动录音",
        titleEn = "Native Call Recording",
        descZh = "在三星电话设置中开启通话自动录音与手动录音按钮",
        descEn = "Enable native call recording option in Samsung Phone app",
    ),
    OneUiHiddenPreset(
        key = "CscFeature_Camera_ShutterSoundMenu",
        activeValue = "TRUE",
        titleZh = "相机快门声音开关",
        titleEn = "Camera Shutter Sound Toggle",
        descZh = "在三星相机设置中显示「快门音」开关菜单",
        descEn = "Show shutter sound switch in Samsung Camera settings",
    ),
    OneUiHiddenPreset(
        key = "CscFeature_Setting_SupportRealTimeNetworkSpeed",
        activeValue = "TRUE",
        titleZh = "状态栏实时网速显示",
        titleEn = "Status Bar Real-Time Network Speed",
        descZh = "在顶部状态栏实时显示当前网络上传与下载速度",
        descEn = "Display real-time network speed in the status bar",
    ),
    OneUiHiddenPreset(
        key = "CscFeature_SystemUI_SupportDataUsageViewOnQuickPanel",
        activeValue = "TRUE",
        titleZh = "下拉通知栏流量统计",
        titleEn = "Quick Panel Data Usage View",
        descZh = "在下拉快捷面板底部显示本月移动数据流量使用情况",
        descEn = "Show monthly mobile data usage in the quick settings panel",
    ),
)

internal fun formatRegionDisplayName(csc: CscConfig, isZh: Boolean): String {
    if (!csc.enabled || csc.salesCode.isBlank()) {
        return if (isZh) "未开启（跟随系统默认）" else "Off (System Default)"
    }
    val matchedPreset = REGION_QUICK_PRESETS.find { it.salesCode.equals(csc.salesCode, ignoreCase = true) }
    return if (matchedPreset != null) {
        if (isZh) "${matchedPreset.labelZh} (${csc.salesCode})" else "${matchedPreset.labelEn} (${csc.salesCode})"
    } else if (csc.countryCode.isNotBlank()) {
        "${csc.salesCode} (${csc.countryCode})"
    } else {
        csc.salesCode
    }
}

/**
 * 底部导航栏「功能」分栏：按用户易懂的 4 大功能分类清晰组织列表入口
 */
@Composable
fun FeaturesCatalogScreen(
    refreshTrigger: Int = 0,
    bottomBar: @Composable () -> Unit,
    onOpenFeature: (FeaturePageId) -> Unit,
) {
    val context = LocalContext.current
    val isZh = remember { Locale.getDefault().language.startsWith("zh") }
    var csc by remember(refreshTrigger) { mutableStateOf(CscPrefs.load(context)) }
    var tuning by remember(refreshTrigger) { mutableStateOf(TuningPrefs.load(context)) }
    val selectedPassApps = remember(refreshTrigger) { TuningPrefs.selectedApps(context) }

    LaunchedEffect(refreshTrigger) {
        csc = CscPrefs.load(context)
        tuning = TuningPrefs.load(context)
    }

    val enabledHiddenCount = ONEUI_HIDDEN_PRESETS.count { preset ->
        csc.customCscFeatures[preset.key].equals(preset.activeValue, ignoreCase = true)
    }

    MiuixPageScaffold(
        title = if (isZh) "功能" else "Features",
        subtitle = if (isZh) "按功能用途分类，点击即可进入配置" else "Organized by purpose — tap any item to configure",
        bottomBar = bottomBar,
        bottomNavigationPadding = 24.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. 区域与机型
            OneUiIslandCard(
                title = if (isZh) "区域与机型" else "Region & Device Model",
            ) {
                OneUiItemRow(
                    title = if (isZh) "商店跨区" else "Store Region Unlock",
                    subtitle = if (isZh) "切换三星应用商店与主题商店地区" else "Switch Galaxy Store & Theme Store region",
                    icon = Icons.Default.Public,
                    iconTint = IosGlassTokens.IosBlue,
                    trailingValue = if (csc.enabled && csc.salesCode.isNotBlank()) {
                        formatRegionDisplayName(csc, isZh)
                    } else {
                        if (isZh) "未开启" else "Off"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.STORE_REGION) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                val deviceActive = csc.spoofDevice && (csc.deviceName.isNotBlank() || csc.perAppDeviceModels.isNotEmpty())
                OneUiItemRow(
                    title = if (isZh) "机型伪装" else "Device Model Spoofing",
                    subtitle = if (isZh) "修改全局默认机型与选择生效应用" else "Choose global target model and effective apps",
                    icon = Icons.Default.Smartphone,
                    iconTint = IosGlassTokens.IosPurple,
                    trailingValue = if (deviceActive) {
                        csc.deviceName.ifBlank {
                            if (isZh) "分应用生效" else "Per-App"
                        }
                    } else {
                        if (isZh) "未开启" else "Off"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.DEVICE_SPOOF) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                val customAppCount = csc.perAppDeviceModels.size
                OneUiItemRow(
                    title = if (isZh) "分应用机型" else "Per-App Device Models",
                    subtitle = if (isZh) "为不同应用单独设置专属伪装机型" else "Assign different models to individual apps",
                    icon = Icons.Default.Apps,
                    iconTint = IosGlassTokens.IosTeal,
                    trailingValue = if (customAppCount > 0) {
                        if (isZh) "已设 $customAppCount 个" else "$customAppCount apps"
                    } else {
                        if (isZh) "跟随默认" else "Default"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.PER_APP_MODEL) },
                )
            }

            // 2. 微信增强
            OneUiIslandCard(
                title = if (isZh) "微信增强" else "WeChat Enhancement",
            ) {
                OneUiItemRow(
                    title = if (isZh) "步数设置" else "Step Counter Settings",
                    subtitle = if (isZh) "自动递增或按固定倍率修改微信运动步数" else "Auto-increment or multiply WeChat steps",
                    icon = Icons.AutoMirrored.Filled.DirectionsRun,
                    iconTint = IosGlassTokens.IosGreen,
                    trailingValue = if (tuning.stepEnabled) {
                        if (isZh) "已开启 · ${tuning.stepMultiplier}倍" else "On · ${tuning.stepMultiplier}x"
                    } else {
                        if (isZh) "未开启" else "Off"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.WECHAT_STEPS) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                OneUiItemRow(
                    title = if (isZh) "消息防撤回" else "Message Anti-Recall",
                    subtitle = if (isZh) "阻止对方撤回消息，保留文字、图片、语音和视频" else "Keep recalled text, photos, voice & video messages",
                    icon = Icons.Default.Shield,
                    iconTint = IosGlassTokens.IosBlue,
                    trailingValue = if (tuning.antiRecall) {
                        if (isZh) "已开启" else "On"
                    } else {
                        if (isZh) "未开启" else "Off"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.WECHAT_ANTI_RECALL) },
                )
            }

            // 3. 充电与供电
            OneUiIslandCard(
                title = if (isZh) "充电与供电" else "Charging & Power",
            ) {
                OneUiItemRow(
                    title = if (isZh) "亮屏快充" else "Screen-On Fast Charge",
                    subtitle = if (isZh) "解除亮屏充电限流，达到安全温度自动恢复系统温控" else "Unlock screen-on fast charge with thermal safety cutoff",
                    icon = Icons.Default.Bolt,
                    iconTint = IosGlassTokens.IosAmber,
                    trailingValue = if (tuning.brightScreenFastCharge) {
                        if (isZh) "已开启 · ${tuning.thermalCutoffCelsius}°C" else "On · ${tuning.thermalCutoffCelsius}°C"
                    } else {
                        if (isZh) "未开启" else "Off"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.FAST_CHARGE) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                OneUiItemRow(
                    title = if (isZh) "应用旁路供电" else "App Bypass Power",
                    subtitle = if (isZh) "运行指定游戏时由充电器直接给主板供电，减少发热" else "Direct motherboard power in selected games to reduce heat",
                    icon = Icons.Default.Power,
                    iconTint = IosGlassTokens.IosGreen,
                    trailingValue = if (tuning.passThrough) {
                        if (isZh) "已开启 · ${selectedPassApps.size}个应用" else "On · ${selectedPassApps.size} apps"
                    } else {
                        if (isZh) "未开启" else "Off"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.BYPASS_POWER) },
                )
            }

            // 4. One UI 增强
            OneUiIslandCard(
                title = if (isZh) "One UI 增强" else "One UI Enhancements",
            ) {
                OneUiItemRow(
                    title = if (isZh) "常用隐藏功能" else "Common Hidden Features",
                    subtitle = if (isZh) "通话自动录音、相机快门音开关、状态栏网速与流量条" else "Call recording, shutter sound toggle, network speed & data usage",
                    icon = Icons.Default.Stars,
                    iconTint = IosGlassTokens.IosAmber,
                    trailingValue = if (enabledHiddenCount > 0) {
                        if (isZh) "已开 $enabledHiddenCount 项" else "$enabledHiddenCount enabled"
                    } else {
                        if (isZh) "未开启" else "None"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.ONEUI_HIDDEN) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                OneUiItemRow(
                    title = if (isZh) "自定义 CscFeature" else "Custom CscFeature",
                    subtitle = if (isZh) "添加或修改三星 One UI 特性键值" else "Add or edit Samsung CscFeature overrides",
                    icon = Icons.Default.Tune,
                    iconTint = IosGlassTokens.IosPurple,
                    trailingValue = if (csc.customCscFeatures.isNotEmpty()) {
                        if (isZh) "${csc.customCscFeatures.size} 项" else "${csc.customCscFeatures.size} items"
                    } else {
                        if (isZh) "默认" else "Default"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.CUSTOM_CSC_FEATURE) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                OneUiItemRow(
                    title = if (isZh) "自定义 SystemProperties" else "Custom SystemProperties",
                    subtitle = if (isZh) "添加或修改系统属性覆盖键值" else "Add or edit system property overrides",
                    icon = Icons.Default.Code,
                    iconTint = IosGlassTokens.IosTeal,
                    trailingValue = if (csc.customProps.isNotEmpty()) {
                        if (isZh) "${csc.customProps.size} 项" else "${csc.customProps.size} items"
                    } else {
                        if (isZh) "默认" else "Default"
                    },
                    showChevron = true,
                    onClick = { onOpenFeature(FeaturePageId.CUSTOM_SYS_PROPS) },
                )
            }
        }
    }
}

/**
 * 统一功能详情页容器：
 * 1. 顶部：功能名称 + 一句话功能说明
 * 2. 按需前置条件引导（仅在当前功能需要 LSPosed 或 Root 且未满足时展示）
 * 3. 主操作区：主开关 + 常用预设一键选择（主开关关闭时也可一键点预设自动启用，下级细项收起）
 * 4. 高级设置：默认折叠
 * 5. 操作区：明确的应用/重启目标应用操作（强停前给出确认提示，无 Root 时明确说明手动重启）
 */
@Composable
fun FeatureDetailScreen(
    pageId: FeaturePageId,
    refreshTrigger: Int = 0,
    onBack: () -> Unit,
    onNavigatePage: (FeaturePageId) -> Unit,
    onOpenRegionSearch: () -> Unit,
    onConfigChanged: () -> Unit,
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
    var showActivationGuide by rememberSaveable { mutableStateOf(false) }
    var pendingRestartConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingRestartTitle by remember { mutableStateOf("") }
    var pendingRestartMessage by remember { mutableStateOf("") }

    // 供子页面注册未保存返回拦截（例如商店跨区高级输入框修改但未保存）
    var childInterceptBack by remember(pageId) { mutableStateOf<(() -> Boolean)?>(null) }

    fun recheckEnvironment(showFeedback: Boolean = false) {
        scope.launch {
            val rootOk = withContext(Dispatchers.IO) { RootShell.hasRoot(forceRefresh = true) }
            val xpOk = App.getXposedService() != null || App.getRemotePrefs() != null
            hasRoot = rootOk
            xposedReady = xpOk
            if (showFeedback) {
                val msg = if (isZh) {
                    "检测完成：LSPosed ${if (xpOk) "已激活" else "未激活"} · Root ${if (rootOk) "已授权" else "未授权"}"
                } else {
                    "Checked: LSPosed ${if (xpOk) "Active" else "Inactive"} · Root ${if (rootOk) "Granted" else "Unavailable"}"
                }
                snackbar.showSnackbar(msg)
            }
        }
    }

    LaunchedEffect(refreshTrigger, pageId) {
        csc = CscPrefs.load(context)
        tuning = TuningPrefs.load(context)
        selectedPassApps = TuningPrefs.selectedApps(context)
        regionCleanupPending = RegionApplyState.isPending(context)
        xposedReady = App.getXposedService() != null || App.getRemotePrefs() != null
        hasRoot = withContext(Dispatchers.IO) { RootShell.hasRoot() }
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

    val pageTitle = when (pageId) {
        FeaturePageId.STORE_REGION -> if (isZh) "商店跨区" else "Store Region Unlock"
        FeaturePageId.DEVICE_SPOOF -> if (isZh) "机型伪装" else "Device Model Spoofing"
        FeaturePageId.PER_APP_MODEL -> if (isZh) "分应用机型" else "Per-App Device Models"
        FeaturePageId.WECHAT_STEPS -> if (isZh) "微信步数设置" else "WeChat Step Settings"
        FeaturePageId.WECHAT_ANTI_RECALL -> if (isZh) "微信消息防撤回" else "WeChat Anti-Recall"
        FeaturePageId.FAST_CHARGE -> if (isZh) "亮屏快充" else "Screen-On Fast Charge"
        FeaturePageId.BYPASS_POWER -> if (isZh) "应用旁路供电" else "App Bypass Power"
        FeaturePageId.ONEUI_HIDDEN -> if (isZh) "One UI 常用隐藏功能" else "One UI Hidden Features"
        FeaturePageId.CUSTOM_CSC_FEATURE -> if (isZh) "自定义 CscFeature" else "Custom CscFeature"
        FeaturePageId.CUSTOM_SYS_PROPS -> if (isZh) "自定义 SystemProperties" else "Custom SystemProperties"
    }

    val pageSubtitle = when (pageId) {
        FeaturePageId.STORE_REGION -> if (isZh) "切换三星应用商店与主题商店所在地区，不影响物理 SIM 卡与信号" else "Switch Galaxy Store region without affecting physical SIM or signal"
        FeaturePageId.DEVICE_SPOOF -> if (isZh) "修改应用识别到的手机型号（如社交平台小尾巴、游戏高帧率）" else "Modify device model identity visible to target apps"
        FeaturePageId.PER_APP_MODEL -> if (isZh) "为不同应用单独指定伪装机型，未单独设置的应用跟随默认机型" else "Set individual models per app; others follow the default model"
        FeaturePageId.WECHAT_STEPS -> if (isZh) "自动递增或按倍率放大今日微信运动步数，达到上限自动停止" else "Auto-increment or multiply daily WeChat steps up to your cap"
        FeaturePageId.WECHAT_ANTI_RECALL -> if (isZh) "阻止对方撤回微信消息，保留撤回的文字、图片、语音和视频" else "Prevent others from recalling WeChat messages and media"
        FeaturePageId.FAST_CHARGE -> if (isZh) "亮屏边用边充时解除充电限流，达到安全温度自动恢复系统温控" else "Unlock full charging speed while screen is on with thermal safety cutoff"
        FeaturePageId.BYPASS_POWER -> if (isZh) "运行指定游戏时由充电器直接给主板供电，减少电池发热与损耗" else "Power motherboard directly while gaming to reduce heat and battery wear"
        FeaturePageId.ONEUI_HIDDEN -> if (isZh) "一键开启三星系统内置的实用隐藏功能，无需编写代码或配置文件" else "Enable built-in Samsung One UI hidden features with one tap"
        FeaturePageId.CUSTOM_CSC_FEATURE -> if (isZh) "按键值对添加或修改三星 CscFeature 参数，重启对应系统应用生效" else "Add or edit Samsung CscFeature key-value overrides"
        FeaturePageId.CUSTOM_SYS_PROPS -> if (isZh) "按键值对添加或修改系统属性返回值，重启目标应用生效" else "Add or edit SystemProperties key-value overrides"
    }

    val requiresLsp = pageId !in setOf(FeaturePageId.FAST_CHARGE, FeaturePageId.BYPASS_POWER)
    val requiresRoot = pageId in setOf(FeaturePageId.FAST_CHARGE, FeaturePageId.BYPASS_POWER)

    if (pageId == FeaturePageId.CUSTOM_CSC_FEATURE || pageId == FeaturePageId.CUSTOM_SYS_PROPS) {
        CustomKeyValueFeaturePage(
            isCscFeature = pageId == FeaturePageId.CUSTOM_CSC_FEATURE,
            title = pageTitle,
            subtitle = pageSubtitle,
            csc = csc,
            xposedReady = xposedReady,
            isZh = isZh,
            onShowGuide = { showActivationGuide = true },
            onSaveMap = { updatedMap ->
                if (pageId == FeaturePageId.CUSTOM_CSC_FEATURE) {
                    updateCsc(if (isZh) "已保存自定义 CscFeature，重启目标应用后生效" else "Saved custom CscFeature") {
                        it.copy(spoofSemCscFeature = true, customCscFeatures = updatedMap)
                    }
                } else {
                    updateCsc(if (isZh) "已保存自定义系统属性，重启目标应用后生效" else "Saved custom SystemProperties") {
                        it.copy(customProps = updatedMap)
                    }
                }
            },
            onBack = onBack,
        )
        ActivationGuideDialog(
            show = showActivationGuide,
            isZh = isZh,
            xposedReady = xposedReady,
            rootReady = hasRoot,
            onOpenLsposed = {
                scope.launch(Dispatchers.IO) { LsposedScopeHelper.launchLsposedManager(context) }
            },
            onRetest = { recheckEnvironment(showFeedback = true) },
            onDismiss = { showActivationGuide = false },
        )
        return
    }

    MiuixPageScaffold(
        title = pageTitle,
        subtitle = pageSubtitle,
        onBack = {
            val intercepted = childInterceptBack?.invoke() == true
            if (!intercepted) {
                onBack()
            }
        },
        snackbarHostState = snackbar,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 按需前置条件引导横幅（仅在影响当前功能时展示）
            if (requiresLsp && !xposedReady) {
                OneUiNoticeBanner(
                    text = if (isZh) "模块尚未激活：此功能需在 LSPosed 中启用本模块后生效" else "Module not active: enable WeiTiao in LSPosed to use this feature",
                    icon = Icons.Default.Warning,
                    contentColor = IosGlassTokens.IosAmber,
                    actionText = if (isZh) "查看激活步骤" else "How to Activate",
                    onActionClick = { showActivationGuide = true },
                )
            }
            if (requiresRoot && !hasRoot) {
                OneUiNoticeBanner(
                    text = if (isZh) "此功能需要 Root 权限：请在 Magisk / KernelSU 中授予权限" else "Root required: grant superuser access in Magisk / KernelSU",
                    icon = Icons.Default.Warning,
                    contentColor = IosGlassTokens.IosAmber,
                    actionText = if (isZh) "去授权 / 重测" else "Grant / Retry",
                    onActionClick = { recheckEnvironment(showFeedback = true) },
                )
            }

            when (pageId) {
                FeaturePageId.STORE_REGION -> StoreRegionDetailContent(
                    csc = csc,
                    isZh = isZh,
                    hasRoot = hasRoot,
                    regionCleanupPending = regionCleanupPending,
                    onRegisterBackInterceptor = { interceptor -> childInterceptBack = interceptor },
                    onExitAfterSaveOrDiscard = onBack,
                    onUpdateCsc = ::updateCsc,
                    onOpenRegionSearch = onOpenRegionSearch,
                    onClearStoreNow = {
                        pendingRestartTitle = if (isZh) "应用并刷新商店？" else "Apply & Refresh Store?"
                        pendingRestartMessage = if (isZh) {
                            "将强制关闭三星应用商店与主题商店并清理其地区缓存，重新打开商店即可加载新地区内容。"
                        } else {
                            "Galaxy Store & Theme Store will be closed and their region cache cleared so the new region loads immediately."
                        }
                        pendingRestartConfirm = {
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
                        }
                    },
                )

                FeaturePageId.DEVICE_SPOOF -> DeviceSpoofDetailContent(
                    csc = csc,
                    isZh = isZh,
                    hasRoot = hasRoot,
                    onUpdateCsc = ::updateCsc,
                    onOpenPerAppPage = { onNavigatePage(FeaturePageId.PER_APP_MODEL) },
                    onRequestRestartTargets = { explicitTargets ->
                        val combinedTargets = explicitTargets + csc.perAppDeviceModels.keys
                        if (!hasRoot) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    if (combinedTargets.isNotEmpty()) {
                                        LsposedScopeHelper.requestScopesViaService(combinedTargets)
                                    }
                                }
                                snackbar.showSnackbar(
                                    if (isZh) "配置已保存，当前无 Root 权限，请手动关闭并重新打开目标应用使其生效"
                                    else "Saved. Please manually restart target apps to apply the new model"
                                )
                            }
                        } else {
                            pendingRestartTitle = if (isZh) "立即重启目标应用？" else "Restart Target Apps?"
                            pendingRestartMessage = if (isZh) {
                                "将强制关闭目标应用以立即加载新的伪装机型，请确保目标应用内没有未保存的内容。"
                            } else {
                                "Target apps will be force-stopped so the new device model takes effect immediately."
                            }
                            pendingRestartConfirm = {
                                scope.launch {
                                    val count = withContext(Dispatchers.IO) {
                                        if (combinedTargets.isNotEmpty()) {
                                            val synced = LsposedScopeHelper.syncScopesWithRoot(context, combinedTargets)
                                            if (!synced) LsposedScopeHelper.requestScopesViaService(combinedTargets)
                                        }
                                        val toRestart = LsposedScopeHelper.resolveRestartableTargetPackages(combinedTargets)
                                        StoreCleaner.forceStopPackages(toRestart)
                                    }
                                    val msg = if (count > 0) {
                                        context.getString(R.string.toast_device_targets_restarted, count)
                                    } else {
                                        if (isZh) "作用域已同步，请重新打开目标应用查看伪装效果"
                                        else "Scope synced. Reopen target apps to verify."
                                    }
                                    snackbar.showSnackbar(msg)
                                }
                            }
                        }
                    },
                    onShowSnackbar = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                )

                FeaturePageId.PER_APP_MODEL -> PerAppModelDetailContent(
                    csc = csc,
                    isZh = isZh,
                    hasRoot = hasRoot,
                    onUpdateCsc = ::updateCsc,
                    onRequestRestartSingleApp = { pkg, appLabel ->
                        if (!hasRoot) {
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (isZh) "配置已保存，请手动关闭并重开「$appLabel」使其生效"
                                    else "Saved. Please manually restart $appLabel",
                                )
                            }
                        } else {
                            pendingRestartTitle = if (isZh) "重启「$appLabel」？" else "Restart $appLabel?"
                            pendingRestartMessage = if (isZh) {
                                "将强制停止「$appLabel」，重新打开后新的专属机型立即生效。"
                            } else {
                                "$appLabel will be force-stopped so its new model takes effect."
                            }
                            pendingRestartConfirm = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        val allTargets = csc.deviceTargetPackages + csc.perAppDeviceModels.keys + pkg
                                        LsposedScopeHelper.syncScopesWithRoot(context, allTargets)
                                        StoreCleaner.forceStopPackages(listOf(pkg)) > 0
                                    }
                                    snackbar.showSnackbar(
                                        if (ok) {
                                            if (isZh) "已重启 $appLabel，重新打开即刻生效" else "Restarted $appLabel"
                                        } else {
                                            if (isZh) "配置已保存，请手动重启 $appLabel 使其生效" else "Saved. Please restart $appLabel manually"
                                        },
                                    )
                                }
                            }
                        }
                    },
                    onShowSnackbar = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                )

                FeaturePageId.WECHAT_STEPS,
                FeaturePageId.WECHAT_ANTI_RECALL -> WechatEnhancementDetailContent(
                    initialSubTab = if (pageId == FeaturePageId.WECHAT_ANTI_RECALL) 1 else 0,
                    tuning = tuning,
                    hasRoot = hasRoot,
                    isZh = isZh,
                    onSwitchSubPage = { idx ->
                        onNavigatePage(if (idx == 0) FeaturePageId.WECHAT_STEPS else FeaturePageId.WECHAT_ANTI_RECALL)
                    },
                    onUpdateTuning = ::updateTuning,
                    onRequestRestartWechat = {
                        if (!hasRoot) {
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (isZh) "配置已自动保存，当前无 Root 权限，请手动划掉微信后台并重开"
                                    else "Saved. Without Root, please close and reopen WeChat manually.",
                                )
                            }
                        } else {
                            pendingRestartTitle = if (isZh) "立即重启微信？" else "Restart WeChat Now?"
                            pendingRestartMessage = if (isZh) {
                                "将通过 Root 强制停止并重新启动微信以加载最新配置。"
                            } else {
                                "WeChat will be force-stopped and relaunched via Root to apply settings."
                            }
                            pendingRestartConfirm = {
                                scope.launch {
                                    val code = withContext(Dispatchers.IO) {
                                        RootShell.exec(
                                            "am force-stop com.tencent.mm && monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1",
                                        ).first
                                    }
                                    snackbar.showSnackbar(
                                        if (code == 0) context.getString(R.string.restart_wechat_done)
                                        else context.getString(R.string.restart_wechat_failed),
                                    )
                                }
                            }
                        }
                    },
                )

                FeaturePageId.FAST_CHARGE,
                FeaturePageId.BYPASS_POWER -> PowerOptimizationDetailContent(
                    initialSubTab = if (pageId == FeaturePageId.BYPASS_POWER) 1 else 0,
                    tuning = tuning,
                    selectedPassApps = selectedPassApps,
                    hasRoot = hasRoot,
                    isZh = isZh,
                    onSwitchSubPage = { idx ->
                        onNavigatePage(if (idx == 0) FeaturePageId.FAST_CHARGE else FeaturePageId.BYPASS_POWER)
                    },
                    onRequireRoot = { onResult ->
                        scope.launch {
                            val granted = withContext(Dispatchers.IO) { RootShell.hasRoot(forceRefresh = true) }
                            hasRoot = granted
                            onResult(granted)
                            if (!granted) {
                                snackbar.showSnackbar(
                                    if (isZh) "开启失败：未获得 Root 权限，请在 Magisk / KernelSU 中授权"
                                    else "Failed to enable: Root access was not granted",
                                )
                            }
                        }
                    },
                    onUpdateTuning = ::updateTuning,
                    onUpdatePassApps = { updated ->
                        selectedPassApps = updated
                        TuningPrefs.saveSelectedApps(context, updated)
                        TuningPrefs.save(context, tuning)
                        onConfigChanged()
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.saved_toast)) }
                    },
                )

                FeaturePageId.ONEUI_HIDDEN -> OneUiHiddenFeaturesDetailContent(
                    csc = csc,
                    isZh = isZh,
                    onUpdateCsc = ::updateCsc,
                    onOpenCustomCscFeature = { onNavigatePage(FeaturePageId.CUSTOM_CSC_FEATURE) },
                )

                FeaturePageId.CUSTOM_CSC_FEATURE,
                FeaturePageId.CUSTOM_SYS_PROPS -> Unit
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    ConfirmRestartAppsDialog(
        show = pendingRestartConfirm != null,
        title = pendingRestartTitle,
        message = pendingRestartMessage,
        confirmText = if (isZh) "立即执行" else "Confirm",
        cancelText = stringResource(R.string.cancel),
        onDismiss = { pendingRestartConfirm = null },
        onConfirm = {
            val action = pendingRestartConfirm
            pendingRestartConfirm = null
            action?.invoke()
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
        onRetest = { recheckEnvironment(showFeedback = true) },
        onDismiss = { showActivationGuide = false },
    )
}

@Composable
internal fun FeatureSelectTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bgColor = if (selected) IosGlassTokens.activeTileBg() else MiuixTheme.colorScheme.secondaryContainer
    val titleColor = if (selected) IosGlassTokens.activeTileFg() else MiuixTheme.colorScheme.onSurface
    val subColor = if (selected) {
        IosGlassTokens.activeTileFg().copy(alpha = 0.85f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                fontSize = 10.5.sp,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun FeatureActionPill(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val displayColor = if (enabled) color else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(IosGlassTokens.pastelBg(displayColor))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = displayColor,
            maxLines = 1,
        )
    }
}
