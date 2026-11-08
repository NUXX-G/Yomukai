package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.manga.ZonaTmoToSource
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZonaTMO (zonatmo.to) against answers its API really gave (test/resources/zonatmoto_*.json). */
class ZonaTmoToSourceTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    /** Answers by the API path asked for. */
    private class FakeApi(private val routes: List<Pair<String, String>>) : HttpFetcher {
        val urls = mutableListOf<String>()
        override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean): String {
            urls += url
            return routes.firstOrNull { (part, _) -> part in url }?.second ?: error("sin respuesta para $url")
        }
        override suspend fun postString(url: String, headers: Map<String, String>) = error("no")
        override suspend fun postJson(url: String, body: String) = error("no")
        override suspend fun checkImage(url: String, headers: Map<String, String>) = error("no")
    }

    @Test
    fun theListingGivesSpanishImageWorksWithTypeGenresAndCover() = runBlocking {
        val http = FakeApi(listOf("listing/manga" to fixture("zonatmoto_listing.json")))
        val items = ZonaTmoToSource(http).fetchLatestUpdates(2).getOrThrow()
        assertEquals(30, items.size)
        val ginseng = items.first { it.id == "star-ginseng-store" }
        assertEquals("Star Ginseng Store", ginseng.title)
        assertEquals(MediaType.MANHWA, ginseng.type)
        assertEquals("https://zonatmo.to/wp-content/uploads/2026/06/star-ginseng-store.webp", ginseng.coverUrl)
        assertTrue("Romance" in ginseng.genres && "Academia" in ginseng.genres)
        assertEquals("Publicándose", ginseng.status)
        assertEquals(MediaType.MANHUA, items.first { it.title == "Super Human Era" }.type)
        assertTrue(http.urls.single().contains("page=2") && http.urls.single().contains("orderBy=last_chapter_date"))
    }

    @Test
    fun searchLeavesOutNovelsAndEnglishAndMarksDoujinshiAsAdult() = runBlocking {
        val items = ZonaTmoToSource(FakeApi(listOf("listing/manga" to fixture("zonatmoto_search.json")))).searchMedia("one piece").getOrThrow()
        assertFalse("text novels are not images", items.any { it.title.startsWith("Sistema de One Piece") })
        assertFalse(items.any { it.title.startsWith("[English]") })
        assertTrue(items.first { it.title.startsWith("Love me to death") }.genres.contains("Adulto"))
        assertTrue(items.any { it.title == "One Piece Party" })
    }

    @Test
    fun chaptersAndPagesComeFromTheApiAndItsCdn() = runBlocking {
        val http = FakeApi(
            listOf(
                "/chapters" to fixture("zonatmoto_chapters.json"),
                "single/manga/star-ginseng-store/star-ginseng-store-capitulo-93" to fixture("zonatmoto_chapter.json")
            )
        )
        val source = ZonaTmoToSource(http)
        val chapters = source.fetchChapterList("star-ginseng-store").getOrThrow()
        assertEquals(25, chapters.size)
        val last = chapters.first()
        assertEquals(93f, last.chapterNumber)
        assertEquals("star-ginseng-store/star-ginseng-store-capitulo-93", last.url)
        assertEquals("2026-06-03", last.dateUploaded)
        val pages = source.fetchPageList(last.url).getOrThrow()
        assertTrue(pages.size > 20)
        assertTrue(pages.first().startsWith("https://cdn.zonatmo.to/manga/") && pages.first().endsWith("/page_0001.webp"))
    }
}
