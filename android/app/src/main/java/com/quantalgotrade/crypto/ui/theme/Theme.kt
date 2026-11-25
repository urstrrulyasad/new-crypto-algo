package com.quantalgotrade.crypto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object QuantColors {
    val Canvas = Color(0xFF09090D)
    val Surface = Color(0xFF11131B)
    val SurfaceElevated = Color(0xFF171A24)
    val Hairline = Color(0xFF262A3B)
    val Gold = Color(0xFFF59E0B)
    val GoldBright = Color(0xFFFBBF24)
    // Primary desk highlight alias retained so all screens share the Gold/Onyx palette
    val Cyan = GoldBright
    val Emerald = Color(0xFF10B981)
    val Amber = Color(0xFFF59E0B)
    val Rose = Color(0xFFF43F5E)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextMuted = Color(0xFF94A3B8)
}

val AppDarkColors = darkColorScheme(
    primary = QuantColors.Gold,
    onPrimary = QuantColors.Canvas,
    secondary = QuantColors.Emerald,
    onSecondary = QuantColors.Canvas,
    tertiary = QuantColors.GoldBright,
    background = QuantColors.Canvas,
    onBackground = QuantColors.TextPrimary,
    surface = QuantColors.Surface,
    onSurface = QuantColors.TextPrimary,
    surfaceVariant = QuantColors.SurfaceElevated,
    onSurfaceVariant = QuantColors.TextMuted,
    outline = QuantColors.Hairline,
    error = QuantColors.Rose,
    onError = QuantColors.Canvas,
    errorContainer = Color(0xFF3F1221),
    onErrorContainer = Color(0xFFFECDD3),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        fontFeatureSettings = "tnum",
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        fontFeatureSettings = "tnum",
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum",
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = QuantColors.TextMuted,
        fontFeatureSettings = "tnum",
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        fontFeatureSettings = "tnum",
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
