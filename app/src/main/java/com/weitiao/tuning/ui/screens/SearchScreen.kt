package com.weitiao.tuning.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.NativeDevice
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.core.StoreCleaner
import com.weitiao.tuning.data.CscCatalog
import com.weitiao.tuning.data.CscEntry
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.OneUiBadge
import com.weitiao.tuning.ui.components.OneUiLargeHeader
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.theme.OneUiTokens

private val QUICK_COUNTRIES = listOf("中国", "香港", "台湾", "韩国", "日本", "美国", "英国", "德国")

@Composable
fun SearchScreen(
    initialQuery: String,
    onBack: () -> Unit,
    onSelect: (CscEntry) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    val catalog = remember { CscCatalog.load(context) }
    val nativeInfo = remember { NativeDevice.detect(context) }
    var currentCsc by remember { mutableStateOf(CscPrefs.load(context)) }
    var pendingConfirmEntry by remember { mutableStateOf<CscEntry?>(null) }

    val currentRegionText = remember(currentCsc, catalog, nativeInfo) {
        if (!currentCsc.enabled) {
            "${nativeInfo.salesCode} (MCC ${nativeInfo.simMcc})"
        } else {
            val matched = catalog.find {
                (currentCsc.simNumeric.isNotBlank() && it.numeric == currentCsc.simNumeric) ||
                    (currentCsc.salesCode.isNotBlank() && it.code.equals(currentCsc.salesCode, ignoreCase = true))
            }
            if (matched != null && matched.country.isNotBlank()) {
                "${matched.country} (${matched.code} · ${matched.numeric})"
            } else if (currentCsc.simNumeric.isNotBlank()) {
                "${currentCsc.countryCode} ${currentCsc.simNumeric}"
            } else {
                currentCsc.salesCode.ifBlank { "${nativeInfo.salesCode} (MCC ${nativeInfo.simMcc})" }
            }
        }
    }

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var searchInput by rememberSaveable { mutableStateOf(initialQuery) }

    val results = remember(query, catalog) { CscCatalog.search(catalog, query) }

    Scaffold(
        containerColor = Color.Transparent,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OneUiLargeHeader(
                title = stringResource(R.string.select_csc_title),
                subtitle = stringResource(R.string.search_screen_subtitle),
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 当前生效地区提示卡片
                Card(
                    shape = OneUiTokens.ControlShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(IosGlassTokens.IosBlue.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Public,
                                    contentDescription = null,
                                    tint = IosGlassTokens.IosBlue,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = stringResource(R.string.current_market_region, currentRegionText),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                        if (currentCsc.enabled || currentCsc.spoofSimInfo || currentCsc.marketUnlockRaw.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    haptics.confirm()
                                    StoreCleaner.restoreNativeTelephonyProps()
                                    val updated = currentCsc.copy(
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
                                    )
                                    currentCsc = updated
                                    CscPrefs.save(context, updated)
                                    StoreCleaner.clearStoreDataRoot()
                                    RegionApplyState.markApplied(context)
                                    Toast.makeText(
                                        context,
                                        R.string.restore_market_success,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) {
                                Text(
                                    text = stringResource(R.string.restore_market_default),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                )
                            }
                        }
                    }
                }

                // 搜索输入框
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = {
                        searchInput = it
                        query = it
                    },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchInput.isNotEmpty()) {
                            IconButton(onClick = { searchInput = ""; query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    singleLine = true,
                    shape = OneUiTokens.ControlShape,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )

                // 中文快捷地区分类胶囊列表
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QUICK_COUNTRIES.forEach { country ->
                        val isSelected = query == country
                        Box(
                            modifier = Modifier
                                .clip(OneUiTokens.PillShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    haptics.confirm()
                                    if (isSelected) {
                                        query = ""
                                        searchInput = ""
                                    } else {
                                        query = country
                                        searchInput = country
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = country,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Text(
                    stringResource(R.string.search_result_count, results.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 80 项地区与运营商列表 (120Hz 极速满帧)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = results,
                    key = { it.numeric + "_" + it.code },
                    contentType = { "csc_item" },
                ) { entry ->
                    CscMarketRow(
                        entry = entry,
                        onClick = {
                            haptics.tap()
                            pendingConfirmEntry = entry
                        },
                    )
                }
            }
        }
    }

    pendingConfirmEntry?.let { entry ->
        val regionDisplayName = "${entry.country.ifBlank { entry.region }} (${entry.code})"
        AlertDialog(
            onDismissRequest = { pendingConfirmEntry = null },
            shape = OneUiTokens.IslandCardShape,
            title = {
                Text(
                    text = stringResource(R.string.preset_confirm_title, regionDisplayName),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneUiNoticeBanner(
                        text = stringResource(
                            R.string.preset_confirm_auto,
                            entry.code,
                            entry.mcc,
                            entry.countryCode,
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
                        pendingConfirmEntry = null
                        onSelect(entry)
                    },
                    shape = OneUiTokens.PillShape,
                ) {
                    Text(stringResource(R.string.action_apply_and_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmEntry = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CscMarketRow(
    entry: CscEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xFF161A24) else Color(0xFFFFFFFF)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(OneUiTokens.ControlShape)
            .background(surfaceColor)
            .border(0.6.dp, borderColor, OneUiTokens.ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.country.ifBlank { entry.region },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OneUiBadge(
                    text = entry.code,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = if (entry.operator.isNotBlank()) "${entry.operator} ${entry.numeric}" else entry.region,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        OneUiBadge(
            text = entry.countryCode,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
