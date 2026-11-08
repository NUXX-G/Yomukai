package com.nelson.yomukai.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryTest {
    private val lines = javaClass.getResource("/kaikki_sample.jsonl")!!.readText().lines().filter { it.isNotBlank() }

    @Test
    fun realKaikkiLinesBecomeShortEntries() {
        val entries = lines.mapNotNull(Dictionary::parse)
        val noun = entries.first { it.word == "dictionary" && it.pos == "noun" }
        assertTrue(noun.pron.startsWith("/"))
        assertTrue(noun.glosses.size in 1..3 && noun.glosses.all { it.length <= 160 })
        assertTrue("Spanish translation: " + noun.es, "diccionario" in noun.es)
        val gdp = entries.first { it.word == "GDP" }
        assertEquals("jì-dì-pì", gdp.pron)
        val a = entries.first { it.word == "A" && it.pos == "verb" }
        assertEquals("pinyin without the extra notation", "ēi", a.pron)
    }

    @Test
    fun keysAndWhatIsLeftOut() {
        assertEquals("hello", Dictionary.key(" Hello,"))
        assertEquals("中国", Dictionary.key("中国。"))
        assertNull(Dictionary.parse("""{"word":"Paris","pos":"name","senses":[{"glosses":["capital of France"]}]}"""))
        assertNull(Dictionary.parse("""{"word":"thee","pos":"pron","senses":[{"glosses":["you"],"tags":["archaic"]}]}"""))
        assertNull(Dictionary.parse("not json"))
        assertEquals("https://kaikki.org/dictionary/Chinese/kaikki.org-dictionary-Chinese.jsonl", Dictionary.dumpUrl("zh"))
        assertNull(Dictionary.dumpUrl("xx"))
        assertEquals(listOf("hello"), Dictionary.candidates("hello"))
        assertEquals(listOf("中国人", "中国", "中"), Dictionary.candidates("中国人"))
    }
}
