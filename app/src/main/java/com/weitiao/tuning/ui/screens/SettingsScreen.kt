package com.weitiao.tuning.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weitiao.tuning.App
import com.weitiao.tuning.BuildConfig
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.CscSpoof
import com.weitiao.tuning.core.HookLiveness
import com.weitiao.tuning.core.LanguageManager
import com.weitiao.tuning.core.LsposedScopeHelper
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.ui.components.ActivationGuideDialog
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiCollapsibleCard
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底部导航栏「设置」分栏：保留低频与高级诊断内容，高级技术信息默认折叠
 * - 环境与伪装自检（含重新检测按钮）
 * - Root 状态、电池无限制权限、LSPosed 作用域说明与界面语言切换
 * - 活跃宿主进程和诊断信息（默认折叠）
 * - 关于、版本、开源许可和免责声明（默认折叠）
 */
@Composable
fun SettingsScreen(
    refreshTrigger: Int = 0,
    bottomBar: (@Composable () -> Unit)? = null,
    onOpenFeature: ((FeaturePageId) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isZh = remember { Locale.getDefault().language.startsWith("zh") }

    var config by remember(refreshTrigger) { mutableStateOf(CscPrefs.load(context)) }
    var xposedConnected by remember {
        mutableStateOf(App.getXposedService() != null || App.getRemotePrefs() != null)
    }
    var probeTick by remember { mutableIntStateOf(0) }
    var hasRoot by remember { mutableStateOf(false) }
    var hookedPkgs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var nativeSalesCode by remember { mutableStateOf("") }
    var batteryRestricted by remember {
        mutableStateOf(TuningPrefs.isBatteryOptimizationRestricted(context))
    }
    var showActivationGuide by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(probeTick, refreshTrigger) {
        config = CscPrefs.load(context)
        xposedConnected = App.getXposedService() != null || App.getRemotePrefs() != null
        batteryRestricted = TuningPrefs.isBatteryOptimizationRestricted(context)
        withContext(Dispatchers.IO) {
            val root = RootShell.hasRoot(forceRefresh = probeTick > 0)
            val pkgs = HookLiveness.recentHookedPackages() ?: emptySet()
            val sysSales = runCatching {
                val clazz = Class.forName("android.os.SystemProperties")
                val m = clazz.getMethod("get", String::class.java, String::class.java)
                (m.invoke(null, "ro.csc.sales_code", "") as? String).orEmpty()
            }.getOrDefault("")
            withContext(Dispatchers.Main) {
                hasRoot = root
                hookedPkgs = pkgs
                nativeSalesCode = sysSales
            }
        }
    }

    val effectiveModel = CscSpoof.matchDeviceProp("ro.product.model", config) ?: Build.MODEL
    val effectiveCsc = CscSpoof.matchProp("ro.csc.sales_code", config)
        ?: nativeSalesCode.ifBlank { if (isZh) "原生默认" else "Native" }

    MiuixPageScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = if (isZh) "环境自检、权限设置与高级诊断" else "Self-Test, Permissions & Diagnostics",
        onBack = onBack,
        bottomBar = bottomBar,
        snackbarHostState = snackbarHostState,
        bottomNavigationPadding = 24.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. 环境与伪装自检
            OneUiIslandCard(
                title = stringResource(R.string.selftest_card_title),
                subtitle = stringResource(R.string.selftest_card_subtitle),
                icon = Icons.Default.BugReport,
            ) {
                OneUiItemRow(
                    title = if (isZh) "LSPosed 模块状态" else "LSPosed Module Status",
                    subtitle = if (xposedConnected) {
                        if (isZh) "模块已激活并连接远程配置服务" else "Module active and remote preferences connected"
                    } else {
                        if (isZh) "模块未激活，点击查看激活步骤" else "Module inactive — tap for activation guide"
                    },
                    icon = Icons.Default.Security,
                    iconTint = if (xposedConnected) IosGlassTokens.IosGreen else IosGlassTokens.IosAmber,
                    badgeText = if (xposedConnected) {
                        if (isZh) "已激活" else "Active"
                    } else {
                        if (isZh) "未激活" else "Inactive"
                    },
                    badgeColor = if (xposedConnected) IosGlassTokens.IosGreen else IosGlassTokens.IosAmber,
                    showChevron = !xposedConnected,
                    onClick = { showActivationGuide = true },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.selftest_root_status),
                    subtitle = if (hasRoot) {
                        if (isZh) "Root 权限已授予（支持亮屏快充、旁路供电与一键清商店缓存）"
                        else "Superuser granted (Fast charge, bypass power & store cleaner ready)"
                    } else {
                        if (isZh) "未获取 Root 权限，请在 Magisk / KernelSU 中授权后点击下方重新检测"
                        else "Root unavailable — grant in Magisk / KernelSU and re-test"
                    },
                    badgeText = if (hasRoot) {
                        if (isZh) "已授权" else "Granted"
                    } else {
                        if (isZh) "未获取" else "Unavailable"
                    },
                    badgeColor = if (hasRoot) IosGlassTokens.IosGreen else IosGlassTokens.IosAmber,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.selftest_build_model),
                    subtitle = if (config.spoofDevice && config.deviceName.isNotBlank()) {
                        if (isZh) "本机型号: ${Build.MODEL} → 伪装结果: $effectiveModel"
                        else "Real: ${Build.MODEL} → Spoofed: $effectiveModel"
                    } else {
                        if (isZh) "当前使用本机真实型号" else "Using real device model"
                    },
                    trailingValue = effectiveModel,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.selftest_csc_code),
                    subtitle = if (config.enabled && config.salesCode.isNotBlank()) {
                        if (isZh) "本机 CSC: ${nativeSalesCode.ifBlank { "-" }} → 伪装地区: ${config.salesCode}"
                        else "Real CSC: ${nativeSalesCode.ifBlank { "-" }} → Target: ${config.salesCode}"
                    } else {
                        if (isZh) "商店跨区未开启，保持本机默认区域" else "Store region unlock off"
                    },
                    trailingValue = effectiveCsc,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.selftest_refresh_btn),
                    subtitle = if (isZh) "立即重新检测 LSPosed、Root 与伪装状态" else "Re-check LSPosed, Root & spoofing status now",
                    icon = Icons.Default.Refresh,
                    iconTint = IosGlassTokens.IosTeal,
                    onClick = {
                        haptics.tap()
                        probeTick++
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (isZh) "已重新检测环境状态" else "Environment status refreshed",
                            )
                        }
                    },
                )
            }

            // 2. 权限、作用域与常规设置
            OneUiIslandCard(
                title = if (isZh) "权限、作用域与语言" else "Permissions, Scope & Language",
            ) {
                OneUiItemRow(
                    title = if (isZh) "LSPosed 作用域管理" else "LSPosed Scope Manager",
                    subtitle = if (isZh) "打开 LSPosed 管理器检查勾选的目标应用" else "Open LSPosed Manager to review target app scope",
                    icon = Icons.Default.Tune,
                    iconTint = IosGlassTokens.IosPurple,
                    showChevron = true,
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val ok = LsposedScopeHelper.launchLsposedManager(context)
                            if (!ok) {
                                withContext(Dispatchers.Main) {
                                    showActivationGuide = true
                                }
                            }
                        }
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = if (isZh) "后台电池无限制权限" else "Unrestricted Battery Usage",
                    subtitle = if (batteryRestricted) {
                        if (isZh) "当前受限：建议设为「不受限制」以保证充电温控服务稳定运行"
                        else "Restricted: tap to allow unrestricted background usage"
                    } else {
                        if (isZh) "已设为「不受限制」，后台充电与温控服务不会被休眠"
                        else "Unrestricted: background charging protection is active"
                    },
                    icon = Icons.Default.BatteryAlert,
                    iconTint = if (batteryRestricted) IosGlassTokens.IosAmber else IosGlassTokens.IosGreen,
                    badgeText = if (batteryRestricted) {
                        if (isZh) "去设置" else "Allow"
                    } else {
                        if (isZh) "已开启" else "Allowed"
                    },
                    badgeColor = if (batteryRestricted) IosGlassTokens.IosAmber else IosGlassTokens.IosGreen,
                    onClick = {
                        TuningPrefs.requestIgnoreBatteryOptimization(context)
                        batteryRestricted = TuningPrefs.isBatteryOptimizationRestricted(context)
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = if (isZh) "界面语言 / Language" else "UI Language",
                    subtitle = if (isZh) "点击切换中英文界面" else "Tap to switch between Chinese and English",
                    icon = Icons.Default.Language,
                    iconTint = IosGlassTokens.IosBlue,
                    trailingValue = if (isZh) "简体中文" else "English",
                    showChevron = true,
                    onClick = {
                        haptics.tap()
                        LanguageManager.toggle(context)
                        LanguageManager.recreateActivity(context)
                    },
                )
            }

            // 3. LSPosed 作用域推荐说明（折叠卡片）
            OneUiCollapsibleCard(
                title = if (isZh) "LSPosed 作用域推荐说明" else "LSPosed Scope Recommendations",
                subtitle = if (isZh) "查看各功能建议勾选的目标应用及注意事项" else "Recommended target apps for each feature",
                initiallyExpanded = false,
            ) {
                Text(
                    text = if (isZh) {
                        "• 商店跨区：建议勾选「三星应用商店 (com.sec.android.app.samsungapps)」与「三星主题商店 (com.samsung.android.themestore)」。\n" +
                            "• 微信增强：请勾选「微信 (com.tencent.mm)」。\n" +
                            "• 机型伪装：请勾选需要伪装型号的社交或游戏应用（也可在「机型伪装」页面让微调通过 Root 自动同步作用域）。\n" +
                            "• 安全提示：请勿勾选「系统框架 (android)」，仅勾选具体目标应用即可获得最佳稳定性与续航。"
                    } else {
                        "• Store Region: Check Galaxy Store (com.sec.android.app.samsungapps) and Theme Store (com.samsung.android.themestore).\n" +
                            "• WeChat Enhancement: Check WeChat (com.tencent.mm).\n" +
                            "• Device Spoofing: Check the specific social or game apps you want to spoof.\n" +
                            "• Safety Note: Do NOT check System Framework (android) in LSPosed."
                    },
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            // 4. 活跃宿主进程与高级诊断信息（默认折叠）
            OneUiCollapsibleCard(
                title = if (isZh) "活跃宿主进程与高级诊断" else "Active Host Processes & Diagnostics",
                subtitle = if (xposedConnected) {
                    if (isZh) "当前检测到 ${hookedPkgs.size} 个活跃注入进程（默认折叠）"
                    else "${hookedPkgs.size} active hooked process(es)"
                } else {
                    if (isZh) "查看进程注入心跳与诊断状态" else "View hook heartbeats & diagnostic status"
                },
                initiallyExpanded = false,
            ) {
                Text(
                    text = if (hookedPkgs.isEmpty()) {
                        if (isZh) "暂无活跃宿主进程记录。打开已勾选作用域的目标应用后，点击上方「重新检测」即可查看活跃进程。"
                        else "No active hooked processes recorded yet. Launch a target app and tap Re-test above."
                    } else {
                        if (isZh) "最近活跃注入进程：\n${hookedPkgs.joinToString("\n") { "• $it" }}"
                        else "Recently active hooked processes:\n${hookedPkgs.joinToString("\n") { "• $it" }}"
                    },
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            // 5. 关于、版本、开源许可与免责声明（默认折叠）
            OneUiCollapsibleCard(
                title = if (isZh) "关于、版本与免责声明" else "About, License & Disclaimer",
                subtitle = "WeiTiao v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                initiallyExpanded = false,
            ) {
                OneUiItemRow(
                    title = if (isZh) "应用版本" else "Version",
                    subtitle = "com.weitiao.tuning",
                    icon = Icons.Default.Info,
                    iconTint = IosGlassTokens.IosBlue,
                    trailingValue = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Text(
                    text = if (isZh) {
                        "开源地址：https://github.com/WEIHEWU/WeiTiao\n" +
                            "UI 框架：Miuix KMP (HyperOS / One UI 融合设计风格)\n\n" +
                            "免责声明：\n" +
                            "1. 本工具仅通过运行时 Hook 与受控温控守护服务提供区域切换、机型伪装与充电辅助，不修改系统分区与基带固件。\n" +
                            "2. 使用亮屏快充时建议保持默认 44°C 安全温控阈值，切勿在高温密闭环境下遮挡手机散热。"
                    } else {
                        "GitHub: https://github.com/WEIHEWU/WeiTiao\n" +
                            "UI Framework: Miuix KMP\n\n" +
                            "Disclaimer:\n" +
                            "1. WeiTiao operates via runtime hooks and controlled thermal guard services without modifying system partitions.\n" +
                            "2. Keep the recommended 44°C thermal cutoff when using Screen-On Fast Charge."
                    },
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    ActivationGuideDialog(
        show = showActivationGuide,
        isZh = isZh,
        xposedReady = xposedConnected,
        rootReady = hasRoot,
        onOpenLsposed = {
            scope.launch(Dispatchers.IO) { LsposedScopeHelper.launchLsposedManager(context) }
        },
        onRetest = { probeTick++ },
        onDismiss = { showActivationGuide = false },
    )
}
