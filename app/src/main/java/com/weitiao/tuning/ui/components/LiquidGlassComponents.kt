package com.weitiao.tuning.ui.components

import androidx.compose.foundation.background
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
 * Miuix 高明度浅灰画布 + 纯白卡片 + 马卡龙柔和色彩系统
 */
object IosGlassTokens {
    val IosBlue = Color(0xFF267AF6)       // 小米 HyperOS 标志性点亮蓝
    val IosGreen = Color(0xFF22B573)      // 薄荷翡翠绿
    val IosRed = Color(0xFFEA4D44)        // 珊瑚警示红
    val IosAmber = Color(0xFFF28C28)      // 暖杏活力橙
    val IosPurple = Color(0xFF7C4DFF)     // 薰衣草紫
    val IosTeal = Color(0xFF00ACC1)       // 冰川青
    val IosIndigo = Color(0xFF5C6BC0)     // 靛青
    val IosGray = Color(0xFF8C93A4)       // 冷调中性灰

    // 磁贴点亮态底色与前景色
    @Composable
    fun activeTileBg(): Color = if (isSystemInDarkTheme()) Color(0xFF162844) else Color(0xFFE7F0FD)

    @Composable
    fun activeTileFg(): Color = if (isSystemInDarkTheme()) Color(0xFF599BFF) else Color(0xFF267AF6)

    // 马卡龙柔和图标底座底色（浅色模式下为高明度糖果浅底，深色模式下为16%透明色底）
    @Composable
    fun pastelBg(accent: Color): Color {
        if (isSystemInDarkTheme()) return accent.copy(alpha = 0.16f)
        return when (accent) {
            IosRed -> Color(0xFFFDECEB)
            IosGreen -> Color(0xFFE6F7F0)
            IosBlue -> Color(0xFFE8F2FF)
            IosAmber -> Color(0xFFFFF3E3)
            IosPurple -> Color(0xFFF2ECFF)
            IosTeal -> Color(0xFFE5F8FA)
            else -> accent.copy(alpha = 0.12f)
        }
    }

    val IosCardCorner = RoundedCornerShape(20.dp)
    val IosPillCorner = RoundedCornerShape(50)
    val IosIconCorner = RoundedCornerShape(12.dp)
}

/**
 * Miuix 纯净画布背景容器（零边框、零离屏开销）
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
    return this
        .clip(shape)
        .background(MiuixTheme.colorScheme.surfaceContainer)
}
