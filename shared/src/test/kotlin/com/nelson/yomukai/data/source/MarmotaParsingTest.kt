package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.manga.MarmotaComicsSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarmotaParsingTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    @Test
    fun homeCards() {
        val items = MarmotaComicsSource.parseItems(fixture("marmota_home.html"), ".page-item-detail")
        assertTrue(items.size >= 40)
        assertTrue(items.all { it.type == MediaType.COMIC && it.title.isNotBlank() && it.id.contains("/comic/") })
        assertTrue(items.all { it.coverUrl.startsWith("https://") && "dflazy" !in it.coverUrl })
    }

    @Test
    fun searchCardsWithAlternativeTitles() {
        val items = MarmotaComicsSource.parseItems(fixture("marmota_search.html"), ".c-tabs-item__content")
        assertTrue(items.isNotEmpty())
        val impostor = items.first { it.id == "https://marmota.me/comic/batman-the-imposter-tpb-2021/" }
        assertTrue(impostor.altTitles.any { it.startsWith("Batman: El Impostor") })
    }

    @Test
    fun detailsAndChapters() {
        val base = MediaItem("https://marmota.me/comic/batman-v4-2025/", "Batman", "", MediaType.COMIC, "", "", "marmota")
        val d = MarmotaComicsSource.parseDetails(fixture("marmota_detail.html"), base)
        assertEquals("OnGoing", d.status)
        assertTrue(d.description.isNotBlank())
        val chapters = MarmotaComicsSource.parseChapters(fixture("marmota_chapters_ajax.html"), base.id)
        assertEquals(13, chapters.size)
        assertEquals("https://marmota.me/comic/batman-v4-2025/batman-vol-4-13/", chapters.first().url)
        assertEquals(13f, chapters.first().chapterNumber)
    }

    @Test
    fun pagesAreCleanHttps() {
        val pages = MarmotaComicsSource.parsePages(fixture("marmota_chapter.html"))
        assertEquals(33, pages.size)
        assertTrue(pages.all { it.startsWith("https://marmota.me/wp-content/uploads/") && ' ' !in it })
    }
}
