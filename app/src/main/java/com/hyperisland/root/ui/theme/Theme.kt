package com.hyperisland.root.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Soft, muted palette — desaturated indigo/lavender accent instead of the
// stock Material purple, calmer neutrals for surfaces and dividers.
private val LightColors = lightColorScheme(
    primary = Color(0xFF5B6BD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E6FB),
    onPrimaryContainer = Color(0xFF232B6B),
    secondary = Color(0xFF6C7684),
    secondaryContainer = Color(0xFFEDEFF5),
    onSecondaryContainer = Color(0xFF3A4250),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF201F26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF201F26),
    surfaceVariant = Color(0xFFF0F0F5),
    onSurfaceVariant = Color(0xFF6E6E78),
    outline = Color(0xFFDEDEE6),
    outlineVariant = Color(0xFFEAEAF0),
    error = Color(0xFFC5554A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEB6F5),
    onPrimary = Color(0xFF232B6B),
    primaryContainer = Color(0xFF3A4290),
    onPrimaryContainer = Color(0xFFE4E6FB),
    secondary = Color(0xFFB5BCC8),
    secondaryContainer = Color(0xFF32353D),
    onSecondaryContainer = Color(0xFFD8DCE4),
    background = Color(0xFF17171B),
    onBackground = Color(0xFFE6E5EA),
    surface = Color(0xFF1E1E23),
    onSurface = Color(0xFFE6E5EA),
    surfaceVariant = Color(0xFF29292F),
    onSurfaceVariant = Color(0xFFA8A8B3),
    outline = Color(0xFF3A3A42),
    outlineVariant = Color(0xFF2C2C33),
    error = Color(0xFFE1938A)
)

// Rounder corners everywhere for a softer, less boxy feel.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun HyperIslandTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
