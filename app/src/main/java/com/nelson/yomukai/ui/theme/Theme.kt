package com.nelson.yomukai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = InkCoral,
    onPrimary = InkNight,
    primaryContainer = Color(0xFF5A2430),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = InkLilac,
    onSecondary = InkNight,
    secondaryContainer = Color(0xFF33304F),
    onSecondaryContainer = Color(0xFFE2DFFF),
    tertiary = InkGold,
    background = InkNight,
    onBackground = Color(0xFFECEAF2),
    surface = InkNight,
    onSurface = Color(0xFFECEAF2),
    surfaceVariant = InkPanel,
    onSurfaceVariant = Color(0xFFB3B0C2),
    surfaceContainerLowest = Color(0xFF0C0C13),
    surfaceContainerLow = Color(0xFF16161F),
    surfaceContainer = Color(0xFF1B1B26),
    surfaceContainerHigh = InkPanel,
    surfaceContainerHighest = Color(0xFF2B2B3A),
    outline = Color(0xFF55536A),
    outlineVariant = Color(0xFF34333F)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFC63A4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDE1),
    onPrimaryContainer = Color(0xFF3F0010),
    secondary = Color(0xFF5B55A0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E1FF),
    onSecondaryContainer = Color(0xFF17124F),
    tertiary = Color(0xFF8A6100),
    background = Color(0xFFFBF8FB),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFBF8FB),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFECE8EF),
    onSurfaceVariant = Color(0xFF5B5868),
    surfaceContainerLow = Color(0xFFF6F2F6),
    surfaceContainer = Color(0xFFF1EDF2),
    surfaceContainerHigh = Color(0xFFEAE6EC),
    outline = Color(0xFF8B8898),
    outlineVariant = Color(0xFFD7D3DD)
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