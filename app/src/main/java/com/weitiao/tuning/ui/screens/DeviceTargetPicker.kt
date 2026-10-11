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
import top.yukonga.miuix.kmp.extra.WindowDialog
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
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
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

    val filteredApps = remember(query, allApps) {
        if (query.isBlank()) allApps
        else allApps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    var selected by remember { mutableStateOf(initialSelection.toSet()) }

    WindowDialog(
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

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .overScrollVertical(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = app.packageName in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selected = selected.toMutableSet().apply {
                                    if (!add(app.packageName)) remove(app.packageName)
                                }.toSet()
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

                        Column(Modifier.weight(1f)) {
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
                    onClick = { onSave(selected) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
