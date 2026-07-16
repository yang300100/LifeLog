package com.lifelog.camera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PeachLightColorScheme = lightColorScheme(
    primary = PeachPrimary,
    onPrimary = PeachOnPrimary,
    primaryContainer = PeachPrimaryContainer,
    onPrimaryContainer = PeachOnPrimaryContainer,
    secondary = PeachSecondary,
    onSecondary = PeachOnSecondary,
    secondaryContainer = PeachSecondaryContainer,
    onSecondaryContainer = PeachOnSecondaryContainer,
    tertiary = PeachTertiary,
    onTertiary = PeachOnTertiary,
    tertiaryContainer = PeachTertiaryContainer,
    onTertiaryContainer = PeachOnTertiaryContainer,
    background = PeachBackground,
    onBackground = PeachOnBackground,
    surface = PeachSurface,
    onSurface = PeachOnSurface,
    surfaceVariant = PeachSurfaceVariant,
    onSurfaceVariant = PeachOnSurfaceVariant,
    error = PeachError,
    onError = PeachOnError,
    errorContainer = PeachErrorContainer,
    onErrorContainer = PeachOnErrorContainer,
    outline = PeachOutline,
    outlineVariant = PeachOutlineVariant
)

private val PeachDarkColorScheme = darkColorScheme(
    primary = PeachDarkPrimary,
    onPrimary = PeachDarkOnPrimary,
    primaryContainer = PeachDarkPrimaryContainer,
    onPrimaryContainer = PeachDarkOnPrimaryContainer,
    secondary = PeachDarkSecondary,
    onSecondary = PeachDarkOnSecondary,
    secondaryContainer = PeachDarkSecondaryContainer,
    onSecondaryContainer = PeachDarkOnSecondaryContainer,
    tertiary = PeachDarkTertiary,
    onTertiary = PeachDarkOnTertiary,
    tertiaryContainer = PeachDarkTertiaryContainer,
    onTertiaryContainer = PeachDarkOnTertiaryContainer,
    background = PeachDarkBackground,
    onBackground = PeachDarkOnBackground,
    surface = PeachDarkSurface,
    onSurface = PeachDarkOnSurface,
    surfaceVariant = PeachDarkSurfaceVariant,
    onSurfaceVariant = PeachDarkOnSurfaceVariant,
    error = PeachDarkError,
    onError = PeachDarkOnError,
    errorContainer = PeachDarkErrorContainer,
    onErrorContainer = PeachDarkOnErrorContainer,
    outline = PeachDarkOutline,
    outlineVariant = PeachDarkOutlineVariant
)

@Composable
fun LifeLogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PeachDarkColorScheme else PeachLightColorScheme,
        typography = PeachTypography,
        shapes = PeachShapes,
        content = content
    )
}
