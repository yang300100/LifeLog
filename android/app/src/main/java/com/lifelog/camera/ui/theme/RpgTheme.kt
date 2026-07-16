package com.lifelog.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RpgColorScheme = darkColorScheme(
    primary = RpgAccent,
    onPrimary = Color.White,
    secondary = RpgExpColor,
    tertiary = RpgNewColor,
    background = RpgBackground,
    onBackground = RpgOnSurface,
    surface = RpgSurface,
    onSurface = RpgOnSurface,
    surfaceVariant = RpgSurfaceVariant,
    onSurfaceVariant = RpgOnSurfaceVariant,
    error = RpgHpColor,
    outline = RpgOnSurfaceVariant
)

@Composable
fun RpgTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RpgColorScheme,
        content = content
    )
}
