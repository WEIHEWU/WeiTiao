package com.weitiao.tuning.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.CscConstants
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.CscSim
import com.weitiao.tuning.core.NativeDevice
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.core.StoreCleaner
import com.weitiao.tuning.data.CscEntry
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.OneUiBadge
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiLargeHeader
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.theme.OneUiTokens
import kotlinx.coroutines.launch

private data class GlobalPreset(
    val flag: String,
    val nameRes: Int,
    val salesCode: String,
    val simMcc: String,
    val countryCode: String,
    val tagRes: Int,
    val numeric: String,
    val operator: String,
    val mnc: String,
)

private val CURATED_GLOBAL_PRESETS = listOf(
    GlobalPreset("🇭🇰", R.string.preset_hk_name, "TGY", "454", "HK", R.string.preset_hk_tag, "45406", "SmarTone", "06"),
    GlobalPreset("🇺🇸", R.string.preset_us_name, "XAA", "310", "US", R.string.preset_us_tag, "310260", "T-Mobile", "260"),
    GlobalPreset("🇪🇺", R.string.preset_eu_name, "EUX", "262", "DE", R.string.preset_eu_tag, "26201", "T-Mobile", "01"),
    GlobalPreset("🇹🇼", R.string.preset_tw_name, "BRI", "466", "TW", R.string.preset_tw_tag, "46688", "Telecom", "88"),
    GlobalPreset("🇰🇷", R.string.preset_kr_name, "KOO", "450", "KR", R.string.preset_kr_tag, "45005", "SKT", "05"),
    GlobalPreset("🇨🇳", R.string.preset_cn_name, "CHC", "460", "CN", R.string.preset_cn_tag, "46000", "CHINA MOBILE", "00"),
)

