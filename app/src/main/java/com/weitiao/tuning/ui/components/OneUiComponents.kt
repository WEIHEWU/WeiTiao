package com.weitiao.tuning.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weitiao.tuning.ui.theme.OneUiTokens

/**
 * One UI 8.5 可折叠大标题视区 (Viewing Area Header)
 */
@Composable
fun OneUiLargeHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (onBack != null) {
                val backLabel = androidx.compose.ui.res.stringResource(com.weitiao.tuning.R.string.back)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backLabel,
                        tint = IosGlassTokens.IosBlue,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = backLabel,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 17.sp,
                        ),
                        color = IosGlassTokens.IosBlue,
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(36.dp))
            }

            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions()
                }
            } else {
                Spacer(modifier = Modifier.size(36.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // iOS 标志性左对齐大标题 (Large Title: 34sp Bold)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    letterSpacing = (-0.2).sp,
                ),
                color = IosGlassTokens.IosGray,
            )
        }
    }
}

/**
 * One UI 8.5 岛屿式卡片容器 (Squircle Island Card)
 * 当处于 Liquid Glass 渲染树下时，自动升级为带 AGSL 透镜折射与高光的液态玻璃卡片
 */
@Composable
fun OneUiIslandCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    shape: RoundedCornerShape = OneUiTokens.IslandCardShape,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalLiquidBackdrop.current != null) {
        OneUiLiquidCard(
            modifier = modifier,
            title = title,
            subtitle = subtitle,
            icon = icon,
            iconTint = iconTint,
            shape = shape,
            contentPadding = contentPadding,
            content = content,
        )
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 6.dp),
                    )
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.2.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * One UI 8.5 标准交互行 (Item Row)
 */
@Composable
fun OneUiItemRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color? = null,
    iconTint: Color = IosGlassTokens.IosBlue,
    badgeText: String? = null,
    badgeColor: Color = IosGlassTokens.IosBlue,
    trailingValue: String? = null,
    showChevron: Boolean = false,
    checked: Boolean? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val haptics = rememberOneUiHaptics()
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(if (isPressed && onClick != null && enabled) 0.982f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = androidx.compose.material3.ripple(),
                    ) {
                        haptics.tap()
                        onClick()
                    }
                } else Modifier
            )
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            val iconBgModifier = if (iconBackground != null) {
                Modifier.background(iconBackground)
            } else {
                Modifier.background(
                    Brush.linearGradient(
                        listOf(
                            iconTint.copy(alpha = 0.90f),
                            iconTint.copy(alpha = 0.70f),
                        )
                    )
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
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

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        letterSpacing = (-0.2).sp,
                    ),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (!badgeText.isNullOrBlank()) {
                    OneUiBadge(text = badgeText, color = badgeColor)
                }
            }

            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        letterSpacing = (-0.1).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
        }

        if (!trailingValue.isNullOrBlank()) {
            Text(
                text = trailingValue,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 15.sp,
                    letterSpacing = (-0.2).sp,
                ),
                color = IosGlassTokens.IosBlue,
            )
        }

        if (checked != null && onCheckedChange != null) {
            // 纯正 iOS 风格开关：iOS Green (#34C759) + 白球
            Switch(
                checked = checked,
                onCheckedChange = {
                    haptics.toggle()
                    onCheckedChange(it)
                },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = IosGlassTokens.IosGreen,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = if (isDark) Color(0xFF39393D) else Color(0xFFE9E9EA),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        } else if (showChevron) {
            // iOS 风格灰色细角标
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF8E8E93),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One UI 8.5 状态胶囊徽章 (Badge Pill)
 */
@Composable
fun OneUiBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = color.copy(alpha = 0.12f),
) {
    if (LocalLiquidBackdrop.current != null) {
        OneUiLiquidBadge(text = text, modifier = modifier, color = color)
        return
    }

    Box(
        modifier = modifier
            .clip(OneUiTokens.BadgeShape)
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            ),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * One UI 8.5 分段选择器 (Segmented Control Pill Tabs)
 */
@Composable
fun <T> OneUiSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
) {
    if (LocalLiquidBackdrop.current != null) {
        OneUiLiquidSegmentedControl(
            items = items,
            selectedItem = selectedItem,
            onSelect = onSelect,
            modifier = modifier,
            label = label,
        )
        return
    }

    val haptics = rememberOneUiHaptics()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(OneUiTokens.PillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            val bgColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "segBg",
            )
            val textColor by animateColorAsState(
                if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "segText",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(OneUiTokens.PillShape)
                    .background(bgColor)
                    .clickable {
                        if (!isSelected) {
                            haptics.tap()
                            onSelect(item)
                        }
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(item),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    ),
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
 * One UI 8.5 警示与状态横条 (Notice Banner)
 */
@Composable
fun OneUiNoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = OneUiTokens.SamsungBlueContainerLight,
    contentColor: Color = OneUiTokens.SamsungBlue,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val isLiquid = LocalLiquidBackdrop.current != null
    val bannerShape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(bannerShape)
            .background(containerColor.copy(alpha = 0.20f))
            .border(
                width = 0.8.dp,
                color = contentColor.copy(alpha = 0.40f),
                shape = bannerShape,
            )
            .padding(14.dp),
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
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                letterSpacing = (-0.1).sp,
            ),
            color = contentColor,
            modifier = Modifier.weight(1f),
        )

        if (!actionText.isNullOrBlank() && onActionClick != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(contentColor)
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
