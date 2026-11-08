package com.nelson.yomukai.data.speech

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin cut must match the web's (the fixture is produced and checked by web/tests/dialogue.test.mjs too). */
class SpeechPlanTest {
    @Test
    fun paragraphsAtBlankLines() {
        assertEquals(listOf("Uno.", "Dos y tres.", "Cuatro."), SpeechPlan.paragraphs("Uno.\n\nDos\ny tres.\n \u00A0\nCuatro.\n\n\n"))
    }

    @Test
    fun sameCutAsTheWeb() {
        val cases = JsonParser.parseString(javaClass.getResource("/speech_parity.json")!!.readText()).asJsonArray
        for (c in cases.map { it.asJsonObject }) {
            val paragraph = c.get("paragraph").asString
            fun expected(key: String) = c.getAsJsonArray(key).map { it.asJsonObject.let { o -> SpeechPlan.Piece(o.get("text").asString, o.get("role").asString) } }
            assertEquals(paragraph, expected("characters"), SpeechPlan.roles(paragraph, characters = true))
            assertEquals(paragraph, expected("single"), SpeechPlan.roles(paragraph, characters = false))
        }
    }
}
