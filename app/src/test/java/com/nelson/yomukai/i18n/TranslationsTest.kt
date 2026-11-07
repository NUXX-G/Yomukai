package com.nelson.yomukai.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every text of the app wrapped in tr("…") has its English translation in shared/src/main/resources/i18n/en.json. */
class TranslationsTest {
    @After
    fun backToSpanish() = I18n.use(I18n.SPANISH)

    private val call = Regex("""\btr\(\s*"((?:[^"\\]|\\.)*)"""")

    private fun unescape(s: String) = s.replace(Regex("""\\(.)""")) { m ->
        when (val c = m.groupValues[1]) { "n" -> "\n"; "t" -> "\t"; else -> c }
    }

    /** All keys used by the app (the shared module's own tr() calls too). */
    private fun keys(): Set<String> = listOf(File("src/main/java"), File("../shared/src/main/kotlin"))
        .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
        .flatMap { f -> call.findAll(f.readText()).map { unescape(it.groupValues[1]) } }
        .toSet()

    @Test
    fun everyTextHasItsEnglishTranslation() {
        val found = keys()
        assertTrue("the app's sources were found", found.size > 200)
        val missing = found - I18n.englishKeys()
        assertEquals("Sin traducir (añádelos a shared/src/main/resources/i18n/en.json):\n" + missing.sorted().joinToString("\n"), emptySet<String>(), missing)
    }

    @Test
    fun englishAndSpanishAndSlots() {
        I18n.use("en")
        assertEquals("Settings", tr("Ajustes"))
        assertEquals("Search “Frieren” everywhere", tr("Buscar «{0}» en todas", "Frieren"))
        assertEquals("not in the dictionary stays as it is", "Texto nuevo {0}", tr("Texto nuevo {0}"))
        I18n.use("es")
        assertEquals("Buscar «Frieren» en todas", tr("Buscar «{0}» en todas", "Frieren"))
        I18n.use(null, deviceLanguage = "fr")
        assertEquals("other device languages get English", "en", I18n.lang)
    }
}
