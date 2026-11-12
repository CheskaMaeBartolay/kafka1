package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val KafkaDarkColorScheme = darkColorScheme(
    primary = DarkColorSchemePrimary,
    onPrimary = DarkColorSchemeOnPrimary,
    primaryContainer = DarkColorSchemePrimaryContainer,
    onPrimaryContainer = DarkColorSchemeOnPrimaryContainer,
    secondary = CyberBlueDeep,
    onSecondary = TextPrimary,
    tertiary = EmeraldSafe,
    onTertiary = TextPrimary,
    background = DarkColorSchemeBackground,
    onBackground = DarkColorSchemeOnBackground,
    surface = DarkColorSchemeSurface,
    onSurface = DarkColorSchemeOnSurface,
    surfaceVariant = DarkColorSchemeSurfaceVariant,
    onSurfaceVariant = DarkColorSchemeOnSurfaceVariant,
    outline = DarkColorSchemeOutline,
    error = DarkColorSchemeError,
    onError = DarkColorSchemeOnError
)

@Composable
fun KafkaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KafkaDarkColorScheme,
        typography = Typography,
        content = content
    )
}

// Backward compatibility alias for any existing references
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    KafkaTheme(content = content)
}
