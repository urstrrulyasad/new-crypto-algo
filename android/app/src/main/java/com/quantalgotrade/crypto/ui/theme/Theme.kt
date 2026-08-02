package com.quantalgotrade.crypto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0F766E)
private val TealDark = Color(0xFF042F2E)
private val Mint = Color(0xFF99F6E4)
private val Ink = Color(0xFF0F172A)
private val Paper = Color(0xFFF8FAFC)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = TealDark,
    background = Paper,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = TealDark,
    secondary = Teal,
    background = TealDark,
    surface = Color(0xFF0B3B39),
    onBackground = Paper,
    onSurface = Paper,
)

@Composable
fun QuantAlgoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
