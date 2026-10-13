package com.oamsapps.productsnap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Swap these for tokens from your shared Compose design system once it's ready.
private val Ink = Color(0xFF1F2A44)
private val Marigold = Color(0xFFE9A23B)
private val Studio = Color(0xFFF7F8FA)
private val Slate = Color(0xFF5B6475)

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Marigold,
    onSecondary = Ink,
    background = Studio,
    surface = Color.White,
    onSurfaceVariant = Slate,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB9C6E8),
    onPrimary = Ink,
    secondary = Marigold,
    onSecondary = Ink,
)

@Composable
fun ProductSnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
