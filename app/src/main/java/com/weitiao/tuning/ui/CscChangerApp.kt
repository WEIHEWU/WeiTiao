package com.weitiao.tuning.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.data.CscEntry
import com.weitiao.tuning.ui.components.AmbientFluidBackground
import com.weitiao.tuning.ui.screens.DashboardScreen
import com.weitiao.tuning.ui.screens.SearchScreen
import com.weitiao.tuning.ui.screens.SettingsScreen

private enum class PageRoute { HOME, SEARCH, SETTINGS }

@Composable
fun CscChangerApp() {
    val context = LocalContext.current
    var currentRoute by rememberSaveable { mutableStateOf(PageRoute.HOME) }
    var pendingSelection by remember { mutableStateOf<CscEntry?>(null) }
    var refreshVersion by remember { mutableIntStateOf(0) }

    val isFastChargingActive = remember(refreshVersion) {
        TuningPrefs.load(context).brightScreenFastCharge
    }

    BackHandler(enabled = currentRoute != PageRoute.HOME) {
        refreshVersion++
        currentRoute = PageRoute.HOME
    }

    AmbientFluidBackground(
        modifier = Modifier.fillMaxSize(),
        isFastChargingActive = isFastChargingActive,
    ) {
        when (currentRoute) {
            PageRoute.HOME -> DashboardScreen(
                refreshTrigger = refreshVersion,
                pendingSelection = pendingSelection,
                onConsumeSelection = {
                    pendingSelection = null
                    refreshVersion++
                },
                onOpenSearch = { currentRoute = PageRoute.SEARCH },
                onOpenSettings = { currentRoute = PageRoute.SETTINGS },
                onConfigChanged = { refreshVersion++ },
            )

            PageRoute.SEARCH -> SearchScreen(
                initialQuery = "",
                onBack = {
                    refreshVersion++
                    currentRoute = PageRoute.HOME
                },
                onSelect = { entry ->
                    pendingSelection = entry
                    refreshVersion++
                    currentRoute = PageRoute.HOME
                },
            )

            PageRoute.SETTINGS -> SettingsScreen(
                onBack = {
                    refreshVersion++
                    currentRoute = PageRoute.HOME
                },
            )
        }
    }
}
