package com.weitiao.tuning.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.unit.DpSize
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
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

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
    var lastConfirmEntry by remember { mutableStateOf(catalog.firstOrNull()) }
    pendingConfirmEntry?.let { lastConfirmEntry = it }

    val currentRegionText = remember(currentCsc, catalog, nativeInfo) {
        val isZh = context.resources.configuration.locales[0].language.startsWith("zh")
        if (!currentCsc.enabled) {
            if (isZh) "未开启伪装 (原生识别)" else "Native (Unmodified)"
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
                currentCsc.salesCode.ifBlank { if (isZh) "未开启伪装" else "Native" }
            }
        }
    }

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var searchInput by rememberSaveable { mutableStateOf(initialQuery) }

    val results = remember(query, catalog) { CscCatalog.search(catalog, query) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.select_csc_title),
                color = MiuixTheme.colorScheme.surface,
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptics.tap()
                            onBack()
                        },
                        modifier = Modifier.padding(start = 16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.search_screen_subtitle),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                // 当前生效地区提示卡片
                Card(
                    cornerRadius = 14.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.tertiaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = stringResource(R.string.current_market_region, currentRegionText),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        if (currentCsc.enabled || currentCsc.spoofSimInfo || currentCsc.marketUnlockRaw.isNotBlank()) {
                            TextButton(
                                text = stringResource(R.string.restore_market_default),
                                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    color = Color.Transparent,
                                    textColor = IosGlassTokens.IosRed,
                                ),
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
                            )
                        }
                    }
                }

                // 搜索输入框（Miuix TextField）
                TextField(
                    value = searchInput,
                    onValueChange = {
                        searchInput = it
                        query = it
                    },
                    label = stringResource(R.string.search_hint),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    insideMargin = DpSize(14.dp, 12.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            modifier = Modifier.padding(start = 12.dp).size(18.dp),
                        )
                    },
                    trailingIcon = {
                        if (searchInput.isNotEmpty()) {
                            IconButton(
                                onClick = { searchInput = ""; query = "" },
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSelected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.secondaryContainer
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
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.search_result_count, results.size),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 80 项地区与运营商列表 (Miuix overScrollVertical 弹性阻尼)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .overScrollVertical(),
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

    val confirmEntry = pendingConfirmEntry ?: lastConfirmEntry
    if (confirmEntry != null) {
        val regionDisplayName = "${confirmEntry.country.ifBlank { confirmEntry.region }} (${confirmEntry.code})"
        WindowDialog(
            show = pendingConfirmEntry != null,
            title = stringResource(R.string.preset_confirm_title, regionDisplayName),
            onDismissRequest = { pendingConfirmEntry = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OneUiNoticeBanner(
                    text = stringResource(
                        R.string.preset_confirm_auto,
                        confirmEntry.code,
                        confirmEntry.mcc,
                        confirmEntry.countryCode,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { pendingConfirmEntry = null },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.action_apply_and_clear),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            haptics.confirm()
                            pendingConfirmEntry = null
                            onSelect(confirmEntry)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CscMarketRow(
    entry: CscEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    OneUiBadge(
                        text = entry.code,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = if (entry.operator.isNotBlank()) "${entry.operator} ${entry.numeric}" else entry.region,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }

            OneUiBadge(
                text = entry.countryCode,
                color = IosGlassTokens.IosGray,
            )
        }
    }
}
