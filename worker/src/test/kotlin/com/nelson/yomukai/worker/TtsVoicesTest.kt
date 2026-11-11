package com.nelson.yomukai.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F6.1: Pocket TTS in Spanish by default, older versions keep English, and every piece at the same loudness. */
class TtsVoicesTest {
    @Test
    fun multilingualPocketOffersSpanishFirst() {
        val tts = TtsHandler(command = null, configuredVoices = null, multilingual = true)
        val first = tts.voices.first()
        assertEquals("lola", first.id)
        assertEquals("es", first.lang)
        val alba = tts.voices.first { it.id == "es_alba" }
        assertEquals("alba", alba.engineVoice)
        assertTrue(tts.voices.count { it.lang == "es" } >= 5)
        assertTrue(tts.voices.any { it.id == "alba" && it.lang == "en" })
        assertTrue(TtsHandler.POCKET_MULTI.containsAll(listOf("--language", "{language}")))
        assertEquals("spanish", TtsHandler.LANGUAGES["es"])
        // Voice ids stay what the server accepts.
        assertTrue(tts.voices.all { Regex("[a-z0-9_-]{1,40}").matches(it.id) })
    }

    @Test
    fun anOlderPocketKeepsItsEnglishVoices() {
        val tts = TtsHandler(command = null, configuredVoices = null, multilingual = false)
        assertTrue(tts.voices.all { it.lang == "en" })
        assertEquals(TtsHandler.POCKET_VOICES, tts.voices)
    }

    @Test
    fun configuredVoicesWin() {
        val tts = TtsHandler(command = listOf("x", "{text}"), configuredVoices = TtsHandler.parseVoices("ef_dora:es:f:Dora"), multilingual = true)
        assertEquals(listOf("ef_dora"), tts.voices.map { it.id })
    }

    @Test
    fun everyPieceIsLevelledAndSpeedIsKept() {
        assertEquals("loudnorm=I=-16:TP=-1.5:LRA=11", TtsHandler.filters(1.0))
        assertEquals("atempo=1.25,loudnorm=I=-16:TP=-1.5:LRA=11", TtsHandler.filters(1.25))
    }
}
