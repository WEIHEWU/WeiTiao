package com.weitiao.tuning.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.weitiao.tuning.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.WindowBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private data class TargetAppItem(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

@Composable
fun DeviceTargetPicker(
    show: Boolean = true,
    initialSelection: Set<String>,
    initialPerAppModels: Map<String, String> = emptyMap(),
    globalDefaultModel: String = "",
    onDismiss: () -> Unit,
    onSave: (Set<String>, Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val pm = context.packageManager

    val allApps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                TargetAppItem(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = runCatching { it.loadIcon(pm) }.getOrNull(),
                )
            }
            .filterNot { it.packageName == context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    var selected by remember { mutableStateOf(initialSelection.toSet()) }
    var perAppModels by remember { mutableStateOf(initialPerAppModels.toMap()) }
    var pickingModelForPkg by remember { mutableStateOf<String?>(null) }

    val filteredApps = remember(query, allApps, selected, perAppModels) {
        val base = if (query.isBlank()) allApps
        else allApps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        base.sortedWith(
            compareByDescending<TargetAppItem> { it.packageName in selected || it.packageName in perAppModels }
                .thenBy { it.label.lowercase() },
        )
    }

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.choose_device_target_apps),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.search_app_hint),
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

            Text(
                text = stringResource(R.string.device_target_scope_hint),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .overScrollVertical(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = app.packageName in selected
                    val customModel = perAppModels[app.packageName]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val next = selected.toMutableSet()
                                if (!next.add(app.packageName)) {
                                    next.remove(app.packageName)
                                    perAppModels = perAppModels - app.packageName
                                }
                                selected = next.toSet()
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (app.icon != null) {
                            Image(
                                bitmap = app.icon.toBitmap(96, 96).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MiuixTheme.colorScheme.secondaryContainer),
                            )
                        }

                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = app.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = app.packageName,
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            if (isChecked) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    val badgeText = if (!customModel.isNullOrBlank()) {
                                        stringResource(R.string.picker_per_app_model_custom, customModel)
                                    } else {
                                        stringResource(
                                            R.string.picker_per_app_model_default,
                                            globalDefaultModel.ifBlank { stringResource(R.string.native_device) },
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (!customModel.isNullOrBlank()) {
                                                    MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                                                } else {
                                                    MiuixTheme.colorScheme.secondaryContainer
                                                },
                                            )
                                            .clickable { pickingModelForPkg = app.packageName }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            text = badgeText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (!customModel.isNullOrBlank()) {
                                                MiuixTheme.colorScheme.primary
                                            } else {
                                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            },
                                        )
                                    }
                                    if (!customModel.isNullOrBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MiuixTheme.colorScheme.secondaryContainer)
                                                .clickable { perAppModels = perAppModels - app.packageName }
                                                .padding(horizontal = 6.dp, vertical = 3.dp),
                                        ) {
                                            Text(
                                                text = "×",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Checkbox(
                            state = ToggleableState(isChecked),
                            onClick = {
                                val next = selected.toMutableSet()
                                if (!next.add(app.packageName)) {
                                    next.remove(app.packageName)
                                    perAppModels = perAppModels - app.packageName
                                }
                                selected = next.toSet()
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

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
                    text = stringResource(R.string.confirm_and_request_scope, selected.size),
                    onClick = {
                        val cleanedModels = perAppModels.filterKeys { it in selected }
                        onSave(selected, cleanedModels)
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    val targetPkg = pickingModelForPkg
    if (targetPkg != null) {
        DeviceModelPicker(
            show = true,
            initialSelection = perAppModels[targetPkg] ?: globalDefaultModel,
            onDismiss = { pickingModelForPkg = null },
            onSelect = { selectedName ->
                perAppModels = perAppModels + (targetPkg to selectedName)
                pickingModelForPkg = null
            },
        )
    }
}
