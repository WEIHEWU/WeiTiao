package com.weitiao.tuning.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.data.CscEntry
import com.weitiao.tuning.ui.components.AmbientFluidBackground
import com.weitiao.tuning.ui.screens.DashboardScreen
import com.weitiao.tuning.ui.screens.SearchScreen
import com.weitiao.tuning.ui.screens.SettingsScreen
import com.weitiao.tuning.ui.screens.SpoofScreen
import com.weitiao.tuning.ui.screens.TuningScreen

private const val SCREEN_HOME = 0
private const val SCREEN_SETTINGS = 1
private const val SCREEN_SEARCH = 2
private const val SCREEN_TUNING = 3
private const val SCREEN_SPOOF = 4

@Composable
fun CscChangerApp() {
    val context = LocalContext.current
    var screenIndex by rememberSaveable { mutableIntStateOf(SCREEN_HOME) }
    var searchInitialQuery by rememberSaveable { mutableStateOf("") }
    var settingsReturnIndex by rememberSaveable { mutableIntStateOf(SCREEN_SPOOF) }
    var pendingSelection by remember { mutableStateOf<CscEntry?>(null) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    val isFastChargingActive = remember(refreshVersion) {
        TuningPrefs.load(context).brightScreenFastCharge
    }

    AmbientFluidBackground(
        modifier = Modifier.fillMaxSize(),
        isFastChargingActive = isFastChargingActive,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 720.dp

            BackHandler(enabled = screenIndex != SCREEN_HOME) {
                refreshVersion++
                screenIndex = when (screenIndex) {
                    SCREEN_SEARCH -> SCREEN_SPOOF
                    SCREEN_SETTINGS -> settingsReturnIndex
                    else -> SCREEN_HOME
                }
            }

            if (isWideScreen) {
                // Galaxy Z Fold 展开态 / 平板 Master-Detail 双栏视图
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .fillMaxHeight(),
                    ) {
                        DashboardScreen(
                            refreshTrigger = refreshVersion,
                            onOpenSpoof = { screenIndex = SCREEN_SPOOF },
                            onOpenTuning = { screenIndex = SCREEN_TUNING },
                        )
                    }

                    VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        when (screenIndex) {
                            SCREEN_HOME, SCREEN_SPOOF -> SpoofScreen(
                                pendingSelection = pendingSelection,
                                onConsumeSelection = {
                                    pendingSelection = null
                                    refreshVersion++
                                },
                                onOpenSearch = {
                                    searchInitialQuery = ""
                                    screenIndex = SCREEN_SEARCH
                                },
                                onOpenSettings = {
                                    settingsReturnIndex = SCREEN_SPOOF
                                    screenIndex = SCREEN_SETTINGS
                                },
                                onConfigChanged = { refreshVersion++ },
                                onBack = null,
                            )
                            SCREEN_SETTINGS -> SettingsScreen(
                                onBack = {
                                    refreshVersion++
                                    screenIndex = settingsReturnIndex
                                },
                            )
                            SCREEN_SEARCH -> SearchScreen(
                                initialQuery = searchInitialQuery,
                                onBack = {
                                    refreshVersion++
                                    screenIndex = SCREEN_SPOOF
                                },
                                onSelect = { entry ->
                                    pendingSelection = entry
                                    refreshVersion++
                                    screenIndex = SCREEN_SPOOF
                                },
                            )
                            SCREEN_TUNING -> TuningScreen(
                                onConfigChanged = { refreshVersion++ },
                                onBack = null,
                            )
                        }
                    }
                }
            } else {
                // 手机单栏视图
                when (screenIndex) {
                    SCREEN_HOME -> DashboardScreen(
                        refreshTrigger = refreshVersion,
                        onOpenSpoof = { screenIndex = SCREEN_SPOOF },
                        onOpenTuning = { screenIndex = SCREEN_TUNING },
                    )
                    SCREEN_SPOOF -> SpoofScreen(
                        pendingSelection = pendingSelection,
                        onConsumeSelection = {
                            pendingSelection = null
                            refreshVersion++
                        },
                        onOpenSearch = {
                            searchInitialQuery = ""
                            screenIndex = SCREEN_SEARCH
                        },
                        onOpenSettings = {
                            settingsReturnIndex = SCREEN_SPOOF
                            screenIndex = SCREEN_SETTINGS
                        },
                        onConfigChanged = { refreshVersion++ },
                        onBack = {
                            refreshVersion++
                            screenIndex = SCREEN_HOME
                        },
                    )
                    SCREEN_SETTINGS -> SettingsScreen(
                        onBack = {
                            refreshVersion++
                            screenIndex = settingsReturnIndex
                        },
                    )
                    SCREEN_SEARCH -> SearchScreen(
                        initialQuery = searchInitialQuery,
                        onBack = {
                            refreshVersion++
                            screenIndex = SCREEN_SPOOF
                        },
                        onSelect = { entry ->
                            pendingSelection = entry
                            refreshVersion++
                            screenIndex = SCREEN_SPOOF
                        },
                    )
                    SCREEN_TUNING -> TuningScreen(
                        onConfigChanged = { refreshVersion++ },
                        onBack = {
                            refreshVersion++
                            screenIndex = SCREEN_HOME
                        },
                    )
                }
            }
        }
    }
}
