package com.aven.downloader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aven.downloader.AvenApp
import com.aven.downloader.data.Settings
import androidx.compose.runtime.collectAsState

/** Holds whether the dark palette is active, so the accent colours below follow the theme everywhere (also in Canvas code). */
object ThemeState {
    var dark by mutableStateOf(true)
}

// accent colours: brighter gold on dark, deeper amber on light (readable on white glass)
val Gold: Color get() = if (ThemeState.dark) Color(0xFFE2B93B) else Color(0xFF9C6B00)
val GoldSoft: Color get() = if (ThemeState.dark) Color(0xFFF4DC8E) else Color(0xFFD9A62E)
val ErrorRed: Color get() = if (ThemeState.dark) Color(0xFFFF7B7B) else Color(0xFFC62828)
val SuccessGreen: Color get() = if (ThemeState.dark) Color(0xFF5BD68A) else Color(0xFF1E8E4E)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE2B93B),
    onPrimary = Color(0xFF1A1400),
    primaryContainer = Color(0xFF3A3010),
    onPrimaryContainer = Color(0xFFF4DC8E),
    secondary = Color(0xFFF4DC8E),
    onSecondary = Color(0xFF1A1400),
    background = Color(0xFF080B16),
    onBackground = Color(0xFFEEF0F7),
    surface = Color(0xFF121827),
    onSurface = Color(0xFFEEF0F7),
    surfaceVariant = Color(0xFF1B2338),
    onSurfaceVariant = Color(0xFFA3ACC4),
    surfaceContainer = Color(0xFF121827),
    surfaceContainerHigh = Color(0xFF1B2338),
    outline = Color(0xFF3C4563),
    outlineVariant = Color(0xFF2A3350),
    error = Color(0xFFFF7B7B),
    onError = Color(0xFF330000),
    errorContainer = Color(0xFF4A1A1A),
    onErrorContainer = Color(0xFFFFD6D6)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF9C6B00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE9B0),
    onPrimaryContainer = Color(0xFF3A2800),
    secondary = Color(0xFFD9A62E),
    onSecondary = Color(0xFF1A1400),
    background = Color(0xFFEFF2F9),
    onBackground = Color(0xFF111522),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111522),
    surfaceVariant = Color(0xFFE6EAF4),
    onSurfaceVariant = Color(0xFF545C73),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE6EAF4),
    outline = Color(0xFFB5BCD0),
    outlineVariant = Color(0xFFD3D8E6),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val AvenShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

/** Dark or light, following Settings → Theme (system / light / dark). */
@Composable
fun rememberDarkTheme(): Boolean {
    val mode by AvenApp.instance.settings.flow.collectAsState(initial = Settings())
    val system = isSystemInDarkTheme()
    return when (mode.themeMode) { "light" -> false; "dark" -> true; else -> system }
}

@Composable
fun AvenTheme(content: @Composable () -> Unit) {
    val dark = rememberDarkTheme()
    if (ThemeState.dark != dark) ThemeState.dark = dark
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = AvenShapes, content = content)
}
