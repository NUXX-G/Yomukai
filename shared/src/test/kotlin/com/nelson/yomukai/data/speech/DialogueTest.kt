package com.nelson.yomukai.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueTest {
    private fun shape(text: String) = Dialogue.split(text).map { Triple(it.text, it.dialogue, it.gender) }

    @Test
    fun spanishDashDialogue() {
        assertEquals(
            listOf(Triple("No lo sé", true, Gender.FEMALE), Triple("dijo ella, mirando por la ventana", false, Gender.UNKNOWN), Triple(". Quizá mañana.", true, Gender.FEMALE)),
            shape("—No lo sé —dijo ella, mirando por la ventana—. Quizá mañana.")
        )
        assertEquals(Gender.MALE, Dialogue.split("—¡Corre! —gritó él.").first().gender)
        assertEquals(Gender.MALE, Dialogue.split("—Hola —respondió el príncipe.").first().gender)
        assertEquals(Gender.UNKNOWN, Dialogue.split("—Vale.").first().gender)
    }

    @Test
    fun quotesAndNarration() {
        assertEquals(listOf(Triple("Run!", true, Gender.MALE), Triple("he shouted.", false, Gender.UNKNOWN)), shape("\"Run!\" he shouted."))
        assertEquals(Gender.FEMALE, Dialogue.split("«Ven aquí», dijo la reina.").first().gender)
        assertEquals(listOf(Triple("Era una noche fría.", false, Gender.UNKNOWN)), shape("Era una noche fría."))
    }

    @Test
    fun cleansAndChunks() {
        assertEquals("[Hola mundo] fin", Dialogue.speakable("\\[Hola\u200B\u200C mundo\\] *** fin"))
        val long = "Hola mundo. ".repeat(80).trim()
        val pieces = Dialogue.chunks(long)
        assertTrue(pieces.size > 1 && pieces.all { it.length <= 800 })
        assertEquals(long.replace(" ", ""), pieces.joinToString("").replace(" ", ""))
    }
}
