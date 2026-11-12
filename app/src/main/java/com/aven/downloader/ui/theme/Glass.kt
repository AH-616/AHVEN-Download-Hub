package com.aven.downloader.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.CardColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Glass tints: translucent white (dark) / white (light), with a bright edge, like frosted iOS panels. */
object GlassColors {
    val fill: Color get() = if (ThemeState.dark) Color(0x24FFFFFF) else Color(0xB8FFFFFF)
    val fillStrong: Color get() = if (ThemeState.dark) Color(0x38FFFFFF) else Color(0xD9FFFFFF)
    val edgeTop: Color get() = if (ThemeState.dark) Color(0x66FFFFFF) else Color(0xFFFFFFFF)
    val edgeBottom: Color get() = if (ThemeState.dark) Color(0x14FFFFFF) else Color(0x40B5BCD0)
}

/** Soft colourful backdrop that the glass panels sit on. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable BoxScope.() -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val dark = ThemeState.dark
    Box(
        modifier
            .background(bg)
            .drawBehind {
                val w = size.width; val h = size.height
                fun blob(c: Color, cx: Float, cy: Float, r: Float) =
                    drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
                if (dark) {
                    blob(Color(0x55E2B93B), w * 0.1f, h * 0.05f, w * 0.9f)
                    blob(Color(0x553D5AFE), w * 0.95f, h * 0.35f, w * 1.0f)
                    blob(Color(0x44B23AE0), w * 0.2f, h * 0.9f, w * 1.0f)
                } else {
                    blob(Color(0x66FFC857), w * 0.1f, h * 0.05f, w * 0.9f)
                    blob(Color(0x5590B4FF), w * 0.95f, h * 0.35f, w * 1.0f)
                    blob(Color(0x55F5A3E6), w * 0.2f, h * 0.9f, w * 1.0f)
                }
            },
        content = content
    )
}

fun glassBorder(): BorderStroke = BorderStroke(1.dp, Brush.verticalGradient(listOf(GlassColors.edgeTop, GlassColors.edgeBottom)))

/** A frosted panel. Drop-in for Card (the `colors` parameter is only used for non-default tints such as errors). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    colors: CardColors? = null,
    shape: Shape = RoundedCornerShape(26.dp),
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val tint = colors?.containerColor?.takeIf { it != scheme.surface && it != scheme.surfaceVariant && it != Color.Unspecified }
    val fill = tint?.copy(alpha = 0.85f) ?: if (strong) GlassColors.fillStrong else GlassColors.fill
    val m = modifier
        .shadow(if (ThemeState.dark) 0.dp else 6.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x22000000))
        .clip(shape)
        .background(fill)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .drawBehind { }
    androidx.compose.material3.Surface(
        modifier = m, shape = shape, color = Color.Transparent, border = glassBorder()
    ) { Column(content = content) }
}

@Composable
fun glassFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = GlassColors.fill,
    unfocusedContainerColor = GlassColors.fill,
    focusedBorderColor = Gold,
    unfocusedBorderColor = GlassColors.edgeTop.copy(alpha = 0.5f)
)
