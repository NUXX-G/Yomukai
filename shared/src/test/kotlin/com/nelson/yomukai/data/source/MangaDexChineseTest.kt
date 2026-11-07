package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.manga.MangaDexProfile
import com.nelson.yomukai.data.source.manga.MangaDexSource
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaDexChineseTest {
    private val latest = javaClass.getResource("/mangadex_zh_latest.json")!!.readText()

    /** Answers every GET with [body] and remembers the URLs asked for. */
    private class FakeHttp(private val body: String) : HttpFetcher {
        val urls = mutableListOf<String>()
        override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean): String {
            urls += url
            return body
        }
        override suspend fun postString(url: String, headers: Map<String, String>) = error("no")
        override suspend fun postJson(url: String, body: String) = error("no")
        override suspend fun checkImage(url: String, headers: Map<String, String>) = error("no")
    }

    @Test
    fun chineseProfileShowsChineseTitlesAndAsksForChineseChapters() = runBlocking {
        val http = FakeHttp(latest)
        val source = MangaDexSource(http, MangaDexProfile.CHINESE)
        assertEquals("mangadex_zh", source.id)
        val items = source.fetchLatestUpdates(1).getOrThrow()
        assertEquals(listOf("今朝摇曳依旧", "卫府之七忍", "恋语轻唱"), items.map { it.title })
        assertTrue(items.all { it.sourceId == "mangadex_zh" })
        val url = http.urls.single()
        assertTrue("availableTranslatedLanguage[]=zh" in url && "availableTranslatedLanguage[]=zh-hk" in url)
        assertFalse("availableTranslatedLanguage[]=es" in url)
    }

    @Test
    fun defaultProfileKeepsRomanisedTitles() = runBlocking {
        val items = MangaDexSource(FakeHttp(latest)).fetchLatestUpdates(1).getOrThrow()
        assertEquals("Kesa mo Yuraretemasu", items.first().title)
        assertEquals(MediaType.MANGA, items.first().type)
    }

    @Test
    fun chineseSourceIsOffUntilTurnedOn() {
        val manager = SourceManager(FakeHttp("{}"))
        assertTrue(manager.get("mangadex_zh") != null)
        assertFalse("mangadex_zh" in manager.defaultEnabledIds)
        assertTrue("mangadex" in manager.defaultEnabledIds)
        assertFalse("no language filter: it is always Chinese", manager.require("mangadex_zh").filters.any { it.key == FilterKeys.LANGUAGE })
    }

    @Test
    fun spanishEnglishAndChineseAreSeparateSources() {
        val manager = SourceManager(FakeHttp("{}"))
        assertEquals("ES", manager.require("mangadex").language)
        assertEquals("EN", manager.require("mangadex_en").language)
        assertEquals("ZH", manager.require("mangadex_zh").language)
        assertTrue("mangadex_en" in manager.defaultEnabledIds)
    }

    @Test
    fun spanishChaptersKeepOneVersionPerNumberAndNoEnglish() = runBlocking {
        // Real feed (One-Punch Man): 20, 21 and 22 exist both as es and es-la.
        val http = FakeHttp(javaClass.getResource("/mangadex_es_feed.json")!!.readText())
        val chapters = MangaDexSource(http).fetchChapterList("d8a959f7-648e-4c8d-8f23-f1f3f8e129f3").getOrThrow()
        assertEquals(listOf(252f, 22f, 21f, 20f), chapters.map { it.chapterNumber })
        assertEquals("es (Spain) wins over es-la when both exist", "000d7819", chapters[1].id.take(8))
        assertEquals("f9aa5936", chapters[2].id.take(8))
        assertTrue(chapters.none { "[" in it.title })
        val url = http.urls.single()
        assertTrue("translatedLanguage[]=es-la" in url && "translatedLanguage[]=en" !in url)
    }

    @Test
    fun englishSourceAsksOnlyForEnglish() = runBlocking {
        val http = FakeHttp(latest)
        MangaDexSource(http, MangaDexProfile.ENGLISH).fetchLatestUpdates(1).getOrThrow()
        val url = http.urls.single()
        assertTrue("availableTranslatedLanguage[]=en" in url && "availableTranslatedLanguage[]=es" !in url)
    }

    @Test
    fun catalogueUsesTheProfileLanguages() {
        val request = MangaDexSource.catalogueRequest(1, emptyMap(), 24, listOf("zh", "zh-hk"))
        assertTrue("availableTranslatedLanguage[]=zh-hk" in request && "availableTranslatedLanguage[]=en" !in request)
    }
}
