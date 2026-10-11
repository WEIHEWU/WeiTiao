package com.weitiao.tuning.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weitiao.tuning.R
import com.weitiao.tuning.ui.theme.OneUiTokens
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Miuix 丝滑阻尼折叠大标题页面脚手架 (Scaffold + Collapsible TopAppBar + overScrollVertical)
 */
@Composable
fun MiuixPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val haptics = rememberOneUiHaptics()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = title,
                largeTitle = title,
                color = MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) {
                        val backLabel = stringResource(R.string.back)
                        IconButton(
                            onClick = {
                                haptics.tap()
                                onBack()
                            },
                            modifier = Modifier.padding(start = 16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = backLabel,
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                actions = {
                    if (actions != null) {
                        Row(
                            modifier = Modifier.padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            actions()
                        }
                    }
                },
            )
        },
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(state = snackbarHostState)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
        ) {
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 26.dp, end = 26.dp, bottom = 10.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }

            content()
        }
    }
}

/**
 * 兼容固定头部页面（如包含独立 LazyColumn 的 SearchScreen）的 Miuix 顶部标题栏
 */
@Composable
fun OneUiLargeHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    val haptics = rememberOneUiHaptics()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (onBack != null) {
                val backLabel = stringResource(R.string.back)
                IconButton(
                    onClick = {
                        haptics.tap()
                        onBack()
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backLabel,
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(40.dp))
            }

            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions()
                }
            } else {
                Spacer(modifier = Modifier.size(40.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.title1.fontSize,
            fontWeight = FontWeight.Normal,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/**
 * Miuix 卡片分组容器 (SmallTitle + Miuix Card)
 */
@Composable
fun OneUiIslandCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MiuixTheme.colorScheme.primary,
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    shape: RoundedCornerShape = OneUiTokens.ControlShape,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            SmallTitle(
                text = if (!subtitle.isNullOrBlank()) "$title · $subtitle" else title,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            insideMargin = PaddingValues(contentPadding),
            colors = CardDefaults.defaultColors(
                color = containerColor,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * Miuix 原生交互列表项：
 * - 带开关时自动使用 Miuix [SuperSwitch]
 * - 带箭头或点击跳转时自动使用 Miuix [SuperArrow]
 * - 纯展示行使用 Miuix [BasicComponent]
 */
@Composable
fun OneUiItemRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color? = null,
    iconTint: Color = MiuixTheme.colorScheme.primary,
    badgeText: String? = null,
    badgeColor: Color = MiuixTheme.colorScheme.primary,
    trailingValue: String? = null,
    showChevron: Boolean = false,
    checked: Boolean? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val haptics = rememberOneUiHaptics()
    val itemMargin = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    val itemModifier = modifier.clip(RoundedCornerShape(12.dp))

    val startIconSlot: (@Composable () -> Unit)? = if (icon != null) {
        {
            val iconBgModifier = if (iconBackground != null) {
                Modifier.background(iconBackground)
            } else {
                Modifier.background(
                    Brush.linearGradient(
                        listOf(
                            iconTint.copy(alpha = 0.92f),
                            iconTint.copy(alpha = 0.72f),
                        )
                    )
                )
            }
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(iconBgModifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    } else null

    val endBadgesSlot: @Composable RowScope.() -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!badgeText.isNullOrBlank()) {
                OneUiBadge(text = badgeText, color = badgeColor)
            }
            if (!trailingValue.isNullOrBlank()) {
                Text(
                    text = trailingValue,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    maxLines = 1,
                )
            }
        }
    }

    when {
        checked != null && onCheckedChange != null -> {
            SuperSwitch(
                checked = checked,
                onCheckedChange = {
                    haptics.toggle()
                    onCheckedChange(it)
                },
                title = title,
                summary = subtitle,
                modifier = itemModifier,
                insideMargin = itemMargin,
                enabled = enabled,
                startAction = startIconSlot,
                endActions = endBadgesSlot,
            )
        }

        showChevron || onClick != null -> {
            SuperArrow(
                title = title,
                summary = subtitle,
                modifier = itemModifier,
                insideMargin = itemMargin,
                enabled = enabled,
                startAction = startIconSlot,
                endActions = endBadgesSlot,
                onClick = if (onClick != null) {
                    {
                        haptics.tap()
                        onClick()
                    }
                } else null,
            )
        }

        else -> {
            BasicComponent(
                title = title,
                summary = subtitle,
                modifier = itemModifier,
                insideMargin = itemMargin,
                enabled = enabled,
                startAction = startIconSlot,
                endActions = endBadgesSlot,
            )
        }
    }
}

/**
 * Miuix 状态胶囊徽章 (Badge Pill)
 */
@Composable
fun OneUiBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary,
    backgroundColor: Color = color.copy(alpha = if (isSystemInDarkTheme()) 0.18f else 0.12f),
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .border(
                width = 0.6.dp,
                color = color.copy(alpha = 0.30f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Miuix 分段选择器
 */
@Composable
fun <T> OneUiSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
) {
    val haptics = rememberOneUiHaptics()
    val isDark = isSystemInDarkTheme()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            val interactionSource = remember { MutableInteractionSource() }

            val thumbBgColor by animateColorAsState(
                if (isSelected) {
                    if (isDark) MiuixTheme.colorScheme.surfaceContainerHighest else Color.White
                } else Color.Transparent,
                label = "miuixSegBg",
            )

            val textColor by animateColorAsState(
                if (isSelected) MiuixTheme.colorScheme.onSurface
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                label = "miuixSegText",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(thumbBgColor)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) {
                        if (!isSelected) {
                            haptics.tap()
                            onSelect(item)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(item),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Miuix 警示与状态横条 (Notice Banner)
 */
@Composable
fun OneUiNoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = MiuixTheme.colorScheme.tertiaryContainer,
    contentColor: Color = MiuixTheme.colorScheme.primary,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val bannerShape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(bannerShape)
            .background(contentColor.copy(alpha = if (isSystemInDarkTheme()) 0.16f else 0.10f))
            .border(
                width = 0.7.dp,
                color = contentColor.copy(alpha = 0.30f),
                shape = bannerShape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }

        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )

        if (!actionText.isNullOrBlank() && onActionClick != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(contentColor)
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
