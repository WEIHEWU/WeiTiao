package com.weitiao.tuning.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixShape
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Miuix 丝滑阻尼折叠大标题页面脚手架
 */
@Composable
fun MiuixPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    bottomNavigationPadding: Dp = 96.dp,
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
                .scrollEndHaptic()
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + bottomNavigationPadding,
                ),
        ) {
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }

            content()
        }
    }
}

/**
 * 顶部主状态横幅大卡（左上粗体大字 + 浅蓝圆点胶囊 + 双行完整摘要 + 右下角超大半裁切圆环对勾水印）
 */
@Composable
fun MiuixHeroStatusCard(
    statusTitle: String,
    pillText: String,
    line1Text: String,
    line2Text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = IosGlassTokens.IosBlue,
    isWarning: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val isDark = isSystemInDarkTheme()
    val cardShape = miuixShape(22.dp)
    val pillBg = if (isWarning) {
        IosGlassTokens.pastelBg(IosGlassTokens.IosAmber)
    } else {
        IosGlassTokens.activeTileBg()
    }
    val pillFg = if (isWarning) IosGlassTokens.IosAmber else IosGlassTokens.activeTileFg()
    val watermarkColor = if (isWarning) {
        IosGlassTokens.IosAmber.copy(alpha = if (isDark) 0.70f else 0.85f)
    } else {
        accentColor.copy(alpha = if (isDark) 0.72f else 0.88f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clipToBounds()
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
    ) {
        // 右下角超大半裁切圆环对勾水印
        Canvas(
            modifier = Modifier
                .size(110.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 28.dp, y = 28.dp),
        ) {
            val strokeWidth = 9.5.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2f
            drawCircle(
                color = watermarkColor,
                radius = radius,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            if (isWarning) {
                val cx = size.width * 0.50f
                val path = Path().apply {
                    moveTo(cx, size.height * 0.30f)
                    lineTo(cx, size.height * 0.56f)
                }
                drawPath(
                    path = path,
                    color = watermarkColor,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                drawCircle(
                    color = watermarkColor,
                    radius = strokeWidth * 0.55f,
                    center = androidx.compose.ui.geometry.Offset(cx, size.height * 0.71f),
                )
            } else {
                val checkPath = Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.52f)
                    lineTo(size.width * 0.45f, size.height * 0.67f)
                    lineTo(size.width * 0.72f, size.height * 0.37f)
                }
                drawPath(
                    path = checkPath,
                    color = watermarkColor,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 20.dp, end = 64.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = statusTitle,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )

                // ● 版本胶囊徽章
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(pillBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(pillFg),
                    )
                    Text(
                        text = pillText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = pillFg,
                        maxLines = 1,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = line1Text,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
                Text(
                    text = line2Text,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.82f),
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * 3×2 快捷点亮式状态磁贴（启用时变为柔和浅天蓝底 #E7F0FD + 小米蓝字 #267AF6；未启用时纯白底）
 */
@Composable
fun MiuixQuickTile(
    label: String,
    statusText: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptics = rememberOneUiHaptics()
    val tileShape = miuixShape(18.dp)
    val bgColor by animateColorAsState(
        targetValue = if (active) IosGlassTokens.activeTileBg() else MiuixTheme.colorScheme.surfaceContainer,
        label = "quickTileBg",
    )
    val iconColor by animateColorAsState(
        targetValue = if (active) IosGlassTokens.activeTileFg() else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        label = "quickTileIcon",
    )
    val labelColor by animateColorAsState(
        targetValue = if (active) IosGlassTokens.activeTileFg().copy(alpha = 0.85f) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        label = "quickTileLabel",
    )
    val statusColor by animateColorAsState(
        targetValue = if (active) IosGlassTokens.activeTileFg() else MiuixTheme.colorScheme.onSurface,
        label = "quickTileStatus",
    )

    Row(
        modifier = modifier
            .clip(tileShape)
            .background(bgColor)
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(18.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = labelColor,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = statusText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * 2×2 马卡龙柔和超椭圆图标 + 大数字/大核心值指标卡
 */
@Composable
fun MiuixMetricCard(
    valueText: String,
    labelText: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val haptics = rememberOneUiHaptics()
    val cardShape = miuixShape(18.dp)
    val iconBoxShape = miuixShape(12.dp)
    val iconBg = IosGlassTokens.pastelBg(accentColor)

    Row(
        modifier = modifier
            .clip(cardShape)
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .then(
                if (onClick != null) {
                    Modifier.clickable {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(iconBoxShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = labelText,
                tint = accentColor,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = valueText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = labelText,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 干净利落的左对齐粗体分组标题（支持下方完整副标题描述或右侧简短状态摘要）
 */
@Composable
fun MiuixSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingText: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (!trailingText.isNullOrBlank()) {
                Text(
                    text = trailingText,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
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
 * Miuix 纯白无边框卡片分组容器 (MiuixSectionTitle + 20.dp Miuix Card)
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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            MiuixSectionTitle(
                title = title,
                subtitle = subtitle,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
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
 * Miuix 原生交互列表项（统一使用柔和马卡龙圆角方块图标底座 + SuperSwitch / SuperArrow / BasicComponent）
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
    val itemModifier = modifier.clip(miuixShape(14.dp))

    val startIconSlot: (@Composable () -> Unit)? = if (icon != null) {
        {
            val resolvedBg = iconBackground ?: IosGlassTokens.pastelBg(iconTint)
            val resolvedIconTint = if (iconBackground != null) Color.White else iconTint
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(38.dp)
                    .clip(miuixShape(11.dp))
                    .background(resolvedBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = resolvedIconTint,
                    modifier = Modifier.size(19.dp),
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
 * Miuix 柔和胶囊徽章 (Badge Pill)
 */
@Composable
fun OneUiBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary,
    backgroundColor: Color = IosGlassTokens.pastelBg(color),
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
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
            .clip(miuixShape(12.dp))
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
                    .clip(miuixShape(9.dp))
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
    val bannerShape = miuixShape(16.dp)
    val bg = IosGlassTokens.pastelBg(contentColor)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(bannerShape)
            .background(bg)
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )

        if (!actionText.isNullOrBlank() && onActionClick != null) {
            Box(
                modifier = Modifier
                    .clip(miuixShape(10.dp))
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
