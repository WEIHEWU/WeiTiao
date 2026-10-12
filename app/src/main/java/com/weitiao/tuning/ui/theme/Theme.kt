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
    primary = Color(0xFF267AF6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0FD),
    onPrimaryContainer = Color(0xFF267AF6),
    secondary = Color(0xFF4B5563),
    onSecondary = Color.White,
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF7A818E),
    outline = Color(0xFFE2E5EC),
    error = Color(0xFFEA4D44),
    errorContainer = Color(0xFFFDECEB),
    onErrorContainer = Color(0xFFEA4D44),
)

private val M3DarkColors = m3DarkColorScheme(
    primary = Color(0xFF4D94FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF162844),
    onPrimaryContainer = Color(0xFF66A3FF),
    secondary = Color(0xFF9CA3AF),
    onSecondary = Color(0xFF1F2937),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF17191C),
    onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF22252A),
    onSurfaceVariant = Color(0xFF8C93A4),
    outline = Color(0xFF2E3238),
    error = Color(0xFFFF6B63),
    errorContainer = Color(0xFF331818),
    onErrorContainer = Color(0xFFFFB4AB),
)

@Composable
fun CscChangerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val miuixColors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF4D94FF),
            surface = Color(0xFF0B0C0E),
            surfaceContainer = Color(0xFF17191C),
            surfaceContainerHigh = Color(0xFF1F2226),
            surfaceContainerHighest = Color(0xFF282C31),
            secondaryContainer = Color(0xFF22252A),
            tertiaryContainer = Color(0xFF162844),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF267AF6),
            surface = Color(0xFFF6F7F9),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFF0F2F6),
            surfaceContainerHighest = Color(0xFFE6E9EF),
            secondaryContainer = Color(0xFFF0F2F6),
            tertiaryContainer = Color(0xFFE7F0FD),
        )
    }
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
