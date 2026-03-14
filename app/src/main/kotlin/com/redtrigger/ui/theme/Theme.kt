package com.redtrigger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// RedMagic red accent color
private val RedMagicRed = Color(0xFFFF1744)
private val RedMagicRedDark = Color(0xFFC4001D)
private val RedMagicRedLight = Color(0xFFFF5177)

private val DarkColorScheme = darkColorScheme(
    primary = RedMagicRed,
    onPrimary = Color.White,
    primaryContainer = RedMagicRedDark,
    onPrimaryContainer = Color.White,
    secondary = RedMagicRedLight,
    onSecondary = Color.Black,
    tertiary = Color(0xFF424242),
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFE0E0E0),
    error = Color(0xFFCF6679),
    onError = Color.Black
)

@Composable
fun RedTriggerTheme(
    content: @Composable () -> Unit
) {
    // Always dark theme for gaming aesthetic
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
