package com.weitiao.tuning.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.DeviceModels
import com.weitiao.tuning.core.LsposedScopeHelper
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.OneUiBadge
import com.weitiao.tuning.ui.components.OneUiCollapsibleCard
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.WindowBottomSheet
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

// ---------------------------------------------------------------------------
// 1. 商店跨区详情页
// ---------------------------------------------------------------------------

@Composable
internal fun StoreRegionDetailContent(
    csc: CscConfig,
    isZh: Boolean,
    hasRoot: Boolean,
    regionCleanupPending: Boolean,
    onRegisterBackInterceptor: ((() -> Boolean)?) -> Unit,
    onExitAfterSaveOrDiscard: () -> Unit,
    onUpdateCsc: (String?, (CscConfig) -> CscConfig) -> Unit,
    onOpenRegionSearch: () -> Unit,
    onClearStoreNow: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    var customCodeInput by remember(csc.salesCode) { mutableStateOf(csc.salesCode) }
    var customCountryInput by remember(csc.countryCode) { mutableStateOf(csc.countryCode) }
    var customMccInput by remember(csc.simMcc) { mutableStateOf(csc.simMcc) }
    var customMncInput by remember(csc.simMnc) { mutableStateOf(csc.simMnc) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val storeActive = csc.enabled && csc.salesCode.isNotBlank()

    val hasUnsavedCustomRegion = storeActive && (
        customCodeInput.trim().uppercase() != csc.salesCode.uppercase() ||
            customCountryInput.trim().uppercase() != csc.countryCode.uppercase() ||
            customMccInput.trim() != csc.simMcc ||
            customMncInput.trim() != csc.simMnc
        )

    DisposableEffect(hasUnsavedCustomRegion) {
        onRegisterBackInterceptor {
            if (hasUnsavedCustomRegion) {
                showUnsavedDialog = true
                true
            } else {
                false
            }
        }
        onDispose { onRegisterBackInterceptor(null) }
    }

    BackHandler(enabled = hasUnsavedCustomRegion) {
        showUnsavedDialog = true
    }

    fun saveCustomRegionInputs(): Boolean {
        val code = customCodeInput.trim().uppercase()
        if (code.length != 3) return false
        val country = customCountryInput.trim().uppercase().ifEmpty { csc.countryCode }
        val mcc = customMccInput.trim().ifEmpty { csc.simMcc }
        val mnc = customMncInput.trim().ifEmpty { csc.simMnc }
        onUpdateCsc(context.getString(R.string.saved_toast)) {
            it.copy(
                enabled = true,
                salesCode = code,
                countryCode = country,
                simCountryIso = country.lowercase(),
                simMcc = mcc,
                simMnc = mnc,
                simNumeric = if (mcc.isNotBlank() && mnc.isNotBlank()) "$mcc$mnc" else it.simNumeric,
                spoofSimInfo = true,
            )
        }
        return true
    }

    OneUiIslandCard {
        OneUiItemRow(
            title = if (isZh) "启用商店跨区" else "Enable Store Region Unlock",
            subtitle = if (storeActive) {
                if (isZh) "当前地区：${formatRegionDisplayName(csc, true)}"
                else "Active region: ${formatRegionDisplayName(csc, false)}"
            } else {
                if (isZh) "已关闭，三星应用商店与主题商店使用本机默认地区"
                else "Disabled. Galaxy Store uses your device's default region"
            },
            icon = Icons.Default.Public,
            iconTint = IosGlassTokens.IosBlue,
            checked = storeActive,
            onCheckedChange = { targetEnabled ->
                if (!targetEnabled) {
                    onUpdateCsc(if (isZh) "已关闭商店跨区" else "Store region unlock disabled") {
                        it.copy(enabled = false)
                    }
                } else {
                    val defaultPreset = REGION_QUICK_PRESETS.first()
                    val presetName = context.getString(defaultPreset.nameRes)
                    onUpdateCsc(
                        if (csc.salesCode.isBlank()) {
                            context.getString(R.string.preset_applied_toast, presetName)
                        } else {
                            if (isZh) "已开启商店跨区" else "Store region unlock enabled"
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
                                marketUnlockRaw = "${defaultPreset.numeric},${defaultPreset.operator},${defaultPreset.countryCode.lowercase()},${presetName}",
                                spoofSimInfo = true,
                            )
                        } else {
                            cur.copy(enabled = true)
                        }
                    }
                }
            },
        )
    }

    // 常用地区快速选择始终展示：即使主开关关闭，用户点选任意地区即可一步开启并切换
    OneUiIslandCard(
        title = if (isZh) "常用地区快速选择" else "Popular Regions",
        subtitle = if (storeActive) {
            if (isZh) "点选即可自动匹配对应地区与运营商参数" else "Tap a region to auto-configure CSC and carrier parameters"
        } else {
            if (isZh) "点击任意地区即可直接开启跨区并套用对应参数" else "Tap any region below to enable and apply in one step"
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            REGION_QUICK_PRESETS.chunked(2).forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowPresets.forEach { preset ->
                        val isSelected = storeActive && csc.salesCode.equals(preset.salesCode, ignoreCase = true)
                        FeatureSelectTile(
                            title = if (isZh) "${preset.labelZh} (${preset.salesCode})" else "${preset.labelEn} (${preset.salesCode})",
                            subtitle = if (isZh) preset.tagZh else preset.tagEn,
                            selected = isSelected,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptics.tap()
                                val presetName = context.getString(preset.nameRes)
                                onUpdateCsc(context.getString(R.string.preset_applied_toast, presetName)) {
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
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        OneUiItemRow(
            title = if (isZh) "搜索全部国家与地区代码" else "Search All Country & CSC Codes",
            subtitle = if (isZh) "按国家/地区名称或 3 位 CSC 代码搜索" else "Search by country name or 3-letter CSC code",
            icon = Icons.Default.Search,
            iconTint = IosGlassTokens.IosBlue,
            trailingValue = if (storeActive) csc.salesCode else if (isZh) "未开启" else "Off",
            showChevron = true,
            onClick = onOpenRegionSearch,
        )
    }

    if (!storeActive) {
        return
    }

    OneUiIslandCard(
        title = if (isZh) "生效操作" else "Apply Changes",
    ) {
        Text(
            text = if (regionCleanupPending) {
                if (isZh) "地区配置已更新，点击下方按钮清理三星商店缓存并重启商店即可立即生效。"
                else "Region updated. Tap below to clear Galaxy Store cache and reload the new region."
            } else {
                if (isZh) "如应用商店仍显示旧地区内容，可点击下方按钮刷新商店缓存。"
                else "If Galaxy Store still shows old region content, tap below to refresh its cache."
            },
            fontSize = 11.5.sp,
            color = if (regionCleanupPending) IosGlassTokens.IosAmber else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextButton(
            text = if (isZh) "应用并刷新三星商店" else "Apply & Refresh Galaxy Store",
            onClick = onClearStoreNow,
            enabled = hasRoot,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!hasRoot) {
            Text(
                text = if (isZh) "提示：自动清理商店缓存需要 Root 权限；无 Root 时可前往「系统设置 → 应用 → 三星应用商店 → 存储」手动清除数据。"
                else "Tip: Auto cache cleanup requires Root. Without Root, clear Galaxy Store storage manually in Android Settings.",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    OneUiCollapsibleCard(
        title = if (isZh) "高级设置" else "Advanced Settings",
        subtitle = if (isZh) "自定义 CSC 代码、MCC/MNC 与底层拦截细项" else "Custom CSC code, MCC/MNC & granular hook switches",
        initiallyExpanded = false,
    ) {
        OneUiItemRow(
            title = if (isZh) "拦截销售代码 (ro.csc.sales_code)" else "Spoof Sales Code (ro.csc.sales_code)",
            checked = csc.spoofSalesCode,
            onCheckedChange = { v -> onUpdateCsc(null) { it.copy(spoofSalesCode = v) } },
        )
        OneUiItemRow(
            title = if (isZh) "拦截国家代码 (ro.csc.country_code)" else "Spoof Country Code (ro.csc.country_code)",
            checked = csc.spoofCountryCode,
            onCheckedChange = { v -> onUpdateCsc(null) { it.copy(spoofCountryCode = v) } },
        )
        OneUiItemRow(
            title = if (isZh) "拦截基带区域 (ril.sales_code / OMC)" else "Spoof RIL & OMC Codes",
            checked = csc.spoofRilSalesCode && csc.spoofOmcCodes,
            onCheckedChange = { v -> onUpdateCsc(null) { it.copy(spoofRilSalesCode = v, spoofOmcCodes = v) } },
        )
        OneUiItemRow(
            title = if (isZh) "拦截三星特性配置 (SemCscFeature)" else "Spoof SemCscFeature",
            checked = csc.spoofSemCscFeature,
            onCheckedChange = { v -> onUpdateCsc(null) { it.copy(spoofSemCscFeature = v) } },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = customCodeInput,
                onValueChange = { customCodeInput = it.uppercase().take(3) },
                label = if (isZh) "CSC 代码 (如 TGY)" else "CSC (e.g. TGY)",
                singleLine = true,
                insideMargin = DpSize(12.dp, 10.dp),
                modifier = Modifier.weight(1f),
            )
            TextField(
                value = customCountryInput,
                onValueChange = { customCountryInput = it.uppercase().take(2) },
                label = if (isZh) "国家码 (如 HK)" else "Country (e.g. HK)",
                singleLine = true,
                insideMargin = DpSize(12.dp, 10.dp),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = customMccInput,
                onValueChange = { customMccInput = it.filter(Char::isDigit).take(3) },
                label = "SIM MCC",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                insideMargin = DpSize(12.dp, 10.dp),
                modifier = Modifier.weight(1f),
            )
            TextField(
                value = customMncInput,
                onValueChange = { customMncInput = it.filter(Char::isDigit).take(3) },
                label = "SIM MNC",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                insideMargin = DpSize(12.dp, 10.dp),
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            text = if (hasUnsavedCustomRegion) {
                if (isZh) "保存自定义区域参数（有未保存修改）" else "Save Custom Region (Unsaved Changes)"
            } else {
                if (isZh) "保存自定义区域参数" else "Save Custom Region Parameters"
            },
            enabled = customCodeInput.trim().length == 3,
            colors = if (hasUnsavedCustomRegion) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
            onClick = { saveCustomRegionInputs() },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    WindowDialog(
        show = showUnsavedDialog,
        title = if (isZh) "未保存的自定义区域参数" else "Unsaved Custom Region",
        onDismissRequest = { showUnsavedDialog = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (isZh) "你在高级设置中修改了自定义区域代码但尚未点击保存，是否保存修改？"
                else "You edited custom region parameters without saving. Save before leaving?",
                fontSize = 12.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = if (isZh) "放弃修改" else "Discard",
                    onClick = {
                        showUnsavedDialog = false
                        onExitAfterSaveOrDiscard()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (isZh) "保存并返回" else "Save & Exit",
                    enabled = customCodeInput.trim().length == 3,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        if (saveCustomRegionInputs()) {
                            showUnsavedDialog = false
                            onExitAfterSaveOrDiscard()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. 机型伪装详情页
// ---------------------------------------------------------------------------

@Composable
internal fun DeviceSpoofDetailContent(
    csc: CscConfig,
    isZh: Boolean,
    hasRoot: Boolean,
    onUpdateCsc: (String?, (CscConfig) -> CscConfig) -> Unit,
    onOpenPerAppPage: () -> Unit,
    onRequestRestartTargets: (Set<String>) -> Unit,
    onShowSnackbar: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showModelPicker by remember { mutableStateOf(false) }
    var showTargetScopePicker by remember { mutableStateOf(false) }

    val deviceActive = csc.spoofDevice && (csc.deviceName.isNotBlank() || csc.perAppDeviceModels.isNotEmpty())
    val currentProfile = remember(csc.deviceName) { DeviceModels.findByName(csc.deviceName) }
    val currentModelSummary = when {
        currentProfile != null -> "${currentProfile.name} (${currentProfile.model})"
        csc.deviceName.isNotBlank() -> csc.deviceName
        csc.perAppDeviceModels.isNotEmpty() -> if (isZh) "分应用独立机型生效中" else "Per-App models active"
        else -> if (isZh) "未选择默认机型" else "No default model selected"
    }

    OneUiIslandCard {
        OneUiItemRow(
            title = if (isZh) "启用机型伪装" else "Enable Device Model Spoofing",
            subtitle = if (deviceActive) {
                if (isZh) "当前默认机型：$currentModelSummary" else "Default model: $currentModelSummary"
            } else {
                if (isZh) "已关闭，所有应用识别本机真实型号 (${Build.MODEL})"
                else "Disabled. Apps see your real model (${Build.MODEL})"
            },
            icon = Icons.Default.Smartphone,
            iconTint = IosGlassTokens.IosPurple,
            checked = deviceActive,
            onCheckedChange = { targetEnabled ->
                if (!targetEnabled) {
                    onUpdateCsc(if (isZh) "已关闭机型伪装" else "Device spoofing disabled") {
                        it.copy(spoofDevice = false)
                    }
                } else {
                    val fallbackModel = RECOMMENDED_DEVICE_MODELS.first()
                    val useFallback = csc.deviceName.isBlank() && csc.perAppDeviceModels.isEmpty()
                    onUpdateCsc(
                        if (useFallback) {
                            if (isZh) "已开启机型伪装（默认设为 $fallbackModel）"
                            else "Device spoofing enabled ($fallbackModel)"
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
        )
    }

    // 热门机型卡片始终可见：支持一键点选直接开启并设为默认机型
    OneUiIslandCard(
        title = if (isZh) "热门机型一键选择" else "Popular Device Models",
        subtitle = if (deviceActive) {
            if (isZh) "点击即可设为全局默认伪装机型" else "Tap to set as the default spoofed model"
        } else {
            if (isZh) "点击任意机型即可直接开启伪装并设为默认机型" else "Tap any model below to enable and apply in one step"
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RECOMMENDED_DEVICE_MODELS.chunked(2).forEach { rowModels ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowModels.forEach { modelName ->
                        val profile = remember(modelName) { DeviceModels.findByName(modelName) }
                        val selected = deviceActive && csc.deviceName == modelName
                        FeatureSelectTile(
                            title = modelName,
                            subtitle = profile?.model ?: "",
                            selected = selected,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onUpdateCsc(
                                    if (isZh) "默认机型已设为 $modelName" else "Default model set to $modelName",
                                ) {
                                    it.copy(
                                        spoofDevice = true,
                                        deviceName = modelName,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        OneUiItemRow(
            title = if (isZh) "浏览完整机型库" else "Browse Full Device Library",
            subtitle = if (isZh) "包含苹果、三星、小米、华为、ROG、索尼等 120+ 款旗舰机型" else "120+ flagship models across Apple, Samsung, Xiaomi, Huawei, ROG & Sony",
            icon = Icons.Default.Search,
            iconTint = IosGlassTokens.IosPurple,
            trailingValue = if (deviceActive && csc.deviceName.isNotBlank()) csc.deviceName else if (isZh) "请选择" else "Select",
            showChevron = true,
            onClick = { showModelPicker = true },
        )
    }

    if (!deviceActive) {
        return
    }

    OneUiIslandCard(
        title = if (isZh) "生效范围与分应用设置" else "Target Scope & Per-App Models",
    ) {
        val filterActive = csc.deviceTargetFilterEnabled && csc.deviceTargetPackages.isNotEmpty()
        val targetCount = csc.deviceTargetPackages.size
        OneUiItemRow(
            title = if (isZh) "全局机型生效范围" else "Global Model Target Scope",
            subtitle = if (filterActive) {
                if (isZh) "仅对已勾选的 $targetCount 个应用生效（点击可增删或恢复全部作用域）"
                else "Active for $targetCount selected app(s)"
            } else {
                if (isZh) "对 LSPosed 中勾选的所有目标应用生效（自动排除微信与三星商店以防冲突）"
                else "Active for all apps in LSPosed scope"
            },
            icon = Icons.Default.Apps,
            iconTint = IosGlassTokens.IosBlue,
            trailingValue = if (filterActive) {
                if (isZh) "指定 $targetCount 个" else "$targetCount apps"
            } else {
                if (isZh) "全部作用域" else "All Scope"
            },
            showChevron = true,
            onClick = { showTargetScopePicker = true },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

        val perAppCount = csc.perAppDeviceModels.size
        OneUiItemRow(
            title = if (isZh) "分应用独立机型" else "Per-App Individual Models",
            subtitle = if (perAppCount > 0) {
                if (isZh) "已为 $perAppCount 个应用单独指定机型（优先于默认机型）" else "$perAppCount app(s) use individual models"
            } else {
                if (isZh) "给不同游戏或社交软件分别设置不同机型" else "Assign different models to specific apps"
            },
            icon = Icons.Default.Tune,
            iconTint = IosGlassTokens.IosTeal,
            trailingValue = if (perAppCount > 0) {
                if (isZh) "已设 $perAppCount 个" else "$perAppCount set"
            } else {
                if (isZh) "跟随默认" else "Default"
            },
            showChevron = true,
            onClick = onOpenPerAppPage,
        )
    }

    OneUiIslandCard(
        title = if (isZh) "生效操作" else "Apply & Restart",
    ) {
        Text(
            text = if (hasRoot) {
                if (isZh) "切换机型或修改目标应用后，点击下方按钮可自动同步作用域并重启目标应用。"
                else "Tap below to sync LSPosed scope and restart target apps so the new model takes effect."
            } else {
                if (isZh) "机型配置已实时保存；当前无 Root 权限，请确认已在 LSPosed 中勾选目标应用并手动重启该应用。"
                else "Model saved. Without Root, ensure target apps are checked in LSPosed and restart them manually."
            },
            fontSize = 11.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextButton(
            text = if (hasRoot) {
                if (isZh) "同步作用域并重启目标应用" else "Sync Scope & Restart Target Apps"
            } else {
                if (isZh) "请求同步 LSPosed 作用域" else "Request LSPosed Scope Sync"
            },
            onClick = { onRequestRestartTargets(csc.deviceTargetPackages) },
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (currentProfile != null) {
        OneUiCollapsibleCard(
            title = if (isZh) "高级信息：当前机型底层参数" else "Advanced: Current Model Parameters",
            subtitle = if (isZh) "查看只读 Build 参数映射详情" else "View read-only Build & property mapping details",
            initiallyExpanded = false,
        ) {
            Text(
                text = "NAME: ${currentProfile.name}\n" +
                    "MODEL: ${currentProfile.model}\n" +
                    "MANUFACTURER: ${currentProfile.manufacturer}\n" +
                    "BRAND: ${currentProfile.brand}\n" +
                    "DEVICE: ${currentProfile.device}\n" +
                    "PRODUCT: ${currentProfile.productName}\n" +
                    "SOC: ${currentProfile.socModel}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    DeviceModelPicker(
        show = showModelPicker,
        initialSelection = csc.deviceName,
        onDismiss = { showModelPicker = false },
        onSelect = { selected ->
            onUpdateCsc(selected) {
                it.copy(
                    spoofDevice = true,
                    deviceName = selected,
                )
            }
            showModelPicker = false
        },
    )

    TargetScopeOnlyPickerSheet(
        show = showTargetScopePicker,
        initialSelection = if (csc.deviceTargetFilterEnabled) csc.deviceTargetPackages else emptySet(),
        isZh = isZh,
        onDismiss = { showTargetScopePicker = false },
        onSave = { updatedTargets ->
            val prevTargets = csc.deviceTargetPackages
            onUpdateCsc(context.getString(R.string.saved_toast)) {
                it.copy(
                    spoofDevice = true,
                    deviceTargetFilterEnabled = updatedTargets.isNotEmpty(),
                    deviceTargetPackages = updatedTargets,
                )
            }
            showTargetScopePicker = false
            if (updatedTargets.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val allScopeTargets = updatedTargets + csc.perAppDeviceModels.keys
                    val synced = LsposedScopeHelper.syncScopesWithRoot(context, allScopeTargets)
                    if (!synced) {
                        val newlyAdded = updatedTargets - prevTargets
                        if (newlyAdded.isNotEmpty()) {
                            LsposedScopeHelper.requestScopesViaService(newlyAdded)
                        }
                    }
                    if (synced) {
                        withContext(Dispatchers.Main) {
                            onShowSnackbar(if (isZh) "已通过 Root 自动同步 LSPosed 作用域" else "Synced LSPosed scope via Root")
                        }
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 3. 分应用机型详情页（独立单应用选择 -> 选机型 2 步直达，不混杂全局作用域勾选）
// ---------------------------------------------------------------------------

@Composable
internal fun PerAppModelDetailContent(
    csc: CscConfig,
    isZh: Boolean,
    hasRoot: Boolean,
    onUpdateCsc: (String?, (CscConfig) -> CscConfig) -> Unit,
    onRequestRestartSingleApp: (pkg: String, appLabel: String) -> Unit,
    onShowSnackbar: (String) -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()
    var showAddAppSheet by remember { mutableStateOf(false) }
    var editingAppPkg by remember { mutableStateOf<String?>(null) }

    val appLabelMap = remember(csc.perAppDeviceModels.keys, editingAppPkg) {
        (csc.perAppDeviceModels.keys + listOfNotNull(editingAppPkg)).associateWith { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(pkg)
        }
    }

    fun syncAppScopeInBackground(pkg: String) {
        scope.launch(Dispatchers.IO) {
            val allTargets = csc.deviceTargetPackages + csc.perAppDeviceModels.keys + pkg
            val synced = LsposedScopeHelper.syncScopesWithRoot(context, allTargets)
            if (!synced) {
                LsposedScopeHelper.requestScopesViaService(setOf(pkg)) { approved ->
                    if (!approved) {
                        scope.launch {
                            onShowSnackbar(
                                if (isZh) "请在 LSPosed 中勾选该应用作用域以使伪装生效"
                                else "Please enable this app in LSPosed scope"
                            )
                        }
                    }
                }
            }
        }
    }

    OneUiIslandCard {
        OneUiItemRow(
            title = if (isZh) "未单独设置的应用" else "Unconfigured Apps",
            subtitle = if (csc.spoofDevice && csc.deviceName.isNotBlank()) {
                if (isZh) "自动跟随全局默认机型：${csc.deviceName}"
                else "Follow global default model: ${csc.deviceName}"
            } else {
                if (isZh) "保持本机真实型号 (${Build.MODEL})"
                else "Keep real device model (${Build.MODEL})"
            },
            icon = Icons.Default.Smartphone,
            iconTint = IosGlassTokens.IosPurple,
            trailingValue = if (csc.spoofDevice && csc.deviceName.isNotBlank()) {
                csc.deviceName
            } else {
                if (isZh) "本机型号" else "Native"
            },
        )
        TextButton(
            text = if (isZh) "添加应用专属机型" else "Add Per-App Model",
            onClick = { showAddAppSheet = true },
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    OneUiIslandCard(
        title = if (isZh) "已单独指定机型的应用 (${csc.perAppDeviceModels.size})"
        else "Apps with Individual Models (${csc.perAppDeviceModels.size})",
    ) {
        if (csc.perAppDeviceModels.isEmpty()) {
            Text(
                text = if (isZh) "暂未给任何应用单独设置机型。\n点击上方「添加应用专属机型」→ 选择应用 → 选择机型，仅需两步即可为指定游戏或社交软件设置独立型号。"
                else "No per-app models configured yet.\nTap 'Add Per-App Model' above -> pick an app -> pick a model in just 2 steps.",
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            val entries = csc.perAppDeviceModels.entries.toList()
            entries.forEachIndexed { index, (pkg, modelName) ->
                val appLabel = appLabelMap[pkg] ?: pkg
                val profile = remember(modelName) { DeviceModels.findByName(modelName) }
                val modelDisplay = if (profile != null) "${profile.name} (${profile.model})" else modelName

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = appLabel,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pkg,
                            fontSize = 10.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isZh) "专属机型：$modelDisplay" else "Model: $modelDisplay",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = IosGlassTokens.IosBlue,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FeatureActionPill(
                            text = if (isZh) "更换机型" else "Change Model",
                            color = IosGlassTokens.IosBlue,
                            onClick = { editingAppPkg = pkg },
                        )
                        if (hasRoot) {
                            FeatureActionPill(
                                text = if (isZh) "重启该应用" else "Restart App",
                                color = IosGlassTokens.IosTeal,
                                onClick = { onRequestRestartSingleApp(pkg, appLabel) },
                            )
                        }
                        FeatureActionPill(
                            text = if (isZh) "恢复默认机型" else "Reset to Default",
                            color = IosGlassTokens.IosRed,
                            onClick = {
                                onUpdateCsc(
                                    if (isZh) "已移除 $appLabel 的专属机型，恢复跟随默认机型"
                                    else "Reset $appLabel to default model",
                                ) { cur ->
                                    cur.copy(perAppDeviceModels = cur.perAppDeviceModels - pkg)
                                }
                            },
                        )
                    }
                }
                if (index < entries.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }

    SingleAppSelectorSheet(
        show = showAddAppSheet,
        perAppModels = csc.perAppDeviceModels,
        globalDefaultModel = csc.deviceName,
        isZh = isZh,
        onDismiss = { showAddAppSheet = false },
        onSelectApp = { pkg ->
            showAddAppSheet = false
            editingAppPkg = pkg
        },
    )

    val currentEditingPkg = editingAppPkg
    DeviceModelPicker(
        show = currentEditingPkg != null,
        initialSelection = currentEditingPkg?.let { csc.perAppDeviceModels[it] } ?: csc.deviceName,
        onDismiss = { editingAppPkg = null },
        onSelect = { selectedModel ->
            val pkg = currentEditingPkg ?: return@DeviceModelPicker
            val label = appLabelMap[pkg] ?: pkg
            onUpdateCsc(
                if (isZh) "已将「$label」设为 $selectedModel" else "Set $label to $selectedModel",
            ) { cur ->
                // 若此前开启了白名单过滤，顺带将该应用加入白名单；若未开启白名单过滤，保持全局作用域不变
                val nextTargets = if (cur.deviceTargetFilterEnabled) cur.deviceTargetPackages + pkg else cur.deviceTargetPackages
                cur.copy(
                    spoofDevice = true,
                    deviceTargetPackages = nextTargets,
                    perAppDeviceModels = cur.perAppDeviceModels + (pkg to selectedModel),
                )
            }
            syncAppScopeInBackground(pkg)
            editingAppPkg = null
        },
    )
}

private data class SimpleAppEntry(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

/**
 * 分应用机型专用：点选应用即进入机型选择，无需勾选复选框和点击多层小徽章
 */
@Composable
private fun SingleAppSelectorSheet(
    show: Boolean,
    perAppModels: Map<String, String>,
    globalDefaultModel: String,
    isZh: Boolean,
    onDismiss: () -> Unit,
    onSelectApp: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                SimpleAppEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = runCatching { it.loadIcon(pm) }.getOrNull(),
                )
            }
            .filterNot { it.packageName == context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    val filteredApps = remember(query, apps, perAppModels) {
        val base = if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        base.sortedWith(
            compareByDescending<SimpleAppEntry> { it.packageName in perAppModels }
                .thenBy { it.label.lowercase() },
        )
    }

    WindowBottomSheet(
        show = show,
        title = if (isZh) "选择要单独设置机型的应用" else "Select App for Custom Model",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.search_app_hint),
                useLabelAsPlaceholder = true,
                singleLine = true,
                insideMargin = DpSize(12.dp, 10.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(16.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = if (isZh) "点击任意应用即可直接为其选择专属机型" else "Tap any app below to choose its device model",
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .overScrollVertical(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val currentOverride = perAppModels[app.packageName]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectApp(app.packageName) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (app.icon != null) {
                            Image(
                                bitmap = app.icon.toBitmap(84, 84).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MiuixTheme.colorScheme.secondaryContainer),
                            )
                        }

                        Column(Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = app.packageName,
                                fontSize = 10.5.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }

                        OneUiBadge(
                            text = currentOverride ?: globalDefaultModel.ifBlank {
                                if (isZh) "跟随默认" else "Default"
                            },
                            color = if (currentOverride != null) IosGlassTokens.IosBlue else IosGlassTokens.IosGray,
                        )
                    }
                }
            }

            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 全局机型伪装的目标应用范围选择器（仅负责白名单勾选或恢复全部作用域，不混杂分应用机型选择）
 */
@Composable
private fun TargetScopeOnlyPickerSheet(
    show: Boolean,
    initialSelection: Set<String>,
    isZh: Boolean,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                SimpleAppEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = runCatching { it.loadIcon(pm) }.getOrNull(),
                )
            }
            .filterNot { it.packageName == context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    var selected by remember(initialSelection, show) { mutableStateOf(initialSelection.toSet()) }

    val filteredApps = remember(query, apps, selected) {
        val base = if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        base.sortedWith(
            compareByDescending<SimpleAppEntry> { it.packageName in selected }
                .thenBy { it.label.lowercase() },
        )
    }

    WindowBottomSheet(
        show = show,
        title = if (isZh) "设置全局机型生效应用范围" else "Select Target Apps for Global Model",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.search_app_hint),
                useLabelAsPlaceholder = true,
                singleLine = true,
                insideMargin = DpSize(12.dp, 10.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(16.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selected.isEmpty()) {
                        if (isZh) "当前：未限制（对 LSPosed 全部作用域生效）"
                        else "Current: All apps in LSPosed scope"
                    } else {
                        if (isZh) "当前：仅对已勾选的 ${selected.size} 个应用生效"
                        else "Current: Only ${selected.size} selected app(s)"
                    },
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                if (selected.isNotEmpty()) {
                    FeatureActionPill(
                        text = if (isZh) "恢复全部作用域" else "Reset All",
                        color = IosGlassTokens.IosBlue,
                        onClick = { selected = emptySet() },
                    )
                }
            }

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
                    text = if (selected.isEmpty()) {
                        if (isZh) "保存（全部作用域）" else "Save (All Scope)"
                    } else {
                        stringResource(R.string.save_app_selection, selected.size)
                    },
                    onClick = { onSave(selected) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
