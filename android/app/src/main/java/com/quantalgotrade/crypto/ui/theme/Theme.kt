package com.quantalgotrade.crypto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Cyan = Color(0xFF22D3EE)
private val Mint = Color(0xFF34D399)
private val Ink = Color(0xFF05070D)
private val Surface = Color(0xFF0E1422)
private val SurfaceHi = Color(0xFF151C2E)
private val Edge = Color(0xFF1A2336)
private val Paper = Color(0xFFE2E8F0)
private val Muted = Color(0xFF94A3B8)

val AppDarkColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink,
    secondary = Mint,
    onSecondary = Ink,
    tertiary = Color(0xFF67E8F9),
    background = Ink,
    onBackground = Paper,
    surface = Surface,
    onSurface = Paper,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = Muted,
    outline = Edge,
    error = Color(0xFFFB7185),
    onError = Ink,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = Muted,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun QuantAlgoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppDarkColors,
        typography = AppTypography,
        content = content,
    )
}
