package com.weitiao.tuning.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private val M3LightColors = m3LightColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FF),
    onPrimaryContainer = Color(0xFF3482FF),
    secondary = Color(0xFF4B5563),
    onSecondary = Color.White,
    background = Color(0xFFF7F7F7),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFFE0E0E0),
    error = Color(0xFFE94634),
    errorContainer = Color(0xFFFDF6F4),
    onErrorContainer = Color(0xFFE94634),
)

private val M3DarkColors = m3DarkColorScheme(
    primary = Color(0xFF277AF7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B3B54),
    onPrimaryContainer = Color(0xFF4788FF),
    secondary = Color(0xFF9CA3AF),
    onSecondary = Color(0xFF1F2937),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFF959595),
    outline = Color(0xFF393939),
    error = Color(0xFFF12522),
    errorContainer = Color(0xFF2E0603),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun CscChangerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val miuixColors = if (darkTheme) darkColorScheme() else lightColorScheme()
    val m3Colors = if (darkTheme) M3DarkColors else M3LightColors

    MiuixTheme(
        colors = miuixColors,
        smoothRounding = true,
    ) {
        MaterialTheme(
            colorScheme = m3Colors,
            content = content,
        )
    }
}
