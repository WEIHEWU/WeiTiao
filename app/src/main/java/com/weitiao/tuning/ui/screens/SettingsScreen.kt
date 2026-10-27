package com.weitiao.tuning.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stars
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.weitiao.tuning.App
import com.weitiao.tuning.BuildConfig
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.CscSpoof
import com.weitiao.tuning.core.HookLiveness
import com.weitiao.tuning.core.LanguageManager
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class CscFeaturePresetItem(
    val key: String,
    val activeValue: String,
    val titleRes: Int,
    val descRes: Int,
)

private val SAMSUNG_CSC_FEATURE_PRESETS = listOf(
    CscFeaturePresetItem(
        key = "CscFeature_VoiceCall_ConfigRecording",
        activeValue = "RecordingAllowed",
        titleRes = R.string.csc_preset_call_recording,
        descRes = R.string.csc_preset_call_recording_desc,
    ),
    CscFeaturePresetItem(
        key = "CscFeature_Camera_ShutterSoundMenu",
        activeValue = "TRUE",
        titleRes = R.string.csc_preset_shutter_sound,
        descRes = R.string.csc_preset_shutter_sound_desc,
    ),
    CscFeaturePresetItem(
        key = "CscFeature_Setting_SupportRealTimeNetworkSpeed",
        activeValue = "TRUE",
        titleRes = R.string.csc_preset_network_speed,
        descRes = R.string.csc_preset_network_speed_desc,
    ),
    CscFeaturePresetItem(
        key = "CscFeature_SystemUI_SupportDataUsageViewOnQuickPanel",
        activeValue = "TRUE",
        titleRes = R.string.csc_preset_data_usage,
        descRes = R.string.csc_preset_data_usage_desc,
    ),
)

