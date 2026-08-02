package com.quantalgotrade.crypto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0F766E)
private val TealDark = Color(0xFF042F2E)
private val Mint = Color(0xFF99F6E4)
private val Paper = Color(0xFFF0FDFA)
private val Ink = Color(0xFF0F172A)

private val AppColors = darkColorScheme(
    primary = Mint,
    onPrimary = TealDark,
    secondary = Teal,
    background = Color(0xFF031A19),
    surface = Color(0xFF0B3B39),
    surfaceVariant = Color(0xFF134E4A),
    onBackground = Paper,
    onSurface = Paper,
    onSurfaceVariant = Color(0xFFCCFBF1),
    error = Color(0xFFFCA5A5),
)

@Composable
fun QuantAlgoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        content = content,
    )
}
