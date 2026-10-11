package com.weitiao.tuning.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
/**
 * 全局共享的 Liquid Glass Backdrop 标记（默认为 null）
 */
val LocalLiquidBackdrop = compositionLocalOf<Any?> { null }

// iOS 18 官方系统设计规范色彩与圆角
object IosGlassTokens {
    val IosGreen = Color(0xFF34C759)      // 经典苹果健康绿
    val IosBlue = Color(0xFF007AFF)       // 经典苹果极速蓝
    val IosIndigo = Color(0xFF5856D6)     // 苹果靛蓝
    val IosPurple = Color(0xFFAF52DE)     // 苹果紫罗兰
    val IosTeal = Color(0xFF30B0C7)       // 苹果纯电青
    val IosAmber = Color(0xFFFF9500)      // 苹果活力橙
    val IosRed = Color(0xFFFF3B30)        // 苹果警示红
    val IosGray = Color(0xFF8E8E93)       // 苹果副标题灰

    // iOS Inset Grouped 标准圆角
    val IosCardCorner = RoundedCornerShape(18.dp)
    val IosPillCorner = RoundedCornerShape(50)
    val IosIconCorner = RoundedCornerShape(8.5.dp)
}

/**
 * 120Hz 极速满帧背景容器 (One UI Native Smooth Background)
 * 采用硬件加速纯净表面与微光渐变，0 CPU 循环开销，0 额外离屏缓冲，杜绝掉帧与卡顿。
 */
@Composable
fun AmbientFluidBackground(
    modifier: Modifier = Modifier,
    isFastChargingActive: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF0C101A) else Color(0xFFF2F5FB)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        content()
    }
}

/**
 * 120Hz 极速满帧 One UI 卡片表面 Modifier
 * 采用硬件加速纯净表面 + 极简高光边框，杜绝任何离屏缓冲区抖动与掉帧
 */
@Composable
fun Modifier.iosLiquidGlass(
    shape: Shape = IosGlassTokens.IosCardCorner,
    blurRadius: Dp = 16.dp,
    refractionHeight: Dp = 8.dp,
    refractionAmount: Dp = 10.dp,
): Modifier {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xFF161A24) else Color(0xFFFFFFFF)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    return this
        .clip(shape)
        .background(surfaceColor)
        .border(0.6.dp, borderColor, shape)
}

/**
 * iOS Inset Grouped 风格的高透晶莹液态卡片容器
 */
@Composable
fun OneUiLiquidCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = IosGlassTokens.IosBlue,
    shape: RoundedCornerShape = IosGlassTokens.IosCardCorner,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
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
                            .size(16.dp)
                            .padding(end = 6.dp),
                    )
                }
                Column {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            fontSize = 12.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .iosLiquidGlass(shape = shape),
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
 * iOS 经典悬浮滑块分段选择器（轻量硬件级，0 离屏缓冲）
 */
@Composable
fun <T> OneUiLiquidSegmentedControl(
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
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (isDark) Color(0xFF2C2C2E).copy(alpha = 0.60f)
                else Color(0xFFE3E3E8).copy(alpha = 0.75f)
            )
            .border(
                width = 0.5.dp,
                color = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.05f),
                shape = RoundedCornerShape(9.dp),
            )
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            val interactionSource = remember { MutableInteractionSource() }

            val thumbBgColor by animateColorAsState(
                if (isSelected) {
                    if (isDark) Color(0xFF636366).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.98f)
                } else Color.Transparent,
                label = "iosThumbBg",
            )

            val textColor by animateColorAsState(
                if (isSelected) {
                    if (isDark) Color.White else Color.Black
                } else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "iosThumbText",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(thumbBgColor)
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 0.5.dp,
                                color = Color.White.copy(alpha = if (isDark) 0.35f else 0.65f),
                                shape = RoundedCornerShape(7.dp),
                            )
                        } else Modifier
                    )
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
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp,
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
 * iOS 状态微光胶囊徽章（轻量硬件级，0 离屏缓冲）
 */
@Composable
fun OneUiLiquidBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = IosGlassTokens.IosBlue,
) {
    val isDark = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .clip(IosGlassTokens.IosPillCorner)
            .background(color.copy(alpha = if (isDark) 0.18f else 0.12f))
            .border(
                width = 0.7.dp,
                color = color.copy(alpha = 0.35f),
                shape = IosGlassTokens.IosPillCorner,
            )
            .padding(horizontal = 9.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            ),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}
