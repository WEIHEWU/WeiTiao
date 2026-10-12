package com.weitiao.tuning.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.weitiao.tuning.core.TuningPrefs
import com.weitiao.tuning.data.CscEntry
import com.weitiao.tuning.ui.components.AmbientFluidBackground
import com.weitiao.tuning.ui.components.rememberOneUiHaptics
import com.weitiao.tuning.ui.screens.DashboardScreen
import com.weitiao.tuning.ui.screens.SearchScreen
import com.weitiao.tuning.ui.screens.SettingsScreen
import com.weitiao.tuning.ui.screens.SpoofScreen
import com.weitiao.tuning.ui.screens.TuningScreen
import java.util.Locale
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAB_HOME = 0
private const val TAB_SPOOF = 1
private const val TAB_TUNING = 2
private const val TAB_SETTINGS = 3
private const val TAB_COUNT = 4

private data class MainTabItem(
    val index: Int,
    val labelZh: String,
    val labelEn: String,
    val icon: ImageVector,
)

private val MAIN_TABS = listOf(
    MainTabItem(TAB_HOME, "概览", "Home", Icons.Default.Home),
    MainTabItem(TAB_SPOOF, "伪装", "Spoof", Icons.Default.Public),
    MainTabItem(TAB_TUNING, "调谐", "Tuning", Icons.Default.Bolt),
    MainTabItem(TAB_SETTINGS, "设置", "Settings", Icons.Default.Tune),
)

@Composable
fun CscChangerApp() {
    val context = LocalContext.current
    val haptics = rememberOneUiHaptics()
    val scope = rememberCoroutineScope()
    val isZh = remember { Locale.getDefault().language.startsWith("zh") }

    val pagerState = rememberPagerState(
        initialPage = TAB_HOME,
        pageCount = { TAB_COUNT },
    )
    var showSearchPage by rememberSaveable { mutableStateOf(false) }
    var searchInitialQuery by rememberSaveable { mutableStateOf("") }
    var pendingSelection by remember { mutableStateOf<CscEntry?>(null) }
    var refreshVersion by remember { mutableIntStateOf(0) }

    val isFastChargingActive = remember(refreshVersion) {
        TuningPrefs.load(context).brightScreenFastCharge
    }

    fun navigateToTab(tabIndex: Int) {
        showSearchPage = false
        refreshVersion++
        scope.launch {
            pagerState.animateScrollToPage(
                page = tabIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    // 每次滑动切换 Tab 时自动刷新最新状态
    LaunchedEffect(pagerState.currentPage) {
        refreshVersion++
    }

    BackHandler(enabled = showSearchPage || pagerState.currentPage != TAB_HOME) {
        if (showSearchPage) {
            showSearchPage = false
            refreshVersion++
        } else {
            navigateToTab(TAB_HOME)
        }
    }

    AmbientFluidBackground(
        modifier = Modifier.fillMaxSize(),
        isFastChargingActive = isFastChargingActive,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 720.dp

            if (showSearchPage) {
                SearchScreen(
                    initialQuery = searchInitialQuery,
                    onBack = {
                        refreshVersion++
                        showSearchPage = false
                    },
                    onSelect = { entry ->
                        pendingSelection = entry
                        refreshVersion++
                        showSearchPage = false
                        navigateToTab(TAB_SPOOF)
                    },
                )
            } else if (isWideScreen) {
                // 折叠屏展开态 / 平板：左侧 Miuix NavigationRail + 右侧主内容区
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        color = MiuixTheme.colorScheme.surfaceContainer,
                    ) {
                        MAIN_TABS.forEach { tab ->
                            val selected = pagerState.currentPage == tab.index
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    haptics.tap()
                                    navigateToTab(tab.index)
                                },
                                icon = tab.icon,
                                label = if (isZh) tab.labelZh else tab.labelEn,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        when (pagerState.currentPage) {
                            TAB_HOME -> DashboardScreen(
                                refreshTrigger = refreshVersion,
                                onOpenSpoof = { navigateToTab(TAB_SPOOF) },
                                onOpenTuning = { navigateToTab(TAB_TUNING) },
                                onOpenSettings = { navigateToTab(TAB_SETTINGS) },
                                onConfigChanged = { refreshVersion++ },
                            )
                            TAB_SPOOF -> SpoofScreen(
                                pendingSelection = pendingSelection,
                                onConsumeSelection = {
                                    pendingSelection = null
                                    refreshVersion++
                                },
                                onOpenSearch = {
                                    searchInitialQuery = ""
                                    showSearchPage = true
                                },
                                onOpenSettings = { navigateToTab(TAB_SETTINGS) },
                                onConfigChanged = { refreshVersion++ },
                                onBack = null,
                            )
                            TAB_TUNING -> TuningScreen(
                                onConfigChanged = { refreshVersion++ },
                                onBack = null,
                            )
                            TAB_SETTINGS -> SettingsScreen(
                                onBack = null,
                            )
                        }
                    }
                }
            } else {
                // 手机单栏视图：HorizontalPager 物理弹簧左右滑页 + 底部 Miuix 悬浮圆角胶囊导航栏 (FloatingNavigationBar)
                Box(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        when (page) {
                            TAB_HOME -> DashboardScreen(
                                refreshTrigger = refreshVersion,
                                onOpenSpoof = { navigateToTab(TAB_SPOOF) },
                                onOpenTuning = { navigateToTab(TAB_TUNING) },
                                onOpenSettings = { navigateToTab(TAB_SETTINGS) },
                                onConfigChanged = { refreshVersion++ },
                            )
                            TAB_SPOOF -> SpoofScreen(
                                pendingSelection = pendingSelection,
                                onConsumeSelection = {
                                    pendingSelection = null
                                    refreshVersion++
                                },
                                onOpenSearch = {
                                    searchInitialQuery = ""
                                    showSearchPage = true
                                },
                                onOpenSettings = { navigateToTab(TAB_SETTINGS) },
                                onConfigChanged = { refreshVersion++ },
                                onBack = null,
                            )
                            TAB_TUNING -> TuningScreen(
                                onConfigChanged = { refreshVersion++ },
                                onBack = null,
                            )
                            TAB_SETTINGS -> SettingsScreen(
                                onBack = null,
                            )
                        }
                    }

                    // 底部悬浮圆角胶囊导航栏 (B 方案)
                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        FloatingNavigationBar(
                            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
                            cornerRadius = 28.dp,
                            horizontalOutSidePadding = 28.dp,
                            shadowElevation = 6.dp,
                            showDivider = true,
                            mode = FloatingNavigationBarDisplayMode.IconAndText,
                        ) {
                            MAIN_TABS.forEach { tab ->
                                val selected = pagerState.currentPage == tab.index
                                FloatingNavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            haptics.tap()
                                            navigateToTab(tab.index)
                                        }
                                    },
                                    icon = tab.icon,
                                    label = if (isZh) tab.labelZh else tab.labelEn,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
