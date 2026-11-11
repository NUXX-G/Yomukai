package com.nelson.yomukai.server

import com.nelson.yomukai.data.network.HttpFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** F6.3: the free online translator as the last step, only for short selections and within its limits. */
class OnlineTranslatorTest {
    private val real = javaClass.classLoader!!.getResource("mymemory_answer.json")!!.readText()

    private class Fake(private val answer: String) : HttpFetcher {
        val urls = mutableListOf<String>()
        override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean): String { urls += url; return answer }
        override suspend fun postString(url: String, headers: Map<String, String>) = error("no")
        override suspend fun postJson(url: String, body: String) = error("no")
        override suspend fun checkImage(url: String, headers: Map<String, String>) = error("no")
    }

    @Test
    fun translatesAShortSelectionAndRemembersIt() = runBlocking {
        val http = Fake(real)
        val tr = OnlineTranslator(http)
        assertEquals(listOf("Empiezo hoy", ""), tr.translate(1, listOf("I'm  starting today", " "), "en-US", "es"))
        assertEquals(listOf("Empiezo hoy"), tr.translate(1, listOf("I'm starting today"), "en", "es"))
        assertEquals(1, http.urls.size)
        assertTrue(http.urls.single().startsWith("https://api.mymemory.translated.net/get?q=I%27m+starting+today&langpair=en%7Ces"))
    }

    @Test
    fun refusesWhatItShouldNotSend() = runBlocking {
        val http = Fake(real)
        val tr = OnlineTranslator(http)
        assertNull(tr.translate(1, listOf("hola"), "auto", "es")) // needs a real language
        assertNull(tr.translate(1, listOf("hola"), "es", "es"))
        assertNull(tr.translate(1, listOf("x".repeat(OnlineTranslator.MAX_CHARS + 1)), "en", "es")) // not whole chapters
        assertNull(tr.translate(1, List(OnlineTranslator.MAX_TEXTS + 1) { "a" }, "en", "es"))
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun keepsUnderTheDailyAllowanceAndThePerPersonPace() = runBlocking {
        var day = LocalDate.of(2026, 11, 11)
        val tr = OnlineTranslator(Fake(real), today = { day })
        val text = "y".repeat(OnlineTranslator.MAX_CHARS - 1)
        var done = 0
        while (tr.translate(2, listOf(text + done), "en", "es") != null) done++
        assertEquals(OnlineTranslator.DAILY_CHARS / OnlineTranslator.MAX_CHARS, done)
        day = day.plusDays(1)
        assertEquals(1, tr.translate(2, listOf("a new day"), "en", "es")?.size)
        val other = OnlineTranslator(Fake(real))
        repeat(OnlineTranslator.MAX_PER_MINUTE) { other.translate(3, listOf("t$it"), "en", "es") }
        assertNull(other.translate(3, listOf("one more"), "en", "es"))
    }

    @Test
    fun itsErrorsAreNotTranslations() {
        assertNull(OnlineTranslator.parse("""{"responseData":{"translatedText":"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY"},"responseStatus":"429"}"""))
        assertNull(OnlineTranslator.parse("""{"responseData":{"translatedText":"x"},"quotaFinished":true,"responseStatus":200}"""))
        assertEquals("It's \"ok\" & <fine>", OnlineTranslator.parse("""{"responseData":{"translatedText":"It&#39;s &quot;ok&quot; &amp; &lt;fine&gt;"},"responseStatus":200}"""))
    }
}
