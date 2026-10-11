package com.weitiao.tuning.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weitiao.tuning.R
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.TuningConfig
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.ui.components.IosGlassTokens
import com.weitiao.tuning.ui.components.MiuixPageScaffold
import com.weitiao.tuning.ui.components.OneUiIslandCard
import com.weitiao.tuning.ui.components.OneUiItemRow
import com.weitiao.tuning.ui.components.OneUiNoticeBanner
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.theme.OneUiTokens
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

@Composable
fun TuningScreen(
    onConfigChanged: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(TuningPrefs.load(context)) }
    var multiplier by remember(config.stepMultiplier) { mutableStateOf(config.stepMultiplier.toString()) }
    var maximum by remember(config.stepMaximum) { mutableStateOf(config.stepMaximum.toString()) }
    var endHour by remember(config.stepEndHour) { mutableStateOf(config.stepEndHour.toString()) }
    var passThroughSlider by remember(config.passThroughLimit) {
        mutableFloatStateOf(config.passThroughLimit.toFloat())
    }

    var selectedApps by remember { mutableStateOf(TuningPrefs.selectedApps(context)) }
    var showAppPicker by remember { mutableStateOf(false) }

    fun notifySaved(message: String) {
        scope.launch {
            snackbar.showSnackbar(message)
        }
    }

    fun persist(updated: TuningConfig, message: String? = null) {
        config = updated
        TuningPrefs.save(context, updated)
        onConfigChanged()
        message?.let(::notifySaved)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (!isGranted) notifySaved("Notification permission not granted")
        },
    )

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 用户从系统电池设置页返回时，重新判定白名单状态并让横幅消失
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val batteryRestricted by remember(resumeTick) {
        mutableStateOf(TuningPrefs.isBatteryOptimizationRestricted(context))
    }

    MiuixPageScaffold(
        title = stringResource(R.string.dashboard_tuning_title),
        subtitle = stringResource(R.string.tuning_subtitle),
        onBack = onBack,
        snackbarHostState = snackbar,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 电池优化白名单提醒横幅
            if ((config.brightScreenFastCharge || config.passThrough) && batteryRestricted) {
                OneUiNoticeBanner(
                    text = stringResource(R.string.battery_optimization_warning),
                    icon = Icons.Default.BatteryAlert,
                    containerColor = OneUiTokens.WarningAmberContainerLight,
                    contentColor = IosGlassTokens.IosAmber,
                    actionText = stringResource(R.string.battery_optimization_action),
                    onActionClick = {
                        haptics.confirm()
                        TuningPrefs.requestIgnoreBatteryOptimization(context)
                    },
                )
            }

            // 微信增强卡片
            OneUiIslandCard(
                title = stringResource(R.string.wechat_section_title),
                subtitle = stringResource(R.string.wechat_steps_desc),
                icon = Icons.Default.Sports,
            ) {
                OneUiItemRow(
                    title = stringResource(R.string.wechat_steps),
                    subtitle = if (config.stepEnabled) stringResource(R.string.module_enabled)
                    else stringResource(R.string.module_disabled),
                    icon = Icons.Default.Sports,
                    iconTint = IosGlassTokens.IosBlue,
                    checked = config.stepEnabled,
                    onCheckedChange = {
                        persist(config.copy(stepEnabled = it))
                    },
                )

                if (!config.stepEnabled) {
                    OneUiNoticeBanner(
                        text = stringResource(R.string.wechat_steps_off_hint),
                        contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                if (config.stepEnabled) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    OneUiItemRow(
                        title = stringResource(R.string.step_auto_mode),
                        subtitle = stringResource(R.string.step_auto_mode_desc),
                        checked = config.stepAuto,
                        onCheckedChange = {
                            persist(config.copy(stepAuto = it))
                        },
                    )

                    val currentMult = config.stepMultiplier.coerceIn(1, 1000)
                    val bannerText = if (config.stepAuto) {
                        stringResource(
                            R.string.step_simulator_auto_calc,
                            currentMult,
                            config.stepMaximum,
                        )
                    } else {
                        stringResource(
                            R.string.step_simulator_calc,
                            (1000 * currentMult).coerceAtMost(config.stepMaximum),
                        )
                    }
                    OneUiNoticeBanner(
                        text = bannerText,
                        contentColor = MiuixTheme.colorScheme.primary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextField(
                            value = multiplier,
                            onValueChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(4)
                                multiplier = digits
                                digits.toIntOrNull()?.let {
                                    persist(config.copy(stepMultiplier = it.coerceIn(1, 1000)))
                                }
                            },
                            label = stringResource(R.string.step_multiplier),
                            singleLine = true,
                            insideMargin = DpSize(12.dp, 12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )

                        TextField(
                            value = maximum,
                            onValueChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(7)
                                maximum = digits
                                digits.toIntOrNull()?.let {
                                    persist(config.copy(stepMaximum = it.coerceIn(1, 1_000_000)))
                                }
                            },
                            label = stringResource(R.string.step_maximum),
                            singleLine = true,
                            insideMargin = DpSize(12.dp, 12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    TextField(
                        value = endHour,
                        onValueChange = { raw ->
                            val digits = raw.filter(Char::isDigit).take(2)
                            endHour = digits
                            digits.toIntOrNull()?.let {
                                persist(config.copy(stepEndHour = it.coerceIn(0, 24)))
                            }
                        },
                        label = stringResource(R.string.step_end_hour),
                        singleLine = true,
                        insideMargin = DpSize(12.dp, 12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OneUiNoticeBanner(
                        text = stringResource(R.string.step_cap_desc),
                        contentColor = IosGlassTokens.IosGreen,
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                OneUiItemRow(
                    title = stringResource(R.string.wechat_anti_recall),
                    subtitle = stringResource(R.string.wechat_anti_recall_desc),
                    icon = Icons.Default.Shield,
                    iconTint = IosGlassTokens.IosGreen,
                    checked = config.antiRecall,
                    onCheckedChange = {
                        persist(config.copy(antiRecall = it))
                    },
                )

                TextButton(
                    text = stringResource(R.string.restart_wechat_btn),
                    onClick = {
                        haptics.confirm()
                        val (code, _) = RootShell.exec(
                            "am force-stop com.tencent.mm && monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1"
                        )
                        if (code == 0) {
                            notifySaved(context.getString(R.string.restart_wechat_done))
                        } else {
                            notifySaved(context.getString(R.string.restart_wechat_failed))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 亮屏快充卡片 (纯 Root 温控节点管理，无需 LSPosed)
            OneUiIslandCard(
                title = stringResource(R.string.fast_charge_section),
                subtitle = stringResource(R.string.fast_charge_desc),
                icon = Icons.Default.Bolt,
                iconTint = IosGlassTokens.IosAmber,
            ) {
                OneUiItemRow(
                    title = stringResource(R.string.fast_charge_enable),
                    subtitle = stringResource(R.string.fast_charge_switch_desc),
                    icon = Icons.Default.Bolt,
                    iconTint = IosGlassTokens.IosAmber,
                    checked = config.brightScreenFastCharge,
                    onCheckedChange = {
                        persist(config.copy(brightScreenFastCharge = it))
                        if (it) requestNotificationPermissionIfNeeded()
                    },
                )

                if (config.brightScreenFastCharge) {
                    OneUiNoticeBanner(
                        text = stringResource(R.string.fast_charge_warning),
                        containerColor = OneUiTokens.WarningAmberContainerLight,
                        contentColor = IosGlassTokens.IosAmber,
                    )
                }
            }

            // 旁路供电卡片 (纯 Root 供电节点管理，无需 LSPosed)
            OneUiIslandCard(
                title = stringResource(R.string.pass_through_section),
                subtitle = stringResource(R.string.pass_through_desc),
                icon = Icons.Default.BatteryChargingFull,
                iconTint = IosGlassTokens.IosBlue,
            ) {
                OneUiItemRow(
                    title = stringResource(R.string.pass_through_enable),
                    subtitle = stringResource(R.string.pass_through_switch_desc),
                    icon = Icons.Default.BatteryChargingFull,
                    iconTint = IosGlassTokens.IosBlue,
                    checked = config.passThrough,
                    onCheckedChange = {
                        persist(config.copy(passThrough = it))
                        if (it) requestNotificationPermissionIfNeeded()
                    },
                )

                if (config.passThrough) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    Text(
                        text = stringResource(R.string.pass_through_threshold_desc, passThroughSlider.toInt()),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    Slider(
                        value = passThroughSlider,
                        onValueChange = { passThroughSlider = it },
                        onValueChangeFinished = {
                            val limit = passThroughSlider.toInt().coerceIn(0, 100)
                            persist(config.copy(passThroughLimit = limit))
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                        showKeyPoints = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )

                    OneUiItemRow(
                        title = stringResource(R.string.choose_pass_through_apps),
                        subtitle = if (selectedApps.isEmpty()) stringResource(R.string.choose_apps_none)
                        else stringResource(R.string.choose_apps_count, selectedApps.size),
                        badgeText = if (selectedApps.isNotEmpty()) "${selectedApps.size} APPS" else null,
                        badgeColor = IosGlassTokens.IosBlue,
                        showChevron = true,
                        onClick = { showAppPicker = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    ModernAppPickerDialog(
        show = showAppPicker,
        initial = selectedApps,
        onDismiss = { showAppPicker = false },
        onSave = { updated ->
            selectedApps = updated
            TuningPrefs.saveSelectedApps(context, updated)
            TuningPrefs.save(context, config)
            onConfigChanged()
            notifySaved(context.getString(R.string.saved_toast))
            showAppPicker = false
        },
    )
}

@Composable
private fun ModernAppPickerDialog(
    show: Boolean = true,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                LaunchableApp(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = runCatching { it.loadIcon(pm) }.getOrNull(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    val filteredApps = remember(searchQuery, apps) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    var selected by remember(initial) { mutableStateOf(initial.toSet()) }

    WindowDialog(
        show = show,
        title = stringResource(R.string.choose_pass_through_apps),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
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
                    text = stringResource(R.string.save_app_selection, selected.size),
                    onClick = { onSave(selected) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