@Composable
fun SpoofScreen(
    pendingSelection: CscEntry?,
    onConsumeSelection: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onConfigChanged: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(CscPrefs.load(context)) }
    var salesCodeInput by remember(config.salesCode) { mutableStateOf(config.salesCode) }
    var countryCodeInput by remember(config.countryCode) { mutableStateOf(config.countryCode) }
    var simMccInput by remember(config.simMcc) { mutableStateOf(config.simMcc) }

    var showDeviceTargetPicker by remember { mutableStateOf(false) }
    var showDeviceModelPicker by remember { mutableStateOf(false) }
    var showPresetConfirmDialog by remember { mutableStateOf<GlobalPreset?>(null) }
    var regionCleanupPending by remember { mutableStateOf(RegionApplyState.isPending(context)) }

    val nativeInfo = remember { NativeDevice.detect(context) }

    fun notifySaved(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    fun persist(updated: CscConfig, message: String? = null) {
        val previous = CscPrefs.load(context)
        config = updated
        CscPrefs.save(context, updated)
        if (hasRegionConfigChanged(previous, updated)) {
            RegionApplyState.markPending(context)
            regionCleanupPending = true
        }
        onConfigChanged()
        message?.let(::notifySaved)
    }

    LaunchedEffect(Unit) {
        val latest = CscPrefs.load(context)
        if (latest != config) {
            config = latest
            salesCodeInput = latest.salesCode
            countryCodeInput = latest.countryCode
            simMccInput = latest.simMcc
            regionCleanupPending = RegionApplyState.isPending(context)
        }
    }

    // 从全局 80 个原版预设检索库选中后，原子性更新 CSC + 国家代码 + SIM MCC 与完整数字代码
    LaunchedEffect(pendingSelection) {
        pendingSelection?.let { entry ->
            salesCodeInput = entry.code
            countryCodeInput = entry.countryCode
            simMccInput = entry.mcc
            val updated = config.copy(
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
            persist(
                updated,
                context.getString(R.string.preset_applied_toast, "${entry.country} (${entry.code})"),
            )
            val cleaned = StoreCleaner.clearStoreDataRoot()
            if (cleaned) {
                RegionApplyState.markApplied(context)
                regionCleanupPending = false
            }
            onConsumeSelection()
        }
    }

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
            OneUiLargeHeader(
                title = stringResource(R.string.dashboard_spoof_title),
                subtitle = stringResource(R.string.spoof_subtitle),
                onBack = onBack,
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
                                onOpenSettings()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
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
                // 卡片 1: 区域画像总览与商店生效状态
                OneUiIslandCard(contentPadding = 14.dp) {
                    OneUiItemRow(
                        title = stringResource(R.string.module_switch),
                        subtitle = if (config.enabled) stringResource(R.string.module_enabled)
                        else stringResource(R.string.module_disabled),
                        icon = Icons.Default.Public,
                        iconTint = if (config.enabled) OneUiTokens.SuccessGreen else OneUiTokens.TextSecondaryLight,
                        checked = config.enabled,
                        onCheckedChange = {
                            persist(config.copy(enabled = it))
                        },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )

                    // 当前生效参数 vs 出厂基准
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.target_region),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (config.enabled && config.salesCode.isNotBlank()) config.salesCode else nativeInfo.salesCode,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = if (config.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                OneUiBadge(
                                    text = if (config.enabled && config.countryCode.isNotBlank()) "ISO ${config.countryCode} · MCC ${config.simMcc}"
                                    else "ISO ${nativeInfo.countryCode} · MCC ${nativeInfo.simMcc}",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = stringResource(R.string.factory_baseline_format, nativeInfo.salesCode, nativeInfo.simMcc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                haptics.confirm()
                                StoreCleaner.restoreNativeTelephonyProps()
                                salesCodeInput = nativeInfo.salesCode
                                countryCodeInput = nativeInfo.countryCode
                                simMccInput = nativeInfo.simMcc
                                persist(
                                    config.copy(
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
                                    ),
                                    context.getString(R.string.restore_factory_success, nativeInfo.salesCode),
                                )
                            },
                            shape = OneUiTokens.PillShape,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_restore_factory),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }

                    // 商店判区待清理提示与一键清理按钮
                    if (regionCleanupPending) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OneUiNoticeBanner(
                            text = stringResource(R.string.region_changed_banner),
                            icon = Icons.Default.CleaningServices,
                            containerColor = OneUiTokens.WarningAmberContainerLight,
                            contentColor = OneUiTokens.WarningAmber,
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
                }

                // 卡片 2: 选择目标区域（单一全局搜索入口 + 6 大精选主流预设 + 参数微调）
                OneUiIslandCard(
                    title = stringResource(R.string.presets_title),
                    subtitle = stringResource(R.string.presets_subtitle),
                    contentPadding = 14.dp,
                ) {
                    // 全局唯一 CSC 搜索入口卡片
                    Card(
                        shape = OneUiTokens.ControlShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptics.tap()
                                onOpenSearch()
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.search_all_csc_btn),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(R.string.search_all_csc_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 6 大主流精选横向快速选择胶囊
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CURATED_GLOBAL_PRESETS.forEach { preset ->
                            val isCurrent = config.enabled && config.salesCode.equals(preset.salesCode, ignoreCase = true)
                            val presetName = stringResource(preset.nameRes)
                            val presetTag = stringResource(preset.tagRes)
                            Box(
                                modifier = Modifier
                                    .clip(OneUiTokens.ControlShape)
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .border(
                                        width = if (isCurrent) 1.5.dp else 0.dp,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = OneUiTokens.ControlShape,
                                    )
                                    .clickable {
                                        haptics.tap()
                                        showPresetConfirmDialog = preset
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(preset.flag, fontSize = 18.sp)
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                preset.salesCode,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = if (isCurrent) Color.White else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                            Text(
                                                preset.countryCode,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isCurrent) Color.White.copy(alpha = 0.8f)
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                        Text(
                                            presetTag,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isCurrent) Color.White.copy(alpha = 0.9f)
                                            else MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )

                    // 联动微调文本框（CSC、国家码、SIM MCC）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = salesCodeInput,
                            onValueChange = { input ->
                                val filtered = input.take(3).uppercase()
                                salesCodeInput = filtered
                                val autoCountry = CscConstants.countryCodeOf(filtered).orEmpty()
                                countryCodeInput = autoCountry
                                val autoMcc = CscSim.mccOfSalesCode(filtered).orEmpty()
                                if (autoMcc.isNotBlank()) simMccInput = autoMcc
                                persist(
                                    config.copy(
                                        salesCode = filtered,
                                        countryCode = autoCountry,
                                        simMcc = if (autoMcc.isNotBlank()) autoMcc else config.simMcc,
                                    ),
                                )
                            },
                            label = {
                                Text(
                                    stringResource(R.string.sales_code_label),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            },
                            placeholder = { Text("TGY / XAA") },
                            singleLine = true,
                            enabled = config.enabled,
                            shape = OneUiTokens.ControlShape,
                            modifier = Modifier.weight(1.3f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )

                        OutlinedTextField(
                            value = countryCodeInput,
                            onValueChange = { input ->
                                val filtered = input.take(2).uppercase()
                                countryCodeInput = filtered
                                persist(config.copy(countryCode = filtered))
                            },
                            label = {
                                Text(
                                    stringResource(R.string.country_code_label),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            },
                            placeholder = { Text("US") },
                            singleLine = true,
                            enabled = config.enabled,
                            shape = OneUiTokens.ControlShape,
                            modifier = Modifier.weight(1.1f),
                        )

                        OutlinedTextField(
                            value = simMccInput,
                            onValueChange = { input ->
                                val filtered = input.filter(Char::isDigit).take(3)
                                simMccInput = filtered
                                persist(config.copy(simMcc = filtered))
                            },
                            label = {
                                Text(
                                    "MCC",
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            },
                            placeholder = { Text("310") },
                            singleLine = true,
                            enabled = config.enabled,
                            shape = OneUiTokens.ControlShape,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.9f),
                        )
                    }
                }

                // 卡片 3: 硬件机型伪装
                OneUiIslandCard(
                    title = stringResource(R.string.device_spoof),
                    subtitle = stringResource(R.string.device_spoof_desc),
                ) {
                    OneUiItemRow(
                        title = stringResource(R.string.device_spoof_switch),
                        subtitle = if (config.spoofDevice) stringResource(R.string.device_spoof_configured)
                        else stringResource(R.string.device_spoof_keep_native),
                        icon = Icons.Default.Smartphone,
                        checked = config.spoofDevice,
                        onCheckedChange = {
                            persist(config.copy(spoofDevice = it))
                        },
                    )

                    if (config.spoofDevice) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        )

                        OneUiItemRow(
                            title = stringResource(R.string.target_model_label),
                            subtitle = config.deviceName.ifBlank { stringResource(R.string.click_to_choose_model) },
                            badgeText = if (config.deviceName.isNotBlank()) "SET" else null,
                            showChevron = true,
                            onClick = { showDeviceModelPicker = true },
                        )

                        OneUiItemRow(
                            title = stringResource(R.string.filter_apps_label),
                            subtitle = if (config.deviceTargetFilterEnabled) {
                                stringResource(R.string.apps_count_applied, config.deviceTargetPackages.size)
                            } else {
                                stringResource(R.string.all_scopes_applied)
                            },
                            checked = config.deviceTargetFilterEnabled,
                            onCheckedChange = {
                                persist(config.copy(deviceTargetFilterEnabled = it))
                            },
                        )

                        if (config.deviceTargetFilterEnabled) {
                            OutlinedButton(
                                onClick = { showDeviceTargetPicker = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = OneUiTokens.ControlShape,
                            ) {
                                Text(stringResource(R.string.select_target_apps_btn, config.deviceTargetPackages.size))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 预设切换二次确认弹窗
    showPresetConfirmDialog?.let { preset ->
        val presetName = stringResource(preset.nameRes)
        AlertDialog(
            onDismissRequest = { showPresetConfirmDialog = null },
            shape = OneUiTokens.IslandCardShape,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(preset.flag, fontSize = 22.sp)
                    Text(stringResource(R.string.preset_confirm_title, presetName), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneUiNoticeBanner(
                        text = stringResource(
                            R.string.preset_confirm_auto,
                            preset.salesCode,
                            preset.simMcc,
                            preset.countryCode,
                        ),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptics.confirm()
                        salesCodeInput = preset.salesCode
                        countryCodeInput = preset.countryCode
                        simMccInput = preset.simMcc
                        val updated = config.copy(
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
                        persist(
                            updated,
                            context.getString(R.string.preset_applied_toast, presetName),
                        )
                        val cleaned = StoreCleaner.clearStoreDataRoot()
                        if (cleaned) {
                            RegionApplyState.markApplied(context)
                            regionCleanupPending = false
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.clear_store_success)) }
                        } else {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.clear_store_fail)) }
                        }
                        showPresetConfirmDialog = null
                    },
                    shape = OneUiTokens.PillShape,
                ) {
                    Text(stringResource(R.string.action_apply_and_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPresetConfirmDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDeviceModelPicker) {
        DeviceModelPicker(
            initialSelection = config.deviceName,
            onDismiss = { showDeviceModelPicker = false },
            onSelect = { selected ->
                persist(config.copy(deviceName = selected), selected)
                showDeviceModelPicker = false
            },
        )
    }

    if (showDeviceTargetPicker) {
        DeviceTargetPicker(
            initialSelection = config.deviceTargetPackages,
            onDismiss = { showDeviceTargetPicker = false },
            onSave = { updated ->
                persist(config.copy(deviceTargetPackages = updated))
                showDeviceTargetPicker = false
            },
        )
    }
}

private fun hasRegionConfigChanged(previous: CscConfig, updated: CscConfig): Boolean =
    previous.enabled != updated.enabled ||
        previous.salesCode != updated.salesCode ||
        previous.countryCode != updated.countryCode ||
        previous.spoofSimInfo != updated.spoofSimInfo ||
        previous.simMcc != updated.simMcc ||
        previous.simNumeric != updated.simNumeric
