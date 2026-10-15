package com.lagostransfer.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Black = Color(0xFF0A0A0A)
private val White = Color(0xFFFFFFFF)
private val Grey200 = Color(0xFFE8E8E8)
private val Grey600 = Color(0xFF6B6B6B)
private val Grey900 = Color(0xFF1A1A1A)

private val LightScheme = lightColorScheme(
    primary = Black,
    onPrimary = White,
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black,
    surfaceVariant = Grey200,
    onSurfaceVariant = Grey900,
    outline = Grey600,
    error = Color(0xFFB00020)
)

@Composable
fun LagosTransferTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightScheme, typography = Typography(), content = content)
}
