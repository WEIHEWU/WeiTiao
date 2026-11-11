package com.weitiao.tuning.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.weitiao.tuning.R
import com.weitiao.tuning.core.CscPrefs
import com.weitiao.tuning.core.RegionApplyState
import com.weitiao.tuning.core.StoreCleaner
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.ui.components.AmbientFluidBackground
import com.weitiao.tuning.ui.components.MiuixBottomNavBar
import com.weitiao.tuning.ui.components.MiuixNavTab
import com.weitiao.tuning.ui.screens.DashboardScreen
import com.weitiao.tuning.ui.screens.FeatureDetailScreen
import com.weitiao.tuning.ui.screens.FeaturePageId
import com.weitiao.tuning.ui.screens.FeaturesCatalogScreen
import com.weitiao.tuning.ui.screens.SearchScreen
import com.weitiao.tuning.ui.screens.SettingsScreen
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MainTab {
    HOME,
    FEATURES,
    SETTINGS,
}

@Composable
fun CscChangerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isZh = remember { Locale.getDefault().language.startsWith("zh") }

    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var activeFeaturePage by rememberSaveable { mutableStateOf<FeaturePageId?>(null) }
    var showRegionSearch by rememberSaveable { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }

    val isFastChargingActive = remember(refreshVersion) {
        TuningPrefs.load(context).brightScreenFastCharge
    }

    val navTabs = remember(isZh) {
        listOf(
            MiuixNavTab(
                id = MainTab.HOME,
                label = if (isZh) "首页" else "Home",
                icon = Icons.Default.Home,
            ),
            MiuixNavTab(
                id = MainTab.FEATURES,
                label = if (isZh) "功能" else "Features",
                icon = Icons.Default.Apps,
            ),
            MiuixNavTab(
                id = MainTab.SETTINGS,
                label = if (isZh) "设置" else "Settings",
                icon = Icons.Default.Settings,
            ),
        )
    }

    val bottomNavComposable: @Composable () -> Unit = {
        MiuixBottomNavBar(
            tabs = navTabs,
            selectedTab = selectedTab,
            onSelectTab = { nextTab ->
                refreshVersion++
                activeFeaturePage = null
                showRegionSearch = false
                selectedTab = nextTab
            },
        )
    }

    // 处理系统返回键层级：地区搜索页 -> 功能详情页 -> 回到首页Tab
    BackHandler(
        enabled = showRegionSearch || activeFeaturePage != null || selectedTab != MainTab.HOME,
    ) {
        when {
            showRegionSearch -> {
                refreshVersion++
                showRegionSearch = false
            }
            activeFeaturePage != null -> {
                refreshVersion++
                activeFeaturePage = null
            }
            selectedTab != MainTab.HOME -> {
                refreshVersion++
                selectedTab = MainTab.HOME
            }
        }
    }

    AmbientFluidBackground(
        modifier = Modifier.fillMaxSize(),
        isFastChargingActive = isFastChargingActive,
    ) {
        when {
            showRegionSearch -> {
                SearchScreen(
                    initialQuery = "",
                    onBack = {
                        refreshVersion++
                        showRegionSearch = false
                    },
                    onSelect = { entry ->
                        val current = CscPrefs.load(context)
                        val derivedMnc = if (entry.numeric.startsWith(entry.mcc) && entry.numeric.length > entry.mcc.length) {
                            entry.numeric.substring(entry.mcc.length)
                        } else {
                            "00"
                        }
                        val updated = current.copy(
                            enabled = true,
                            salesCode = entry.code.uppercase(),
                            countryCode = entry.countryCode.uppercase(),
                            simMcc = entry.mcc,
                            simMnc = derivedMnc,
                            simNumeric = entry.numeric.ifBlank { "${entry.mcc}$derivedMnc" },
                            simOperatorName = entry.operator,
                            simCountryIso = entry.countryCode.lowercase(),
                            marketUnlockRaw = "${entry.numeric},${entry.operator},${entry.countryCode.lowercase()},${entry.country}",
                            spoofSimInfo = true,
                            spoofSalesCode = true,
                            spoofCountryCode = true,
                            spoofRilSalesCode = true,
                            spoofOmcCodes = true,
                            spoofSemCscFeature = true,
                        )
                        CscPrefs.save(context, updated)
                        RegionApplyState.markPending(context)
                        refreshVersion++
                        showRegionSearch = false
                        activeFeaturePage = FeaturePageId.STORE_REGION

                        scope.launch {
                            val cleaned = withContext(Dispatchers.IO) { StoreCleaner.clearStoreDataRoot() }
                            if (cleaned) {
                                RegionApplyState.markApplied(context)
                                refreshVersion++
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.clear_store_success),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.preset_applied_toast, entry.country.ifBlank { entry.code }),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }

            activeFeaturePage != null -> {
                FeatureDetailScreen(
                    pageId = activeFeaturePage!!,
                    refreshTrigger = refreshVersion,
                    onBack = {
                        refreshVersion++
                        activeFeaturePage = null
                    },
                    onNavigatePage = { nextPage ->
                        refreshVersion++
                        activeFeaturePage = nextPage
                    },
                    onOpenRegionSearch = {
                        showRegionSearch = true
                    },
                    onConfigChanged = {
                        refreshVersion++
                    },
                )
            }

            else -> {
                when (selectedTab) {
                    MainTab.HOME -> DashboardScreen(
                        refreshTrigger = refreshVersion,
                        bottomBar = bottomNavComposable,
                        onOpenFeature = { pageId ->
                            activeFeaturePage = pageId
                        },
                        onOpenSettings = {
                            selectedTab = MainTab.SETTINGS
                        },
                        onConfigChanged = {
                            refreshVersion++
                        },
                    )

                    MainTab.FEATURES -> FeaturesCatalogScreen(
                        refreshTrigger = refreshVersion,
                        bottomBar = bottomNavComposable,
                        onOpenFeature = { pageId ->
                            activeFeaturePage = pageId
                        },
                    )

                    MainTab.SETTINGS -> SettingsScreen(
                        refreshTrigger = refreshVersion,
                        bottomBar = bottomNavComposable,
                        onOpenFeature = { pageId ->
                            activeFeaturePage = pageId
                        },
                    )
                }
            }
        }
    }
}
