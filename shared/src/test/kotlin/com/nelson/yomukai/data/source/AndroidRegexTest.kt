package com.nelson.yomukai.data.source

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Android's regex engine (ICU) refuses a "}" that closes nothing ("\\{(\\w+)}"), while the JVM's accepts it, so such a
 * pattern passes every test here and breaks on the phone (it once kept a whole source from loading). Every Regex in the
 * shared code must escape a closing brace unless it ends a quantifier ({2}, {2,30}) or a \p{…} class.
 */
class AndroidRegexTest {
    private val literal = Regex("""Regex\(\s*"((?:[^"\\]|\\.)*)"""")
    private val allowed = Regex("""\{\d+(,\d*)?}|\\\\p\{\w+}|\\\\P\{\w+}""")

    @Test
    fun noBareClosingBraceInPatterns() {
        val bad = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            literal.findAll(f.readText()).mapNotNull { m ->
                val pattern = m.groupValues[1]
                // Inside a character class "[^}]" a brace is fine; quantifiers and \p{…} are fine; anything else is not.
                val stripped = allowed.replace(pattern.replace(Regex("""\[[^\]]*]"""), ""), "").replace("\\\\}", "")
                if ('}' in stripped) "${f.name}: $pattern" else null
            }
        }.toList()
        assertEquals(emptyList<String>(), bad)
    }
}
