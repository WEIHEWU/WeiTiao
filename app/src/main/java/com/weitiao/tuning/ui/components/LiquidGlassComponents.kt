package com.weitiao.tuning.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalLiquidBackdrop = compositionLocalOf<Any?> { null }

/**
 * Miuix / Xiaomi HyperOS 语义色彩与圆角规范
 */
object IosGlassTokens {
    val IosGreen = Color(0xFF26C281)      // HyperOS 翡翠健康绿
    val IosBlue = Color(0xFF3482FF)       // Miuix 官方主色蓝
    val IosIndigo = Color(0xFF5C6BC0)     // 靛蓝
    val IosPurple = Color(0xFF9C27B0)     // 紫罗兰
    val IosTeal = Color(0xFF00BCD4)       // 青色
    val IosAmber = Color(0xFFFF9800)      // 警示活力橙
    val IosRed = Color(0xFFE94634)        // Miuix 官方警示红
    val IosGray = Color(0xFF8C93B0)       // Miuix 副标题灰

    val IosCardCorner = RoundedCornerShape(16.dp)
    val IosPillCorner = RoundedCornerShape(50)
    val IosIconCorner = RoundedCornerShape(10.dp)
}

/**
 * Miuix 原生纯色背景容器（0 GPU 离屏开销，120Hz 满帧）
 */
@Composable
fun AmbientFluidBackground(
    modifier: Modifier = Modifier,
    isFastChargingActive: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        content()
    }
}

@Composable
fun Modifier.iosLiquidGlass(
    shape: Shape = IosGlassTokens.IosCardCorner,
    blurRadius: Dp = 16.dp,
    refractionHeight: Dp = 8.dp,
    refractionAmount: Dp = 10.dp,
): Modifier {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = MiuixTheme.colorScheme.surfaceContainer
    val borderColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
    return this
        .clip(shape)
        .background(surfaceColor)
        .border(0.5.dp, borderColor, shape)
}
