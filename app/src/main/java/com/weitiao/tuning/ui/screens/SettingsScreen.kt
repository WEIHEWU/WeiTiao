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
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(CscPrefs.load(context)) }
    var customPropsText by rememberSaveable { mutableStateOf(config.customProps.toJson()) }
    var customFeaturesText by rememberSaveable { mutableStateOf(config.customCscFeatures.toJson()) }
    var error by remember { mutableStateOf<String?>(null) }

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
        subtitle = stringResource(R.string.settings_subtitle),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 细分欺骗项开关（Miuix SuperSwitch）
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

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                OneUiItemRow(
                    title = stringResource(R.string.spoof_country_code_setting),
                    subtitle = stringResource(R.string.spoof_country_code_setting_desc),
                    checked = config.spoofCountryCode,
                    onCheckedChange = { persistSwitch(config.copy(spoofCountryCode = it)) },
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                OneUiItemRow(
                    title = stringResource(R.string.spoof_ril_code_setting),
                    subtitle = stringResource(R.string.spoof_ril_code_setting_desc),
                    checked = config.spoofRilSalesCode,
                    onCheckedChange = { persistSwitch(config.copy(spoofRilSalesCode = it)) },
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                OneUiItemRow(
                    title = stringResource(R.string.spoof_omc_setting),
                    subtitle = "ro.csc.omcnw_code / official_csc / csc_code",
                    checked = config.spoofOmcCodes,
                    onCheckedChange = { persistSwitch(config.copy(spoofOmcCodes = it)) },
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                OneUiItemRow(
                    title = stringResource(R.string.hook_csc_feature_setting),
                    subtitle = stringResource(R.string.hook_csc_feature_setting_desc),
                    checked = config.spoofSemCscFeature,
                    onCheckedChange = { persistSwitch(config.copy(spoofSemCscFeature = it)) },
                )
            }

            // 自定义属性覆盖
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

            // 自定义 CscFeature 覆盖
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

            // 应用自定义属性按钮
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

            // 作用域说明
            OneUiNoticeBanner(
                text = stringResource(R.string.scope_note),
                icon = Icons.Default.Info,
                contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(modifier = Modifier.height(24.dp))
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
