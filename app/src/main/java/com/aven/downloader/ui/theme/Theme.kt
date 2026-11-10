package com.aven.downloader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Navy = Color(0xFF0B0F1A)
val NavySurface = Color(0xFF131A2B)
val NavyHigh = Color(0xFF1B2440)
val Gold = Color(0xFFD4AF37)
val GoldSoft = Color(0xFFF0D77A)
val TextPrimary = Color(0xFFEAECF2)
val TextMuted = Color(0xFF9AA3BC)
val ErrorRed = Color(0xFFFF6B6B)
val SuccessGreen = Color(0xFF5BD68A)

private val AvenColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF1A1400),
    primaryContainer = Color(0xFF3A3010),
    onPrimaryContainer = GoldSoft,
    secondary = GoldSoft,
    onSecondary = Color(0xFF1A1400),
    background = Navy,
    onBackground = TextPrimary,
    surface = NavySurface,
    onSurface = TextPrimary,
    surfaceVariant = NavyHigh,
    onSurfaceVariant = TextMuted,
    surfaceContainer = NavySurface,
    surfaceContainerHigh = NavyHigh,
    outline = Color(0xFF3A4260),
    outlineVariant = Color(0xFF2A3350),
    error = ErrorRed,
    onError = Color(0xFF330000),
    errorContainer = Color(0xFF4A1A1A),
    onErrorContainer = Color(0xFFFFD6D6)
)

private val AvenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AvenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AvenColors, shapes = AvenShapes, content = content)
}
