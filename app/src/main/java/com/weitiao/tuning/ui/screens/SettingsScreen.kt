package com.weitiao.tuning.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.weitiao.tuning.App
import com.weitiao.tuning.BuildConfig
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.LanguageManager
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    var error by remember { mutableStateOf<String?>(null) }

    val xposedConnected = App.getXposedService() != null || App.getRemotePrefs() != null

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
