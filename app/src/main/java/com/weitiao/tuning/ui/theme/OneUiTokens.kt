package com.weitiao.tuning.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Samsung One UI 8.5 官方设计系统 Tokens
 * 遵循三星设计语言：26dp 超椭圆曲率、AMOLED 纯黑基底、Galaxy 品牌色阶
 */
object OneUiTokens {

    // ---- 品牌主色 (Samsung Galaxy Palette) ----
    val SamsungBlue = Color(0xFF0C80FF)
    val SamsungBlueDark = Color(0xFF58A6FF)
    val SamsungBlueContainerLight = Color(0xFFE5F1FF)
    val SamsungBlueContainerDark = Color(0xFF142E4E)

    // ---- 语义状态色 ----
    val SuccessGreen = Color(0xFF00A862)
    val SuccessGreenDark = Color(0xFF34D399)
    val SuccessGreenContainerLight = Color(0xFFE6F8F0)
    val SuccessGreenContainerDark = Color(0xFF0D3322)

    val WarningAmber = Color(0xFFFF9500)
    val WarningAmberDark = Color(0xFFFBBF24)
    val WarningAmberContainerLight = Color(0xFFFFF7EB)
    val WarningAmberContainerDark = Color(0xFF3B280A)

    val DangerRed = Color(0xFFFA5252)
    val DangerRedDark = Color(0xFFFF6B6B)
    val DangerRedContainerLight = Color(0xFFFFEBEB)
    val DangerRedContainerDark = Color(0xFF381414)

    // ---- 浅色模式背景与表面材质 (Clean Light Canvas) ----
    val BackgroundLight = Color(0xFFF4F5F8)
    val SurfaceCardLight = Color(0xFFFFFFFF)
    val SurfaceCardSubtleLight = Color(0xFFF9FAFB)
    val DividerLight = Color(0xFFE5E7EB)
    val TextPrimaryLight = Color(0xFF111827)
    val TextSecondaryLight = Color(0xFF6B7280)
    val TextTertiaryLight = Color(0xFF9CA3AF)

    // ---- 深色模式背景与表面材质 (AMOLED Deep Black) ----
    val BackgroundDark = Color(0xFF000000)
    val SurfaceCardDark = Color(0xFF16171B)
    val SurfaceCardSubtleDark = Color(0xFF1F2026)
    val DividerDark = Color(0xFF272930)
    val TextPrimaryDark = Color(0xFFF3F4F6)
    val TextSecondaryDark = Color(0xFF9CA3AF)
    val TextTertiaryDark = Color(0xFF6B7280)

    // ---- One UI 8.5 经典几何曲率 (Squircle Shapes) ----
    val IslandCardShape = RoundedCornerShape(26.dp)
    val ContainerShape = RoundedCornerShape(20.dp)
    val ControlShape = RoundedCornerShape(16.dp)
    val BadgeShape = RoundedCornerShape(10.dp)
    val PillShape = RoundedCornerShape(50)
}
