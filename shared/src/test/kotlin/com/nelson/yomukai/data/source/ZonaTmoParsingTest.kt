package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.manga.ZonaTmoSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZonaTmoParsingTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    private fun item(id: String, source: String) = MediaItem(id, "", "", MediaType.MANHWA, "", "", source)

    @Test
    fun zonaTmoCatalogueAndLatest() {
        val items = ZonaTmoSource.parseItems(fixture("zonatmo_library.html"))
        assertTrue(items.size >= 20)
        assertTrue(items.all { "/library/" in it.id && it.title.isNotBlank() && it.coverUrl.startsWith("http") })
    }

    @Test
    fun zonaTmoDetails() {
        val url = "https://zonatmo.org/library/manhwa/31352/la-ventura-del-caballero-blanco"
        val details = ZonaTmoSource.parseDetails(fixture("zonatmo_detail.html"), item(url, "zonatmo"))
        assertEquals("La Ventura Del Caballero Blanco", details.title)
        assertEquals(MediaType.MANHWA, details.type)
        assertEquals("Publicándose", details.status)
        assertTrue("Drama" in details.genres)
        assertTrue(details.description.startsWith("Para asegurar la paz"))
        assertTrue(details.author.contains("Brothers Without a Tomorrow"))
        assertTrue("Blossoms of the White Night" in details.altTitles)
    }

    @Test
    fun zonaTmoChaptersAndPages() {
        val url = "https://zonatmo.org/library/manhwa/31352/la-ventura-del-caballero-blanco"
        val chapters = ZonaTmoSource.parseChapters(fixture("zonatmo_detail.html"), url)
        assertTrue(chapters.size >= 30)
        val first = chapters.first()
        assertEquals("Capítulo 30", first.title)
        assertEquals(30f, first.chapterNumber)
        assertEquals("https://zonatmo.org/view_uploads/985018", first.url)
        assertEquals("22/06/2026", first.dateUploaded)

        val pages = ZonaTmoSource.parsePages(fixture("zonatmo_chapter.html"))
        assertTrue(pages.size >= 30)
        assertTrue(pages.all { "/chapters/985018/" in it })
    }

    @Test
    fun zonaTmoFilters() {
        assertEquals(
            "https://zonatmo.org/biblioteca?order_item=likes_count&order_dir=desc&page=1",
            ZonaTmoSource.catalogueUrl(1, emptyMap())
        )
        assertEquals(
            "https://zonatmo.org/biblioteca?order_item=alphabetically&order_dir=asc&page=3&type=manhwa&status=ongoing",
            ZonaTmoSource.catalogueUrl(
                3, mapOf(FilterKeys.SORT to setOf("alphabetically"), FilterKeys.TYPE to setOf("manhwa"), FilterKeys.STATUS to setOf("ongoing"))
            )
        )
    }

    @Test
    fun catalogueFiltersBuildTheSitesOwnRequests() {
        assertTrue(
            "&genders%5B%5D=1&exclude_genders%5B%5D=31" in
                ZonaTmoSource.catalogueUrl(1, mapOf(FilterKeys.GENRE to setOf("1"), "without" to setOf("31")))
        )
        assertEquals(
            "/series?page=2&direction=desc&type=comic&status=4&genres=26",
            com.nelson.yomukai.data.source.manga.OlympusScansSource.catalogueRequest(2, mapOf(FilterKeys.STATUS to setOf("4"), FilterKeys.GENRE to setOf("26")))
        )
        assertEquals(
            "/catalogo?order=score&page=1&genre=mecha",
            com.nelson.yomukai.data.source.anime.AnimeAv1Source.catalogueRequest(1, mapOf(FilterKeys.SORT to setOf("score"), FilterKeys.GENRE to setOf("mecha")))
        )
    }
}
