package com.nelson.yomukai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.google.gson.JsonParser
import com.nelson.yomukai.data.theme.Contrast
import com.nelson.yomukai.data.theme.ThemeRules
import com.nelson.yomukai.data.theme.ThemeSpec

/**
 * The same themes as the web (shared ThemeSpec): built-in ones from design/themes.json and, with a server connected,
 * the one chosen there. Colours the theme leaves out come from the design tokens.
 */
object CustomTheme {
    /** Built-in themes: id to spec, in the file's order. */
    val builtIn: List<Pair<String, ThemeSpec>> by lazy {
        runCatching {
            val text = javaClass.getResourceAsStream("/design/themes.json")!!.bufferedReader().readText()
            JsonParser.parseString(text).asJsonObject.getAsJsonArray("themes").map { it.asJsonObject["id"].asString to ThemeRules.fromJson(it.asJsonObject) }
        }.getOrDefault(emptyList())
    }

    fun parse(json: String?): ThemeSpec? = json?.let { ThemeRules.parse(it).getOrNull() }

    private fun colors(spec: ThemeSpec): Map<String, String> = DesignTokens.HEX + spec.colors

    /** A light canvas makes a light theme. */
    fun isDark(spec: ThemeSpec): Boolean = Contrast.luminance(colors(spec).getValue("canvas").take(7)) < 0.5

    private fun color(hex: String): Color {
        val h = hex.removePrefix("#")
        val rgb = h.take(6).toLong(16)
        val alpha = if (h.length == 8) h.substring(6, 8).toLong(16) else 0xFF
        return Color((alpha shl 24) or rgb)
    }

    fun colorScheme(spec: ThemeSpec): ColorScheme {
        val c = colors(spec).mapValues { color(it.value) }
        fun k(name: String) = c.getValue(name)
        val common: ColorScheme.() -> ColorScheme = {
            copy(
                primary = k("accent"), onPrimary = k("onAccent"), primaryContainer = k("accentSoft"), onPrimaryContainer = k("onAccentSoft"),
                background = k("canvas"), onBackground = k("text"), surface = k("canvas"), onSurface = k("text"),
                surfaceVariant = k("surfaceHigh"), onSurfaceVariant = k("textDim"), surfaceTint = k("accent"),
                surfaceContainerLowest = k("canvas"), surfaceContainerLow = k("surface"), surfaceContainer = k("surfaceRaised"),
                surfaceContainerHigh = k("surfaceHigh"), surfaceContainerHighest = k("surfaceHighest"), surfaceDim = k("canvas"),
                outline = k("borderStrong"), outlineVariant = k("border"), error = k("danger"), tertiary = k("warning"),
                secondary = k("accent"), onSecondary = k("onAccent"), secondaryContainer = k("accentSoft"), onSecondaryContainer = k("onAccentSoft")
            )
        }
        return (if (isDark(spec)) darkColorScheme() else lightColorScheme()).common()
    }

    fun fontFamily(spec: ThemeSpec): FontFamily = when (spec.font) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "rounded", "system" -> FontFamily.SansSerif
        else -> FontFamily.Default
    }

    /** The app's typography with the theme's font family. */
    fun typography(base: Typography, spec: ThemeSpec): Typography {
        val family = fontFamily(spec)
        fun TextStyle.f() = copy(fontFamily = family)
        return base.copy(
            displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
            headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
            titleLarge = base.titleLarge.f(), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
            bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
            labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f()
        )
    }

    /** Corner radius factor (sharp, normal, round), as on the web. */
    fun radiusFactor(spec: ThemeSpec): Float = when (spec.radius) {
        "sharp" -> 0.35f
        "round" -> 1.6f
        else -> 1f
    }
}
