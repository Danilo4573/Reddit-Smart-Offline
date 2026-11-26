package com.smartoffline.reddit.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartoffline.reddit.data.ThemeMode

val Orange = Color(0xFFFF4500)
val OrangeSoft = Color(0xFFFF8A5C)
val Success = Color(0xFF3DDC84)
val Warn = Color(0xFFFFB020)
val Danger = Color(0xFFFF5A5F)

private val Dark = darkColorScheme(
    primary = Orange, onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1A0D), onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = OrangeSoft, onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2B2F), onSecondaryContainer = Color(0xFFE6E6EA),
    background = Color(0xFF0E0F11), onBackground = Color(0xFFECECEF),
    surface = Color(0xFF0E0F11), onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF1F2024), onSurfaceVariant = Color(0xFFA9AAB2),
    surfaceContainerLowest = Color(0xFF0A0B0C), surfaceContainerLow = Color(0xFF141518),
    surfaceContainer = Color(0xFF17181B), surfaceContainerHigh = Color(0xFF1D1E22),
    surfaceContainerHighest = Color(0xFF25262B),
    outline = Color(0xFF3A3B41), outlineVariant = Color(0xFF2A2B30),
    error = Danger,
)

private val Light = lightColorScheme(
    primary = Orange, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D8), onPrimaryContainer = Color(0xFF3A1200),
    secondary = Color(0xFFB8471C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEEF2), onSecondaryContainer = Color(0xFF1B1C20),
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF15161A),
    surface = Color(0xFFF6F7F9), onSurface = Color(0xFF15161A),
    surfaceVariant = Color(0xFFE7E8EC), onSurfaceVariant = Color(0xFF5B5D66),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF0F1F4),
    surfaceContainerHighest = Color(0xFFE8E9ED),
    outline = Color(0xFFC9CAD1), outlineVariant = Color(0xFFE1E2E7),
    error = Color(0xFFD93036),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    )
}

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)),
        content = content,
    )
}
