package com.nelson.yomukai.data.learning

import com.nelson.yomukai.data.learning.TranslationChain.Via
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F6.3: the same order as the web; a step that is off never blocks the next. */
class TranslationChainTest {
    private val dog = listOf(Dictionary.Entry("dog", "noun", "/dɒɡ/", listOf("A domesticated canine"), listOf("perro", "can")))

    @Test
    fun aWordComesFromTheDictionaryFirst() = runBlocking {
        val calls = mutableListOf<String>()
        val r = TranslationChain.translate(
            "Dog", "en-GB", "es",
            dictionary = { lang, w -> calls += "dict $lang $w"; dog },
            device = { _, _, _ -> calls += "device"; "NO" },
            server = { _, _, _ -> calls += "server"; null }
        )
        assertEquals(TranslationChain.Result("perro, can", Via.DICTIONARY), r)
        assertEquals(listOf("dict en dog"), calls)
    }

    @Test
    fun aPhraseGoesToTheDeviceThenTheServer() = runBlocking {
        val device = TranslationChain.translate("good morning", "en", "es", { _, _ -> error("not for phrases") }, { _, _, _ -> "buenos días" }, { _, _, _ -> null })
        assertEquals(Via.DEVICE, device.via)
        val server = TranslationChain.translate("good morning", "en", "es", { _, _ -> null }, { _, _, _ -> null }, { t, f, to -> "$t:$f>$to" to Via.ONLINE })
        assertEquals(TranslationChain.Result("good morning:en>es", Via.ONLINE), server)
    }

    @Test
    fun nothingAvailableGivesTheWebTranslator() = runBlocking {
        val r = TranslationChain.translate("こんにちは", "ja", "es", { _, _ -> emptyList() }, { _, _, _ -> null }, { _, _, _ -> null })
        assertNull(r.text)
        assertTrue(r.webUrl!!.startsWith("https://translate.google.com/?sl=ja&tl=es&text=%E3%81%93"))
    }

    @Test
    fun sameLanguageAndUnknownLanguage() = runBlocking {
        assertEquals(Via.SAME, TranslationChain.translate("Hola", "es-ES", "es", { _, _ -> null }, { _, _, _ -> null }, { _, _, _ -> null }).via)
        var asked = ""
        TranslationChain.translate("bonjour à tous", "auto", "es", { _, _ -> null }, { _, _, _ -> error("needs a language") }, { _, f, _ -> asked = f; null })
        assertEquals("auto", asked)
        assertEquals("", TranslationChain.baseLang("auto"))
        assertEquals("pt", TranslationChain.baseLang("pt_BR"))
        assertEquals("A domesticated canine", TranslationChain.fromDictionary(dog, "en"))
        assertNull(TranslationChain.fromDictionary(dog, "fr"))
    }
}
