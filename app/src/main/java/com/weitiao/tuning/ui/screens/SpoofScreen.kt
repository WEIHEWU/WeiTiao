package com.weitiao.tuning.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class GlobalPreset(
    val flag: String,
    val shortLabelZh: String,
    val shortLabelEn: String,
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
    GlobalPreset("🇭🇰", "中国香港", "Hong Kong", R.string.preset_hk_name, "TGY", "454", "HK", R.string.preset_hk_tag, "45406", "SmarTone", "06"),
    GlobalPreset("🇺🇸", "美国公开", "US Open", R.string.preset_us_name, "XAA", "310", "US", R.string.preset_us_tag, "310260", "T-Mobile", "260"),
    GlobalPreset("🇪🇺", "欧洲通用", "Europe", R.string.preset_eu_name, "EUX", "262", "DE", R.string.preset_eu_tag, "26201", "T-Mobile", "01"),
    GlobalPreset("🇹🇼", "中国台湾", "Taiwan", R.string.preset_tw_name, "BRI", "466", "TW", R.string.preset_tw_tag, "46688", "Telecom", "88"),
    GlobalPreset("🇰🇷", "韩国官方", "Korea", R.string.preset_kr_name, "KOO", "450", "KR", R.string.preset_kr_tag, "45005", "SKT", "05"),
    GlobalPreset("🇨🇳", "中国大陆", "Mainland", R.string.preset_cn_name, "CHC", "460", "CN", R.string.preset_cn_tag, "46000", "CHINA MOBILE", "00"),
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
    val isZh = remember { Locale.getDefault().language.startsWith("zh") }

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
        onBack = onBack,
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

            // 第一层：系统真实底包 vs 当前伪装区域 2×1 双列对比卡
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MiuixMetricCard(
                    valueText = "${nativeInfo.salesCode} · ${nativeInfo.countryCode}",
                    labelText = if (isZh) "出厂底包 (MCC ${nativeInfo.simMcc})" else "Factory (${nativeInfo.simMcc})",
                    icon = Icons.Default.Public,
                    accentColor = IosGlassTokens.IosTeal,
                    modifier = Modifier.weight(1f),
                )

                val isSpoofingActive = config.enabled && config.salesCode.isNotBlank()
                MiuixMetricCard(
                    valueText = if (isSpoofingActive) {
                        "${config.salesCode} · ${config.countryCode}"
                    } else {
                        if (isZh) "原生未改" else "Native"
                    },
                    labelText = if (isSpoofingActive) {
                        if (isZh) "当前伪装 (MCC ${config.simMcc})" else "Spoofed (${config.simMcc})"
                    } else {
                        if (isZh) "点击下方地区启用" else "Select region below"
                    },
                    icon = Icons.Default.Public,
                    accentColor = if (isSpoofingActive) IosGlassTokens.IosBlue else IosGlassTokens.IosGreen,
                    modifier = Modifier.weight(1f),
                )
            }

            // 第二层：6 大精选地区 3×2 点亮式磁贴网格
            MiuixSectionTitle(
                title = stringResource(R.string.presets_title),
                trailingText = if (isZh) "点击一键切换并清理商店" else "Tap to switch region",
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CURATED_GLOBAL_PRESETS.chunked(3).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowPresets.forEach { preset ->
                            val isCurrent = config.enabled && config.salesCode.equals(preset.salesCode, ignoreCase = true)
                            MiuixQuickTile(
                                label = "${preset.flag} ${if (isZh) preset.shortLabelZh else preset.shortLabelEn}",
                                statusText = if (isCurrent) {
                                    "${preset.salesCode} · ${if (isZh) "已启用" else "Active"}"
                                } else {
                                    "${preset.salesCode} · ${preset.countryCode}"
                                },
                                icon = Icons.Default.Public,
                                active = isCurrent,
                                modifier = Modifier.weight(1f),
                                onClick = { showPresetConfirmDialog = preset },
                            )
                        }
                    }
                }
            }

            // 第三层：跨区总开关 + 全球 80 国检索库 + 手动参数微调
            OneUiIslandCard(
                title = if (isZh) "区域伪装控制" else "Region Spoof Control",
                subtitle = if (isZh) "仅对应用商店生效" else "Store apps only",
                contentPadding = 14.dp,
            ) {
                OneUiItemRow(
                    title = stringResource(R.string.module_switch),
                    subtitle = if (config.enabled) stringResource(R.string.module_enabled)
                    else stringResource(R.string.module_disabled),
                    icon = Icons.Default.Public,
                    iconTint = if (config.enabled) IosGlassTokens.IosBlue else IosGlassTokens.IosGray,
                    checked = config.enabled,
                    onCheckedChange = {
                        persist(config.copy(enabled = it))
                    },
                )

                OneUiItemRow(
                    title = stringResource(R.string.search_all_csc_btn),
                    subtitle = stringResource(R.string.search_all_csc_desc),
                    icon = Icons.Default.Search,
                    iconTint = IosGlassTokens.IosPurple,
                    showChevron = true,
                    onClick = onOpenSearch,
                )

                OneUiItemRow(
                    title = stringResource(R.string.action_restore_factory),
                    subtitle = stringResource(R.string.factory_baseline_format, nativeInfo.salesCode, nativeInfo.simMcc),
                    icon = Icons.Default.Restore,
                    iconTint = IosGlassTokens.IosAmber,
                    showChevron = true,
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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

            // 第四层：硬件机型伪装（双列选择卡 + 开关卡）
            MiuixSectionTitle(
                title = stringResource(R.string.device_spoof),
                trailingText = stringResource(R.string.device_spoof_desc),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MiuixMetricCard(
                    valueText = config.deviceName.ifBlank { Build.MODEL },
                    labelText = if (isZh) "点击选择伪装机型 (126款)" else "Tap to pick model (126)",
                    icon = Icons.Default.Smartphone,
                    accentColor = IosGlassTokens.IosAmber,
                    modifier = Modifier.weight(1f),
                    onClick = { showDeviceModelPicker = true },
                )

                MiuixMetricCard(
                    valueText = if (config.deviceTargetFilterEnabled) {
                        if (isZh) "${config.deviceTargetPackages.size} 个应用" else "${config.deviceTargetPackages.size} Apps"
                    } else {
                        if (isZh) "全局作用域" else "All Scopes"
                    },
                    labelText = if (isZh) "点击勾选生效应用" else "Tap to select target apps",
                    icon = Icons.Default.Apps,
                    accentColor = IosGlassTokens.IosPurple,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!config.deviceTargetFilterEnabled) {
                            persist(config.copy(deviceTargetFilterEnabled = true))
                        }
                        showDeviceTargetPicker = true
                    },
                )
            }

            OneUiIslandCard(contentPadding = 14.dp) {
                OneUiItemRow(
                    title = stringResource(R.string.device_spoof_switch),
                    subtitle = if (config.spoofDevice) stringResource(R.string.device_spoof_configured)
                    else stringResource(R.string.device_spoof_keep_native),
                    icon = Icons.Default.Smartphone,
                    iconTint = IosGlassTokens.IosAmber,
                    checked = config.spoofDevice,
                    onCheckedChange = {
                        persist(config.copy(spoofDevice = it))
                    },
                )

                OneUiItemRow(
                    title = stringResource(R.string.filter_apps_label),
                    subtitle = if (config.deviceTargetFilterEnabled) {
                        stringResource(R.string.apps_count_applied, config.deviceTargetPackages.size)
                    } else {
                        stringResource(R.string.all_scopes_applied)
                    },
                    icon = Icons.Default.Apps,
                    iconTint = IosGlassTokens.IosPurple,
                    checked = config.deviceTargetFilterEnabled,
                    onCheckedChange = {
                        persist(config.copy(deviceTargetFilterEnabled = it))
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // 预设切换二次确认弹窗
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
            persist(config.copy(spoofDevice = true, deviceName = selected), selected)
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
