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
    fun catalogueUsesTheProfileLanguages() {
        val request = MangaDexSource.catalogueRequest(1, emptyMap(), 24, listOf("zh", "zh-hk"))
        assertTrue("availableTranslatedLanguage[]=zh-hk" in request && "availableTranslatedLanguage[]=en" !in request)
    }
}
