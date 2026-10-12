package com.weitiao.tuning.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixHeroStatusCard
import com.weitiao.tuning.ui.components.MiuixMetricCard
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.MiuixQuickTile
import com.weitiao.tuning.ui.components.MiuixSectionTitle
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.theme.OneUiTokens
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SnackbarHostState

private enum class ModuleState { NOT_CONNECTED, NO_SCOPE, ACTIVE }

@Composable
fun DashboardScreen(
    refreshTrigger: Int = 0,
    onOpenSpoof: () -> Unit,
    onOpenTuning: () -> Unit,
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

    val initialConnected = App.getXposedService() != null || App.getRemotePrefs() != null
    var moduleState by remember {
        mutableStateOf(if (initialConnected) ModuleState.ACTIVE else ModuleState.NOT_CONNECTED)
    }
    var heartbeatCount by remember { mutableIntStateOf(if (initialConnected) 1 else 0) }
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

    fun refreshModuleState() {
        if (App.getXposedService() == null && App.getRemotePrefs() == null) {
            moduleState = ModuleState.NOT_CONNECTED
            heartbeatCount = 0
            return
        }
        val pkgs = liveness
        if (pkgs != null && pkgs.isEmpty()) {
            heartbeatCount = 0
            moduleState = ModuleState.NO_SCOPE
        } else {
            heartbeatCount = pkgs?.size ?: 1
            moduleState = ModuleState.ACTIVE
        }
    }

    fun refreshState() {
        csc = CscPrefs.load(context)
        tuning = TuningPrefs.load(context)
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

    fun updateCsc(block: (CscConfig) -> CscConfig) {
        val next = block(csc)
        csc = next
        CscPrefs.save(context, next)
        onConfigChanged()
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

    val isServiceReady = moduleState == ModuleState.ACTIVE || hasRoot
    val enabledText = if (isZh) "已启用" else "Enabled"
    val disabledText = if (isZh) "未启用" else "Disabled"

    MiuixPageScaffold(
        title = stringResource(R.string.topbar_title),
        snackbarHostState = snackbar,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                        val success = StoreCleaner.clearStoreDataRoot()
                        if (success) {
                            RegionApplyState.markApplied(context)
                            regionCleanupPending = false
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.clear_store_success)) }
                        } else {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.clear_store_fail)) }
                        }
                    },
                )
            }

            // 第一层：主状态横幅大卡（带右下角超大半裁切圆环对勾水印）
            val modeLabel = if (moduleState == ModuleState.ACTIVE) {
                if (isZh) "LSPosed 推荐模式" else "LSPosed Mode"
            } else {
                if (isZh) "Root 硬件直控" else "Root Mode"
            }
            MiuixHeroStatusCard(
                statusTitle = when {
                    moduleState == ModuleState.ACTIVE -> if (isZh) "运行中" else "Active"
                    hasRoot -> if (isZh) "Root 就绪" else "Root Ready"
                    else -> if (isZh) "待激活" else "Standby"
                },
                pillText = "v${BuildConfig.VERSION_NAME}",
                line1Text = if (isZh) {
                    "版本 v${BuildConfig.VERSION_NAME} · $modeLabel · 服务运行正常"
                } else {
                    "v${BuildConfig.VERSION_NAME} · $modeLabel · Running normally"
                },
                line2Text = if (isZh) {
                    "物理 SIM 与基带 100% 原生免注入 · 实时状态已同步"
                } else {
                    "Physical SIM & baseband 100% native · Live synced"
                },
                isWarning = !isServiceReady,
                onClick = onOpenSettings,
            )

            // 第二层：3×2 快捷状态磁贴网格（点亮即变柔和天蓝底 #E7F0FD + 小米蓝字 #267AF6）
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MiuixQuickTile(
                        label = if (isZh) "商店跨区" else "Store Region",
                        statusText = if (csc.enabled && csc.salesCode.isNotBlank()) enabledText else disabledText,
                        icon = Icons.Default.Public,
                        active = csc.enabled && csc.salesCode.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (csc.salesCode.isNotBlank()) {
                                updateCsc { it.copy(enabled = !it.enabled) }
                            } else {
                                onOpenSpoof()
                            }
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "机型伪装" else "Device Spoof",
                        statusText = if (csc.spoofDevice) enabledText else disabledText,
                        icon = Icons.Default.Smartphone,
                        active = csc.spoofDevice,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (csc.deviceName.isNotBlank()) {
                                updateCsc { it.copy(spoofDevice = !it.spoofDevice) }
                            } else {
                                onOpenSpoof()
                            }
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "微信计步" else "WeChat Steps",
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MiuixQuickTile(
                        label = if (isZh) "消息防撤回" else "Anti-Recall",
                        statusText = if (tuning.antiRecall) enabledText else disabledText,
                        icon = Icons.Default.Shield,
                        active = tuning.antiRecall,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            updateTuning { it.copy(antiRecall = !it.antiRecall) }
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "亮屏快充" else "Fast Charge",
                        statusText = if (tuning.brightScreenFastCharge) enabledText else disabledText,
                        icon = Icons.Default.Bolt,
                        active = tuning.brightScreenFastCharge,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            updateTuning { it.copy(brightScreenFastCharge = !it.brightScreenFastCharge) }
                        },
                    )
                    MiuixQuickTile(
                        label = if (isZh) "旁路供电" else "Bypass Power",
                        statusText = if (tuning.passThrough) enabledText else disabledText,
                        icon = Icons.Default.Power,
                        active = tuning.passThrough,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            updateTuning { it.copy(passThrough = !it.passThrough) }
                        },
                    )
                }
            }

            // 第三层：2×2 马卡龙柔和图标 + 大数字/大核心值实时统计卡
            MiuixSectionTitle(
                title = if (isZh) "实时状态统计" else "Live Telemetry",
                trailingText = if (isPlugged) {
                    if (isZh) "⚡ 已接通电源 · $batteryLevel%" else "⚡ Plugged · $batteryLevel%"
                } else {
                    if (isZh) "🔋 电池供电 · $batteryLevel%" else "🔋 On Battery · $batteryLevel%"
                },
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MiuixMetricCard(
                        valueText = if (csc.enabled && csc.salesCode.isNotBlank()) {
                            "${csc.salesCode} · ${csc.countryCode}"
                        } else {
                            rawSalesCode.ifBlank { "CHC" }
                        },
                        labelText = if (csc.enabled && csc.salesCode.isNotBlank()) {
                            if (isZh) "伪装商店区域 (${csc.simMcc})" else "Spoofed Region (${csc.simMcc})"
                        } else {
                            if (isZh) "原生底包区域 ($rawSimOperator)" else "Native Region ($rawSimOperator)"
                        },
                        icon = Icons.Default.Public,
                        accentColor = IosGlassTokens.IosGreen,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSpoof,
                    )

                    MiuixMetricCard(
                        valueText = "${String.format(Locale.US, "%.1f", batteryTemp)} °C",
                        labelText = if (tuning.brightScreenFastCharge) {
                            if (isZh) "快充守护中 (44°C 熔断)" else "Fast Charge (44°C Cutoff)"
                        } else {
                            if (isZh) "实时电池温度" else "Battery Temperature"
                        },
                        icon = Icons.Default.Bolt,
                        accentColor = IosGlassTokens.IosRed,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenTuning,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MiuixMetricCard(
                        valueText = if (tuning.stepEnabled) {
                            if (tuning.stepAuto) {
                                if (isZh) "自动递增" else "Auto Step"
                            } else {
                                "${tuning.stepMultiplier}x"
                            }
                        } else {
                            if (isZh) "原生步数" else "Native Steps"
                        },
                        labelText = if (isZh) {
                            "每日封顶 ${tuning.stepMaximum} 步"
                        } else {
                            "Daily Cap ${tuning.stepMaximum}"
                        },
                        icon = Icons.Default.Sync,
                        accentColor = IosGlassTokens.IosBlue,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenTuning,
                    )

                    MiuixMetricCard(
                        valueText = if (csc.spoofDevice && csc.deviceName.isNotBlank()) {
                            csc.deviceName
                        } else {
                            Build.MODEL
                        },
                        labelText = if (csc.spoofDevice) {
                            if (isZh) "伪装机型 (${csc.deviceTargetPackages.size} 应用)" else "Spoofed (${csc.deviceTargetPackages.size} apps)"
                        } else {
                            if (isZh) "系统原生型号" else "Native Device Model"
                        },
                        icon = Icons.Default.Memory,
                        accentColor = IosGlassTokens.IosAmber,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSpoof,
                    )
                }
            }

            // 第四层：快捷维护双列动作卡
            MiuixSectionTitle(
                title = if (isZh) "快捷维护" else "Quick Actions",
                trailingText = if (isZh) "点击立即执行" else "Tap to run",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MiuixMetricCard(
                    valueText = if (isZh) "重置商店" else "Reset Store",
                    labelText = if (isZh) "清理缓存立即重判区" else "Clear store cache now",
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
                    labelText = if (isZh) "强停并自动拉起微信" else "Force stop & relaunch",
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
