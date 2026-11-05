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

private val DarkColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF1F0C03),
    primaryContainer = Color(0xFF3A1C0E),
    onPrimaryContainer = Color(0xFFFFDBCB),
    inversePrimary = EmberDeep,
    secondary = Slate,
    onSecondary = Color(0xFF0E1620),
    secondaryContainer = Color(0xFF1D242E),
    onSecondaryContainer = Color(0xFFD5E1EE),
    tertiary = Gold,
    onTertiary = Color(0xFF241A00),
    tertiaryContainer = Color(0xFF332910),
    onTertiaryContainer = Color(0xFFF6E3B0),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
    background = Sumi,
    onBackground = Paper,
    surface = Sumi,
    onSurface = Paper,
    surfaceVariant = SumiVariant,
    onSurfaceVariant = PaperDim,
    surfaceTint = Ember,
    surfaceContainerLowest = Sumi,
    surfaceContainerLow = SumiLow,
    surfaceContainer = SumiMid,
    surfaceContainerHigh = SumiHigh,
    surfaceContainerHighest = SumiHighest,
    surfaceBright = Color(0xFF2A2A30),
    surfaceDim = Sumi,
    outline = Color(0xFF45454D),
    outlineVariant = Color(0xFF2A2A30),
    scrim = Color.Black
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
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
