package com.nelson.yomukai.data.theme

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A theme overrides the design tokens (shared/src/main/resources/design/tokens.json): colours by token name, a font
 * from a fixed list, corner radius, density, an optional background image (uploaded to the server) and whether the
 * accent follows each title's cover. Never free CSS: only these validated values, applied the same on web and app.
 */
data class ThemeSpec(
    val name: String,
    val colors: Map<String, String>,
    val font: String = "inter",
    val radius: String = "normal",
    val density: String = "normal",
    /** Id of an uploaded background (served by the server), or null. */
    val background: Long? = null,
    val coverAccent: Boolean = true
)

object ThemeRules {
    /** Colour tokens a theme may set (the same names as tokens.json › color). */
    val COLOR_KEYS = listOf(
        "canvas", "surface", "surfaceRaised", "surfaceHigh", "surfaceHighest", "border", "borderStrong",
        "text", "textDim", "textFaint", "accent", "onAccent", "accentSoft", "onAccentSoft", "info", "success", "warning", "danger",
        // «Tinta y viñeta» (docs/referencias/ESTILO.md): the ink line, the two other accents and one colour per kind of work.
        "ink", "accent2", "onAccent2", "accent3", "typeAnime", "typeManga", "typeManhwa", "typeComic", "typeNovel", "onType"
    )
    val FONTS = listOf("inter", "system", "serif", "rounded", "mono")
    val RADII = listOf("sharp", "normal", "round")
    val DENSITIES = listOf("compact", "normal", "comfy")
    private val HEX = Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")
    const val MAX_NAME = 40

    /** Parsed and checked, or the list of what is wrong (in Spanish, for the editor). */
    fun parse(json: String): Result<ThemeSpec> = runCatching {
        require(json.length <= 8_000) { "El tema es demasiado grande" }
        val o = JsonParser.parseString(json).asJsonObject
        fromJson(o)
    }

    fun fromJson(o: JsonObject): ThemeSpec {
        val errors = mutableListOf<String>()
        val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME) errors += "El nombre debe tener entre 1 y $MAX_NAME caracteres"
        val colors = LinkedHashMap<String, String>()
        o.getAsJsonObject("colors")?.entrySet()?.forEach { (k, v) ->
            val value = v.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            when {
                k !in COLOR_KEYS -> errors += "Color desconocido: $k"
                !HEX.matches(value) -> errors += "$k debe ser un color #rrggbb"
                else -> colors[k] = value.lowercase()
            }
        }
        fun pick(key: String, allowed: List<String>, default: String): String {
            val v = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: return default
            if (v !in allowed) errors += "$key debe ser uno de: ${allowed.joinToString()}"
            return v
        }
        val font = pick("font", FONTS, "inter")
        val radius = pick("radius", RADII, "normal")
        val density = pick("density", DENSITIES, "normal")
        val background = o.get("background")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.takeIf { it > 0 }
        val coverAccent = o.get("coverAccent")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        require(errors.isEmpty()) { errors.joinToString(". ") }
        return ThemeSpec(name, colors, font, radius, density, background, coverAccent)
    }

    fun toJson(t: ThemeSpec): JsonObject = JsonObject().apply {
        addProperty("name", t.name)
        add("colors", JsonObject().apply { t.colors.forEach { (k, v) -> addProperty(k, v) } })
        addProperty("font", t.font)
        addProperty("radius", t.radius)
        addProperty("density", t.density)
        t.background?.let { addProperty("background", it) }
        addProperty("coverAccent", t.coverAccent)
    }
}

/** WCAG 2.x contrast, to warn about themes that are hard to read. */
object Contrast {
    private fun channel(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

    fun luminance(hex: String): Double {
        val h = hex.removePrefix("#")
        val r = h.substring(0, 2).toInt(16)
        val g = h.substring(2, 4).toInt(16)
        val b = h.substring(4, 6).toInt(16)
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun ratio(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    data class Issue(val foreground: String, val background: String, val ratio: Double, val needed: Double)

    /** Pairs that people actually read, with what each needs (AA: 4.5 for text, 3 for big or secondary text). */
    private val PAIRS = listOf(
        Triple("text", "canvas", 4.5), Triple("text", "surface", 4.5), Triple("text", "surfaceHigh", 4.5),
        Triple("textDim", "canvas", 4.5), Triple("textDim", "surface", 3.0), Triple("textFaint", "canvas", 3.0),
        Triple("onAccent", "accent", 4.5), Triple("onAccentSoft", "accentSoft", 4.5), Triple("accent", "canvas", 3.0),
        // Neobrutalism: the ink line (borders, hard shadows) must stand out from the canvas, and text on the yellow read.
        Triple("ink", "canvas", 3.0), Triple("onAccent2", "accent2", 4.5)
    )

    /** Hard-to-read pairs of [theme] over [base] (the default tokens for whatever the theme leaves out). */
    fun issues(theme: ThemeSpec, base: Map<String, String>): List<Issue> {
        val colors = base + theme.colors
        return PAIRS.mapNotNull { (fg, bg, needed) ->
            val a = colors[fg]?.take(7) ?: return@mapNotNull null
            val b = colors[bg]?.take(7) ?: return@mapNotNull null
            val r = ratio(a, b)
            if (r + 1e-9 < needed) Issue(fg, bg, (r * 100).toInt() / 100.0, needed) else null
        }
    }
}
