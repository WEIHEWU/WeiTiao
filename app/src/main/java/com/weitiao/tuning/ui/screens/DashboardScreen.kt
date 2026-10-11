package com.weitiao.tuning.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weitiao.tuning.App
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.HookLiveness
import com.weitiao.tuning.core.LanguageManager
import com.weitiao.tuning.core.NativeDevice
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.StoreCleaner
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiBadge
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.theme.OneUiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    MiuixPageScaffold(
        title = stringResource(R.string.topbar_title),
        subtitle = stringResource(R.string.dashboard_subtitle),
        snackbarHostState = snackbar,
        actions = {
            IconButton(
                onClick = {
                    haptics.tap()
                    LanguageManager.toggle(context)
                    LanguageManager.recreateActivity(context)
                },
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = stringResource(R.string.switch_language),
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
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
                            .size(38.dp)
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
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when (moduleState) {
                                ModuleState.ACTIVE -> stringResource(R.string.lsposed_ready_desc, heartbeatCount)
                                ModuleState.NO_SCOPE -> stringResource(R.string.lsposed_scope_missing_desc)
                                ModuleState.NOT_CONNECTED -> stringResource(R.string.lsposed_not_ready_desc)
                            },
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
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
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                // 实时状态网格
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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

            // 核心业务入口列表（Miuix SuperArrow）
            OneUiIslandCard(
                title = stringResource(R.string.section_features),
                contentPadding = 10.dp,
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
                    modifier = Modifier.padding(start = 48.dp, end = 8.dp),
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

@Composable
private fun StatusTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    isHighlight: Boolean,
) {
    val isDark = isSystemInDarkTheme()
    val tileShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(tileShape)
            .background(
                if (isDark) MiuixTheme.colorScheme.surfaceContainerHighest
                else MiuixTheme.colorScheme.surface
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            fontSize = 13.5.sp,
            fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.SemiBold,
            color = if (isHighlight) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
