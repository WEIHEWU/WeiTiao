package com.weitiao.tuning.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
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
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiBadge
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.theme.OneUiTokens
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    var lastConfirmPreset by remember { mutableStateOf(CURATED_GLOBAL_PRESETS.first()) }
    showPresetConfirmDialog?.let { lastConfirmPreset = it }
    var regionCleanupPending by remember { mutableStateOf(RegionApplyState.isPending(context)) }

    val nativeInfo = remember { NativeDevice.detect(context) }

    fun notifySaved(message: String) {
        scope.launch {
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

    MiuixPageScaffold(
        title = stringResource(R.string.dashboard_spoof_title),
        subtitle = stringResource(R.string.spoof_subtitle),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(
                onClick = {
                    haptics.tap()
                    onOpenSettings()
                },
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings_title),
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
            // 卡片 1: 区域画像总览与商店生效状态
            OneUiIslandCard(contentPadding = 14.dp) {
                OneUiItemRow(
                    title = stringResource(R.string.module_switch),
                    subtitle = if (config.enabled) stringResource(R.string.module_enabled)
                    else stringResource(R.string.module_disabled),
                    icon = Icons.Default.Public,
                    iconTint = if (config.enabled) IosGlassTokens.IosGreen else IosGlassTokens.IosGray,
                    checked = config.enabled,
                    onCheckedChange = {
                        persist(config.copy(enabled = it))
                    },
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                )

                // 当前生效参数 vs 出厂基准
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.target_region),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (config.enabled && config.salesCode.isNotBlank()) config.salesCode else nativeInfo.salesCode,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (config.enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                            )
                            OneUiBadge(
                                text = if (config.enabled && config.countryCode.isNotBlank()) "ISO ${config.countryCode} · MCC ${config.simMcc}"
                                else "ISO ${nativeInfo.countryCode} · MCC ${nativeInfo.simMcc}",
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(R.string.factory_baseline_format, nativeInfo.salesCode, nativeInfo.simMcc),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    TextButton(
                        text = stringResource(R.string.action_restore_factory),
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
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
                    )
                }

                // 商店判区待清理提示与一键清理按钮
                if (regionCleanupPending) {
                    Spacer(modifier = Modifier.height(6.dp))
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
            }

            // 卡片 2: 选择目标区域（单一全局搜索入口 + 6 大精选主流预设 + 参数微调）
            OneUiIslandCard(
                title = stringResource(R.string.presets_title),
                subtitle = stringResource(R.string.presets_subtitle),
                contentPadding = 14.dp,
            ) {
                OneUiItemRow(
                    title = stringResource(R.string.search_all_csc_btn),
                    subtitle = stringResource(R.string.search_all_csc_desc),
                    icon = Icons.Default.Search,
                    iconTint = MiuixTheme.colorScheme.primary,
                    showChevron = true,
                    onClick = onOpenSearch,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 6 大主流精选横向快速选择胶囊
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CURATED_GLOBAL_PRESETS.forEach { preset ->
                        val isCurrent = config.enabled && config.salesCode.equals(preset.salesCode, ignoreCase = true)
                        val presetTag = stringResource(preset.tagRes)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isCurrent) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.secondaryContainer
                                )
                                .border(
                                    width = if (isCurrent) 1.5.dp else 0.dp,
                                    color = if (isCurrent) MiuixTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp),
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
                                            text = preset.salesCode,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCurrent) Color.White else MiuixTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                        Text(
                                            text = preset.countryCode,
                                            fontSize = 11.sp,
                                            color = if (isCurrent) Color.White.copy(alpha = 0.8f)
                                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    Text(
                                        text = presetTag,
                                        fontSize = 11.sp,
                                        color = if (isCurrent) Color.White.copy(alpha = 0.9f)
                                        else MiuixTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                // 联动微调文本框（Miuix TextField：CSC、国家码、SIM MCC）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
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
                        label = stringResource(R.string.sales_code_label),
                        singleLine = true,
                        enabled = config.enabled,
                        insideMargin = DpSize(12.dp, 12.dp),
                        modifier = Modifier.weight(1.3f),
                    )

                    TextField(
                        value = countryCodeInput,
                        onValueChange = { input ->
                            val filtered = input.take(2).uppercase()
                            countryCodeInput = filtered
                            persist(config.copy(countryCode = filtered))
                        },
                        label = stringResource(R.string.country_code_label),
                        singleLine = true,
                        enabled = config.enabled,
                        insideMargin = DpSize(12.dp, 12.dp),
                        modifier = Modifier.weight(1.1f),
                    )

                    TextField(
                        value = simMccInput,
                        onValueChange = { input ->
                            val filtered = input.filter(Char::isDigit).take(3)
                            simMccInput = filtered
                            persist(config.copy(simMcc = filtered))
                        },
                        label = "MCC",
                        singleLine = true,
                        enabled = config.enabled,
                        insideMargin = DpSize(12.dp, 12.dp),
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
                        TextButton(
                            text = stringResource(R.string.select_target_apps_btn, config.deviceTargetPackages.size),
                            onClick = { showDeviceTargetPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // 预设切换二次确认弹窗（Miuix WindowDialog）
    val preset = showPresetConfirmDialog ?: lastConfirmPreset
    val presetName = stringResource(preset.nameRes)
    WindowDialog(
        show = showPresetConfirmDialog != null,
        title = "${preset.flag} ${stringResource(R.string.preset_confirm_title, presetName)}",
        onDismissRequest = { showPresetConfirmDialog = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    DeviceModelPicker(
        show = showDeviceModelPicker,
        initialSelection = config.deviceName,
        onDismiss = { showDeviceModelPicker = false },
        onSelect = { selected ->
            persist(config.copy(deviceName = selected), selected)
            showDeviceModelPicker = false
        },
    )

    DeviceTargetPicker(
        show = showDeviceTargetPicker,
        initialSelection = config.deviceTargetPackages,
        onDismiss = { showDeviceTargetPicker = false },
        onSave = { updated ->
            persist(config.copy(deviceTargetPackages = updated))
            showDeviceTargetPicker = false
        },
    )
}

private fun hasRegionConfigChanged(previous: CscConfig, updated: CscConfig): Boolean =
    previous.enabled != updated.enabled ||
        previous.salesCode != updated.salesCode ||
        previous.countryCode != updated.countryCode ||
        previous.spoofSimInfo != updated.spoofSimInfo ||
        previous.simMcc != updated.simMcc ||
        previous.simNumeric != updated.simNumeric
