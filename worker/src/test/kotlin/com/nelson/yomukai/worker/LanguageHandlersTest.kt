package com.nelson.yomukai.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageHandlersTest {
    @Test
    fun onlyPublicAddressesAreFetched() {
        listOf("http://localhost/x.png", "http://127.0.0.1/x", "http://192.168.1.10/x", "http://10.0.0.5/x", "ftp://example.com/x", "file:///etc/passwd", "nada")
            .forEach { url -> assertTrue(url, runCatching { PublicOnly.check(url) }.exceptionOrNull() is PermanentJobFailure) }
    }

    @Test
    fun voicesAndCommandTemplatesAreParsedSafely() {
        assertEquals(listOf("pocket-tts", "generate", "{text}"), TtsHandler.template(" pocket-tts  generate {text} "))
        val voices = TtsHandler.parseVoices("ef_dora:es:f:Dora, ../x:es:f:Mala, em_alex:es:m")!!
        assertEquals(listOf("ef_dora", "em_alex"), voices.map { it.id })
        assertEquals("em_alex", voices[1].label)
    }
}
