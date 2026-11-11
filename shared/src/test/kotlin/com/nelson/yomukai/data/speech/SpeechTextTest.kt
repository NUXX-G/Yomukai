package com.nelson.yomukai.data.speech

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** F6.1: what the voice says. The same cases run in the web (web/tests/speech-text.test.mjs). */
class SpeechTextTest {
    @Test
    fun sharedCases() {
        val cases = JsonParser.parseString(javaClass.classLoader!!.getResource("speech_text.json")!!.readText()).asJsonArray
        val wrong = cases.map { it.asJsonObject }.mapNotNull { c ->
            val got = SpeechText.normalize(c.get("in").asString, c.get("lang").asString)
            if (got == c.get("out").asString) null else "${c.get("in").asString}\n  esperado: ${c.get("out").asString}\n  sale:     $got"
        }
        assertEquals("", wrong.joinToString("\n"))
    }

    @Test
    fun numbersInWords() {
        assertEquals("cien", SpeechText.cardinalEs(100))
        assertEquals("ciento uno", SpeechText.cardinalEs(101))
        assertEquals("quinientos", SpeechText.cardinalEs(500))
        assertEquals("treinta y un mil", SpeechText.cardinalEs(31_000))
        assertEquals("dos millones trescientos mil", SpeechText.cardinalEs(2_300_000))
        assertEquals("one hundred five", SpeechText.cardinalEn(105))
        assertEquals("twenty twenty-six", SpeechText.yearEn(2026))
        assertEquals("nineteen oh five", SpeechText.yearEn(1905))
        assertEquals("two thousand five", SpeechText.yearEn(2005))
    }
}
