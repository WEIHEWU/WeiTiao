package com.weitiao.tuning.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weitiao.tuning.App
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscConstants
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.LanguageManager
import com.weitiao.tuning.core.NativeDevice
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.StoreCleaner
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.core.HookLiveness
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.iosLiquidGlass
import com.weitiao.tuning.ui.components.OneUiBadge
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiLargeHeader
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.theme.OneUiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 顶部模块状态。三种状态的用户症状都是"功能没生效"，但排查方向完全不同，
 * 所以必须分开呈现而不是合并成一个"未生效"：
 *
 * - [NOT_CONNECTED]：框架服务都没连上，配置根本推不出去 —— 查 LSPosed 是否安装/运行。
 * - [NO_SCOPE]：框架连着，但没有任何目标进程加载过本模块 —— 查作用域勾选、模块是否启用。
 * - [ACTIVE]：至少有一个作用域进程加载过本模块（有 hook 心跳）—— 真生效了。
 */
private enum class ModuleState { NOT_CONNECTED, NO_SCOPE, ACTIVE }

@Composable
fun DashboardScreen(
    refreshTrigger: Int = 0,
    onOpenSpoof: () -> Unit,
    onOpenTuning: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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

    val activeTuningCount = listOf(
        tuning.stepEnabled,
        tuning.antiRecall,
        tuning.brightScreenFastCharge,
        tuning.passThrough,
    ).count { it }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // One UI 8.5 大标题视区（右上角提供中英双语切换）
            OneUiLargeHeader(
                title = stringResource(R.string.topbar_title),
                subtitle = stringResource(R.string.dashboard_subtitle),
                actions = {
                    val isDark = isSystemInDarkTheme()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.12f)
                                else Color.Black.copy(alpha = 0.06f)
                            )
                            .border(
                                width = 0.6.dp,
                                color = if (isDark) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.8f),
                                shape = CircleShape,
                            )
                            .clickable {
                                haptics.tap()
                                LanguageManager.toggle(context)
                                LanguageManager.recreateActivity(context)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = stringResource(R.string.switch_language),
                            tint = IosGlassTokens.IosBlue,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 待清理商店提示横条
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

                // 智能中枢 Hero 状态总览大卡片
                OneUiIslandCard(
                    contentPadding = 16.dp,
                ) {
                    val statusColor = when (moduleState) {
                        ModuleState.ACTIVE -> IosGlassTokens.IosGreen
                        ModuleState.NO_SCOPE -> IosGlassTokens.IosAmber
                        ModuleState.NOT_CONNECTED -> IosGlassTokens.IosRed
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(IosGlassTokens.IosIconCorner)
                                .background(
                                    when (moduleState) {
                                        ModuleState.ACTIVE -> Brush.linearGradient(listOf(IosGlassTokens.IosGreen, Color(0xFF28CD41)))
                                        ModuleState.NO_SCOPE -> Brush.linearGradient(listOf(IosGlassTokens.IosAmber, Color(0xFFFF9F0A)))
                                        ModuleState.NOT_CONNECTED -> Brush.linearGradient(listOf(IosGlassTokens.IosRed, Color(0xFFFF453A)))
                                    }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when (moduleState) {
                                    ModuleState.ACTIVE -> stringResource(R.string.lsposed_ready)
                                    ModuleState.NO_SCOPE -> stringResource(R.string.lsposed_scope_missing)
                                    ModuleState.NOT_CONNECTED -> stringResource(R.string.lsposed_not_ready)
                                },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    letterSpacing = (-0.2).sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when (moduleState) {
                                    ModuleState.ACTIVE -> stringResource(R.string.lsposed_ready_desc, heartbeatCount)
                                    ModuleState.NO_SCOPE -> stringResource(R.string.lsposed_scope_missing_desc)
                                    ModuleState.NOT_CONNECTED -> stringResource(R.string.lsposed_not_ready_desc)
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 13.sp,
                                    letterSpacing = (-0.1).sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }

                        OneUiBadge(
                            text = when (moduleState) {
                                ModuleState.ACTIVE -> stringResource(R.string.badge_active)
                                ModuleState.NO_SCOPE -> stringResource(R.string.badge_no_scope)
                                ModuleState.NOT_CONNECTED -> stringResource(R.string.badge_not_connected)
                            },
                            color = statusColor,
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    )

                    // 实时状态网格
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatusTile(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.status_csc_label),
                            value = if (csc.enabled && csc.salesCode.isNotBlank()) csc.salesCode else "$rawSalesCode (${stringResource(R.string.native_device)})",
                            isHighlight = csc.enabled,
                        )
                        StatusTile(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.status_model_label),
                            value = if (csc.enabled && csc.spoofDevice && csc.deviceName.isNotBlank()) csc.deviceName else stringResource(R.string.native_device),
                            isHighlight = csc.enabled && csc.spoofDevice,
                        )
                        StatusTile(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.status_tuning_label),
                            value = if (activeTuningCount > 0) stringResource(R.string.items_active, activeTuningCount) else stringResource(R.string.disabled),
                            isHighlight = activeTuningCount > 0,
                        )
                    }
                }

                // 核心业务入口列表（iOS Inset Grouped）
                OneUiIslandCard(
                    title = stringResource(R.string.section_features),
                    contentPadding = 8.dp,
                ) {
                    OneUiItemRow(
                        title = stringResource(R.string.dashboard_spoof_title),
                        subtitle = buildString {
                            if (csc.enabled) {
                                append(stringResource(R.string.dashboard_spoof_enabled))
                                if (csc.salesCode.isNotBlank()) append(" · ${csc.salesCode}")
                                if (csc.spoofDevice && csc.deviceName.isNotBlank()) append(" · ${csc.deviceName}")
                            } else {
                                append(stringResource(R.string.dashboard_spoof_disabled))
                            }
                        },
                        icon = Icons.Default.Public,
                        iconTint = IosGlassTokens.IosBlue,
                        badgeText = if (csc.enabled) csc.salesCode.ifBlank { "ON" } else null,
                        badgeColor = IosGlassTokens.IosBlue,
                        showChevron = true,
                        onClick = onOpenSpoof,
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp, end = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    )

                    OneUiItemRow(
                        title = stringResource(R.string.dashboard_tuning_title),
                        subtitle = if (activeTuningCount == 0) stringResource(R.string.dashboard_tuning_none)
                        else stringResource(R.string.dashboard_tuning_count, activeTuningCount),
                        icon = Icons.Default.Tune,
                        iconTint = IosGlassTokens.IosGreen,
                        badgeText = if (activeTuningCount > 0) stringResource(R.string.items_active, activeTuningCount) else null,
                        badgeColor = IosGlassTokens.IosGreen,
                        showChevron = true,
                        onClick = onOpenTuning,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    isHighlight: Boolean,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val tileShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .clip(tileShape)
            .background(if (isDark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.50f))
            .border(
                width = 0.6.dp,
                color = Color.White.copy(alpha = if (isDark) 0.18f else 0.70f),
                shape = tileShape,
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                letterSpacing = (-0.1).sp,
                fontWeight = FontWeight.Medium,
            ),
            color = IosGlassTokens.IosGray,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 13.5.sp,
                letterSpacing = (-0.2).sp,
            ),
            color = if (isHighlight) IosGlassTokens.IosBlue else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
