package com.nelson.yomukai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Dark is the main look and comes from the design tokens shared with the web (DesignTokens.kt).
private val DarkColorScheme = darkColorScheme(
    primary = DesignTokens.accent,
    onPrimary = DesignTokens.onAccent,
    primaryContainer = DesignTokens.accentSoft,
    onPrimaryContainer = DesignTokens.onAccentSoft,
    // Black and white with one accent (ESTILO.md): «secondary» is the ink itself, «tertiary» the same ember.
    inversePrimary = DesignTokens.accent,
    secondary = DesignTokens.ink,
    onSecondary = DesignTokens.canvas,
    secondaryContainer = DesignTokens.surfaceHigh,
    onSecondaryContainer = DesignTokens.text,
    tertiary = DesignTokens.accent,
    onTertiary = DesignTokens.onAccent,
    tertiaryContainer = DesignTokens.accentSoft,
    onTertiaryContainer = DesignTokens.onAccentSoft,
    error = DesignTokens.danger,
    onError = Color(0xFF2A0000),
    background = DesignTokens.canvas,
    onBackground = DesignTokens.text,
    surface = DesignTokens.canvas,
    onSurface = DesignTokens.text,
    surfaceVariant = DesignTokens.surfaceHigh,
    onSurfaceVariant = DesignTokens.textDim,
    surfaceTint = DesignTokens.accent,
    surfaceContainerLowest = DesignTokens.canvas,
    surfaceContainerLow = DesignTokens.surface,
    surfaceContainer = DesignTokens.surfaceRaised,
    surfaceContainerHigh = DesignTokens.surfaceHigh,
    surfaceContainerHighest = DesignTokens.surfaceHighest,
    surfaceBright = DesignTokens.surfaceHighest,
    surfaceDim = DesignTokens.canvas,
    outline = DesignTokens.borderStrong,
    outlineVariant = DesignTokens.border,
    scrim = DesignTokens.scrim
)

private val LightColorScheme = lightColorScheme(
    primary = EmberDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF3A1100),
    inversePrimary = Ember,
    secondary = SlateDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE5EF),
    onSecondaryContainer = Color(0xFF16202C),
    tertiary = GoldDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBEBC1),
    onTertiaryContainer = Color(0xFF2B2000),
    background = Color(0xFFFAFAF9),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFFAFAF9),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFEDEDEF),
    onSurfaceVariant = Color(0xFF55555F),
    surfaceTint = EmberDeep,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F4F5),
    surfaceContainer = Color(0xFFEFEFF1),
    surfaceContainerHigh = Color(0xFFE9E9EC),
    surfaceContainerHighest = Color(0xFFE3E3E7),
    outline = Color(0xFF8A8A94),
    outlineVariant = Color(0xFFD6D6DC)
)

@Composable
fun YomukaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    /** A shared theme (built-in or from the server): it decides colours, font and corners. */
    custom: com.nelson.yomukai.data.theme.ThemeSpec? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        custom != null -> CustomTheme.colorScheme(custom)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = custom?.let { CustomTheme.typography(Typography, it) } ?: Typography,
        shapes = Shapes(
            // Nearly square corners, as panels of a manga page.
            extraSmall = RoundedCornerShape(2.dp * (custom?.let(CustomTheme::radiusFactor) ?: 1f)),
            small = RoundedCornerShape(4.dp * (custom?.let(CustomTheme::radiusFactor) ?: 1f)),
            medium = RoundedCornerShape(DesignTokens.radiusSm * (custom?.let(CustomTheme::radiusFactor) ?: 1f)),
            large = RoundedCornerShape(DesignTokens.radiusMd * (custom?.let(CustomTheme::radiusFactor) ?: 1f)),
            extraLarge = RoundedCornerShape(DesignTokens.radiusLg * (custom?.let(CustomTheme::radiusFactor) ?: 1f))
        ),
        content = content
    )
}
