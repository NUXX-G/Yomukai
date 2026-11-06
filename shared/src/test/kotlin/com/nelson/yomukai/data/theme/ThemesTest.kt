package com.nelson.yomukai.data.theme

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    private fun resource(path: String) = javaClass.getResourceAsStream(path)!!.bufferedReader().readText()
    private val base: Map<String, String> = JsonParser.parseString(resource("/design/tokens.json")).asJsonObject
        .getAsJsonObject("color").entrySet().associate { it.key to it.value.asString }

    @Test
    fun wcagRatiosMatchTheStandard() {
        assertEquals(21.0, Contrast.ratio("#000000", "#ffffff"), 1e-6)
        assertEquals(1.0, Contrast.ratio("#777777", "#777777"), 1e-6)
        assertEquals(4.48, Contrast.ratio("#777777", "#ffffff"), 0.01)
    }

    @Test
    fun everyBuiltInThemeIsValidAndReadable() {
        val themes = JsonParser.parseString(resource("/design/themes.json")).asJsonObject.getAsJsonArray("themes")
        assertTrue(themes.size() >= 7)
        for (t in themes) {
            val spec = ThemeRules.fromJson(t.asJsonObject)
            assertEquals("${spec.name}: ${Contrast.issues(spec, base)}", emptyList<Contrast.Issue>(), Contrast.issues(spec, base))
        }
        // The default tokens themselves are readable too.
        assertTrue(Contrast.issues(ThemeSpec("x", emptyMap()), base).isEmpty())
    }

    @Test
    fun onlyKnownTokensAndPlainColoursAreAccepted() {
        assertTrue(ThemeRules.parse("""{"name":"Ok","colors":{"accent":"#FF8A5B"},"font":"serif"}""").isSuccess)
        assertEquals("#ff8a5b", ThemeRules.parse("""{"name":"Ok","colors":{"accent":"#FF8A5B"}}""").getOrThrow().colors["accent"])
        val bad = listOf(
            """{"name":"","colors":{}}""",
            """{"name":"X","colors":{"accent":"red; background:url(x)"}}""",
            """{"name":"X","colors":{"--evil":"#000000"}}""",
            """{"name":"X","colors":{},"font":"Comic Sans"}""",
            """{"name":"X","colors":{},"radius":"999px"}"""
        )
        bad.forEach { assertTrue(it, ThemeRules.parse(it).isFailure) }
    }

    @Test
    fun unreadableCombinationsAreReported() {
        val grey = ThemeSpec("Gris", mapOf("text" to "#777777", "canvas" to "#808080"))
        val issues = Contrast.issues(grey, base)
        assertTrue(issues.any { it.foreground == "text" && it.background == "canvas" })
    }
}
