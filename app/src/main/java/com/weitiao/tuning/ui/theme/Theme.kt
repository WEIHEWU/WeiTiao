package com.weitiao.tuning.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val OneUiLightColors = lightColorScheme(
    primary = OneUiTokens.SamsungBlue,
    onPrimary = Color.White,
    primaryContainer = OneUiTokens.SamsungBlueContainerLight,
    onPrimaryContainer = OneUiTokens.SamsungBlue,
    secondary = Color(0xFF4B5563),
    onSecondary = Color.White,
    background = OneUiTokens.BackgroundLight,
    onBackground = OneUiTokens.TextPrimaryLight,
    surface = OneUiTokens.SurfaceCardLight,
    onSurface = OneUiTokens.TextPrimaryLight,
    surfaceVariant = OneUiTokens.SurfaceCardSubtleLight,
    onSurfaceVariant = OneUiTokens.TextSecondaryLight,
    outline = OneUiTokens.DividerLight,
    error = OneUiTokens.DangerRed,
    errorContainer = OneUiTokens.DangerRedContainerLight,
    onErrorContainer = OneUiTokens.DangerRed,
)

private val OneUiDarkColors = darkColorScheme(
    primary = OneUiTokens.SamsungBlueDark,
    onPrimary = Color(0xFF002F65),
    primaryContainer = OneUiTokens.SamsungBlueContainerDark,
    onPrimaryContainer = OneUiTokens.SamsungBlueDark,
    secondary = Color(0xFF9CA3AF),
    onSecondary = Color(0xFF1F2937),
    background = OneUiTokens.BackgroundDark,
    onBackground = OneUiTokens.TextPrimaryDark,
    surface = OneUiTokens.SurfaceCardDark,
    onSurface = OneUiTokens.TextPrimaryDark,
    surfaceVariant = OneUiTokens.SurfaceCardSubtleDark,
    onSurfaceVariant = OneUiTokens.TextSecondaryDark,
    outline = OneUiTokens.DividerDark,
    error = OneUiTokens.DangerRedDark,
    errorContainer = OneUiTokens.DangerRedContainerDark,
    onErrorContainer = OneUiTokens.DangerRedDark,
)

@Composable
fun CscChangerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // 默认使用 Samsung One UI 官方精调色彩，确保最纯正的 Galaxy 质感
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (darkTheme) {
                // 确保在深色模式下即使使用动态取色，也保持 AMOLED 纯黑底色
                dynamic.copy(
                    background = OneUiTokens.BackgroundDark,
                    surface = OneUiTokens.SurfaceCardDark,
                )
            } else dynamic
        }
        darkTheme -> OneUiDarkColors
        else -> OneUiLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
