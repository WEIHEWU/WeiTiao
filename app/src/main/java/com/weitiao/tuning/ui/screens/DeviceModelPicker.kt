package com.weitiao.tuning.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weitiao.tuning.R
import com.weitiao.tuning.core.DeviceModels
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.WindowBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun DeviceModelPicker(
    show: Boolean = true,
    initialSelection: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val haptics = rememberOneUiHaptics()
    var query by remember { mutableStateOf("") }
    val families = remember { DeviceModels.BY_FAMILY.keys.toList() }
    val initialFamily = remember(initialSelection) {
        DeviceModels.findByName(initialSelection)?.family ?: families.firstOrNull() ?: "Samsung"
    }
    var selectedFamily by remember(initialFamily, show) { mutableStateOf(initialFamily) }

    val filtered = remember(query, selectedFamily) {
        val needle = query.trim()
        if (needle.isNotBlank()) {
            val cleanNeedle = needle.lowercase().filter { it.isLetterOrDigit() }
            DeviceModels.ALL.filter { profile ->
                val matchNormal = listOf(
                    profile.name,
                    profile.model,
                    profile.device,
                    profile.brand,
                    profile.manufacturer,
                ).any { it.contains(needle, ignoreCase = true) }
                val matchClean = cleanNeedle.isNotBlank() && listOf(
                    profile.name,
                    profile.model,
                    profile.device,
                ).any { it.lowercase().filter { c -> c.isLetterOrDigit() }.contains(cleanNeedle) }
                matchNormal || matchClean
            }
        } else {
            DeviceModels.BY_FAMILY[selectedFamily].orEmpty()
        }
    }

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.choose_device_model, DeviceModels.ALL.size),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.search_device_model),
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
                modifier = Modifier.fillMaxWidth(),
            )

            // 品牌分类横向 Pill 列表
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                families.forEach { item ->
                    val isSelected = selectedFamily == item && query.isBlank()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isSelected) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.secondaryContainer
                            )
                            .clickable {
                                haptics.tap()
                                selectedFamily = item
                                query = ""
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.device_model_result_count, filtered.size),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .overScrollVertical(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val customName = query.trim()
                if (customName.isNotBlank() && filtered.none { it.name.equals(customName, ignoreCase = true) }) {
                    item(key = "custom_selection_$customName") {
                        Card(
                            cornerRadius = 14.dp,
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            colors = CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.tertiaryContainer,
                            ),
                            onClick = {
                                haptics.confirm()
                                onSelect(customName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MiuixTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Smartphone,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.use_custom_model_format, customName),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.custom_model_desc),
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }

                items(filtered, key = { "${it.family}:${it.name}:${it.model}" }) { profile ->
                    val isCurrent = profile.name == initialSelection

                    Card(
                        cornerRadius = 14.dp,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        colors = CardDefaults.defaultColors(
                            color = if (isCurrent) MiuixTheme.colorScheme.tertiaryContainer
                            else MiuixTheme.colorScheme.secondaryContainer,
                        ),
                        onClick = {
                            haptics.confirm()
                            onSelect(profile.name)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isCurrent) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.surfaceContainer
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Smartphone,
                                    contentDescription = null,
                                    tint = if (isCurrent) Color.White else MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${profile.model} (${profile.device})",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }

                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(
                text = stringResource(R.string.close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
