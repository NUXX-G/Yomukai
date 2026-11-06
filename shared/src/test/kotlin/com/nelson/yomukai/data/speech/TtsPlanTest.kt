package com.nelson.yomukai.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlanTest {
    @Test
    fun bestAvailableTierFirstAndTheSystemVoiceAlwaysLast() {
        assertEquals(listOf(TtsTier.WORKER, TtsTier.SERVER, TtsTier.SYSTEM), TtsPlan.order(setOf(TtsTier.SERVER, TtsTier.WORKER)))
        assertEquals(listOf(TtsTier.SYSTEM), TtsPlan.order(emptySet()))
        assertEquals(listOf(TtsTier.DEVICE, TtsTier.SYSTEM), TtsPlan.order(setOf(TtsTier.WORKER, TtsTier.DEVICE), offline = true))
    }

    @Test
    fun chunksAreWholeSentencesUnderTheLimit() {
        val text = "Primera frase. ¿Segunda frase? ¡Tercera!\n\nOtro párrafo con «comillas». Y otra frase más larga que las demás."
        val chunks = TtsPlan.chunks(text, max = 40)
        assertTrue(chunks.all { it.length <= 40 })
        assertEquals("Primera frase. ¿Segunda frase? ¡Tercera!", chunks.first())
        assertEquals(text.replace("\n", " ").replace(Regex("\\s+"), " "), chunks.joinToString(" "))
        // The same text always gives the same pieces (cache keys are shared between readers).
        assertEquals(chunks, TtsPlan.chunks(text, max = 40))
    }

    @Test
    fun aSentenceLongerThanTheLimitIsSplitBetweenWords() {
        val long = (1..60).joinToString(" ") { "palabra$it" } + "."
        val chunks = TtsPlan.chunks(long, max = 100)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 100 && !it.startsWith(" ") })
        assertEquals(long, chunks.joinToString(" "))
    }
}