@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isZh = remember { Locale.getDefault().language.startsWith("zh") }

    var config by remember { mutableStateOf(CscPrefs.load(context)) }
    var customPropsText by rememberSaveable { mutableStateOf(config.customProps.toJson()) }
    var customFeaturesText by rememberSaveable { mutableStateOf(config.customCscFeatures.toJson()) }
    var propKeyInput by rememberSaveable { mutableStateOf("") }
    var propValInput by rememberSaveable { mutableStateOf("") }
    var featKeyInput by rememberSaveable { mutableStateOf("") }
    var featValInput by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val xposedConnected = App.getXposedService() != null || App.getRemotePrefs() != null
    var probeTick by remember { mutableIntStateOf(0) }
    var hasRoot by remember { mutableStateOf(false) }
    var hookedPkgs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var nativeSalesCode by remember { mutableStateOf("") }

    LaunchedEffect(probeTick) {
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

    fun persistSwitch(updated: CscConfig) {
        val changed = updated != config
        config = updated
        CscPrefs.save(context, updated)
        if (changed) RegionApplyState.markPending(context)
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.settings_auto_saved))
        }
    }

    MiuixPageScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 第一层：运行环境与界面语言（全宽列表项，文案完整显示不截断）
            OneUiIslandCard(
                title = if (isZh) "运行环境与语言" else "Environment & Language",
            ) {
                OneUiItemRow(
                    title = if (isZh) "LSPosed 框架状态" else "LSPosed Framework",
                    subtitle = "Modern Xposed API 102 · v${BuildConfig.VERSION_NAME}",
                    icon = Icons.Default.Security,
                    iconTint = if (xposedConnected) IosGlassTokens.IosGreen else IosGlassTokens.IosAmber,
                    badgeText = if (xposedConnected) {
                        if (isZh) "已就绪" else "Ready"
                    } else {
                        if (isZh) "未连接" else "Disconnected"
                    },
                    badgeColor = if (xposedConnected) IosGlassTokens.IosGreen else IosGlassTokens.IosAmber,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = if (isZh) "界面语言 / Language" else "UI Language",
                    subtitle = if (isZh) "点击立即切换中英文界面" else "Tap to switch UI language",
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

            // 第一点五层：环境与伪装自检探针（实时验证伪装结果与系统探针）
            val effectiveModel = CscSpoof.matchDeviceProp("ro.product.model", config) ?: Build.MODEL
            val effectiveCsc = CscSpoof.matchProp("ro.csc.sales_code", config)
                ?: nativeSalesCode.ifBlank { if (isZh) "原生默认" else "Native" }
            OneUiIslandCard(
                title = stringResource(R.string.selftest_card_title),
                subtitle = stringResource(R.string.selftest_card_subtitle),
                icon = Icons.Default.BugReport,
            ) {
                OneUiItemRow(
                    title = stringResource(R.string.selftest_build_model),
                    subtitle = if (config.spoofDevice && config.deviceName.isNotBlank()) {
                        if (isZh) "物理机型: ${Build.MODEL} → 伪装生效: $effectiveModel"
                        else "Physical: ${Build.MODEL} → Spoofed: $effectiveModel"
                    } else {
                        if (isZh) "当前为物理真实机型" else "Physical native model"
                    },
                    trailingValue = effectiveModel,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.selftest_csc_code),
                    subtitle = if (config.enabled && config.salesCode.isNotBlank()) {
                        if (isZh) "原厂 CSC: ${nativeSalesCode.ifBlank { "-" }} → 伪装目标: ${config.salesCode}"
                        else "Native CSC: ${nativeSalesCode.ifBlank { "-" }} → Target: ${config.salesCode}"
                    } else {
                        if (isZh) "未开启商店跨区，保持原厂区域" else "Store spoofing off, native region"
                    },
                    trailingValue = effectiveCsc,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.selftest_root_status),
                    subtitle = if (isZh) "持久交互式 su 会话池 (含单次兜底)" else "Persistent su shell session with fallback",
                    badgeText = if (hasRoot) {
                        if (isZh) "已授权" else "Granted"
                    } else {
                        if (isZh) "未获取" else "Unavailable"
                    },
                    badgeColor = if (hasRoot) IosGlassTokens.IosGreen else IosGlassTokens.IosAmber,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.selftest_lsposed_status),
                    subtitle = if (hookedPkgs.isEmpty()) {
                        if (isZh) "打开任意目标应用即可触发注入心跳" else "Open any target app to trigger hook heartbeat"
                    } else {
                        hookedPkgs.joinToString(", ")
                    },
                    trailingValue = if (xposedConnected) {
                        if (isZh) "${hookedPkgs.size} 个进程活跃" else "${hookedPkgs.size} active"
                    } else {
                        if (isZh) "未激活" else "Inactive"
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.selftest_refresh_btn),
                    icon = Icons.Default.Refresh,
                    iconTint = IosGlassTokens.IosTeal,
                    showChevron = false,
                    onClick = {
                        haptics.tap()
                        probeTick++
                    },
                )
            }

            // 三星 One UI 常用 CscFeature 一键预设卡片
            OneUiIslandCard(
                title = stringResource(R.string.csc_presets_card_title),
                subtitle = stringResource(R.string.csc_presets_card_subtitle),
                icon = Icons.Default.Stars,
            ) {
                SAMSUNG_CSC_FEATURE_PRESETS.forEachIndexed { idx, item ->
                    if (idx > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    val isEnabled = config.customCscFeatures[item.key].equals(item.activeValue, ignoreCase = true)
                    OneUiItemRow(
                        title = stringResource(item.titleRes),
                        subtitle = stringResource(item.descRes),
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            val updatedMap = config.customCscFeatures.toMutableMap().apply {
                                if (checked) {
                                    put(item.key, item.activeValue)
                                } else {
                                    remove(item.key)
                                }
                            }.toMap()
                            customFeaturesText = updatedMap.toJson()
                            persistSwitch(
                                config.copy(
                                    spoofSemCscFeature = true,
                                    customCscFeatures = updatedMap,
                                )
                            )
                        },
                    )
                }
            }

            // 第二层：细分欺骗项开关（Miuix SuperSwitch）
            OneUiIslandCard(
                title = stringResource(R.string.settings_inject_title),
                subtitle = stringResource(R.string.settings_inject_desc),
                icon = Icons.Default.Tune,
            ) {
                OneUiItemRow(
                    title = stringResource(R.string.spoof_sales_code_setting),
                    subtitle = stringResource(R.string.spoof_sales_code_setting_desc),
                    checked = config.spoofSalesCode,
                    onCheckedChange = { persistSwitch(config.copy(spoofSalesCode = it)) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.spoof_country_code_setting),
                    subtitle = stringResource(R.string.spoof_country_code_setting_desc),
                    checked = config.spoofCountryCode,
                    onCheckedChange = { persistSwitch(config.copy(spoofCountryCode = it)) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.spoof_ril_code_setting),
                    subtitle = stringResource(R.string.spoof_ril_code_setting_desc),
                    checked = config.spoofRilSalesCode,
                    onCheckedChange = { persistSwitch(config.copy(spoofRilSalesCode = it)) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.spoof_omc_setting),
                    subtitle = "ro.csc.omcnw_code / official_csc / csc_code",
                    checked = config.spoofOmcCodes,
                    onCheckedChange = { persistSwitch(config.copy(spoofOmcCodes = it)) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OneUiItemRow(
                    title = stringResource(R.string.hook_csc_feature_setting),
                    subtitle = stringResource(R.string.hook_csc_feature_setting_desc),
                    checked = config.spoofSemCscFeature,
                    onCheckedChange = { persistSwitch(config.copy(spoofSemCscFeature = it)) },
                )
            }

            // 第三层：自定义属性覆盖
            OneUiIslandCard(
                title = stringResource(R.string.custom_props),
                subtitle = stringResource(R.string.custom_props_desc),
                icon = Icons.Default.Code,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = propKeyInput,
                        onValueChange = { propKeyInput = it },
                        label = stringResource(R.string.kv_key_hint),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        insideMargin = DpSize(10.dp, 8.dp),
                        modifier = Modifier.weight(1.2f),
                    )
                    TextField(
                        value = propValInput,
                        onValueChange = { propValInput = it },
                        label = stringResource(R.string.kv_value_hint),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        insideMargin = DpSize(10.dp, 8.dp),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.kv_add_btn),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            val k = propKeyInput.trim()
                            if (k.isNotEmpty()) {
                                haptics.tap()
                                val map = runCatching { customPropsText.trim().ifEmpty { "{}" }.toMap() }
                                    .getOrDefault(config.customProps)
                                    .toMutableMap()
                                map[k] = propValInput.trim()
                                customPropsText = map.toJson()
                                propKeyInput = ""
                                propValInput = ""
                                persistSwitch(config.copy(customProps = map))
                            }
                        },
                    )
                }

                TextField(
                    value = customPropsText,
                    onValueChange = { customPropsText = it },
                    label = "{\"ro.csc.custom\":\"value\"}",
                    useLabelAsPlaceholder = true,
                    minLines = 3,
                    textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.format_json_btn),
                        onClick = {
                            haptics.tap()
                            runCatching {
                                val formatted = JSONObject(customPropsText).toString(2)
                                customPropsText = formatted
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )

                    TextButton(
                        text = stringResource(R.string.clear_btn),
                        onClick = {
                            haptics.tap()
                            customPropsText = "{}"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 第四层：自定义 CscFeature 覆盖
            OneUiIslandCard(
                title = stringResource(R.string.custom_features),
                subtitle = stringResource(R.string.custom_features_desc),
                icon = Icons.Default.Code,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = featKeyInput,
                        onValueChange = { featKeyInput = it },
                        label = stringResource(R.string.kv_key_hint),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        insideMargin = DpSize(10.dp, 8.dp),
                        modifier = Modifier.weight(1.2f),
                    )
                    TextField(
                        value = featValInput,
                        onValueChange = { featValInput = it },
                        label = stringResource(R.string.kv_value_hint),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        insideMargin = DpSize(10.dp, 8.dp),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.kv_add_btn),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            val k = featKeyInput.trim()
                            if (k.isNotEmpty()) {
                                haptics.tap()
                                val map = runCatching { customFeaturesText.trim().ifEmpty { "{}" }.toMap() }
                                    .getOrDefault(config.customCscFeatures)
                                    .toMutableMap()
                                map[k] = featValInput.trim()
                                customFeaturesText = map.toJson()
                                featKeyInput = ""
                                featValInput = ""
                                persistSwitch(config.copy(spoofSemCscFeature = true, customCscFeatures = map))
                            }
                        },
                    )
                }

                TextField(
                    value = customFeaturesText,
                    onValueChange = { customFeaturesText = it },
                    label = "{\"CscFeature_Common_ConfigSamsungLegal\":\"...\"}",
                    useLabelAsPlaceholder = true,
                    minLines = 3,
                    textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.format_json_btn),
                        onClick = {
                            haptics.tap()
                            runCatching {
                                val formatted = JSONObject(customFeaturesText).toString(2)
                                customFeaturesText = formatted
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )

                    TextButton(
                        text = stringResource(R.string.clear_btn),
                        onClick = {
                            haptics.tap()
                            customFeaturesText = "{}"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            TextButton(
                text = stringResource(R.string.save_and_apply_btn),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    haptics.confirm()
                    error = null
                    val props = runCatching { customPropsText.trim().ifEmpty { "{}" }.toMap() }
                        .getOrElse { error = "JSON format error"; return@TextButton }
                    val features = runCatching { customFeaturesText.trim().ifEmpty { "{}" }.toMap() }
                        .getOrElse { error = "JSON format error"; return@TextButton }

                    val final = config.copy(customProps = props, customCscFeatures = features)
                    val changed = final != config
                    config = final
                    CscPrefs.save(context, final)
                    if (changed) RegionApplyState.markPending(context)
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.settings_saved)) }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                OneUiNoticeBanner(
                    text = it,
                    contentColor = IosGlassTokens.IosRed,
                )
            }

            OneUiNoticeBanner(
                text = stringResource(R.string.scope_note),
                icon = Icons.Default.Info,
                contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun Map<String, String>.toJson(): String =
    JSONObject(this).toString(2)

private fun String.toMap(): Map<String, String> {
    val obj = JSONObject(this)
    val keys = obj.keys()
    return buildMap {
        while (keys.hasNext()) {
            val k = keys.next()
            put(k, obj.optString(k))
        }
    }
}
