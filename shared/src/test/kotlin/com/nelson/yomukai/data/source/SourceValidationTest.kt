package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.anime.AnimeFlvSource
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceValidationTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun item(title: String, id: String = title, cover: String = "https://x.com/$id.jpg") =
        MediaItem(id = id, title = title, coverUrl = cover, type = MediaType.MANGA, status = "", description = "", sourceId = "s")

    @Test
    fun aRealListPasses() {
        val items = AnimeFlvSource.parseCatalog(res("animeflv_catalog.html"))
        assertTrue(items.size >= SourceValidation.MIN_LATEST)
        assertEquals(emptyList<String>(), SourceValidation.latest(items))
        val chapters = AnimeFlvSource.parseEpisodes(res("animeflv_series.html"), "https://animeflv.or.at/anime/x/")
        assertEquals(emptyList<String>(), SourceValidation.chapters(chapters))
    }

    @Test
    fun aChangedPageIsCaught() {
        // The site changed its markup: the old selectors now match nothing.
        assertFalse(SourceValidation.latest(AnimeFlvSource.parseCatalog("<html><body><div class='nuevo'></div></body></html>")).isEmpty())
        // Menu links picked up instead of titles.
        assertFalse(SourceValidation.latest(List(10) { item("", "https://x.com/$it") }).isEmpty())
        assertFalse(SourceValidation.latest(List(10) { item("Cargando...", "https://x.com/$it") }).isEmpty())
        // The same card ten times.
        assertFalse(SourceValidation.latest(List(10) { item("Uno", "https://x.com/1") }).isEmpty())
        // Relative junk instead of image addresses.
        assertFalse(SourceValidation.latest(List(5) { item("T$it", cover = "/img/$it.jpg") }).isEmpty())
        assertFalse(SourceValidation.pages(listOf("/1.jpg", "/2.jpg")).isEmpty())
        assertFalse(SourceValidation.text("Cargando capítulo").isEmpty())
        assertFalse(SourceValidation.chapters(emptyList()).isEmpty())
        assertTrue(SourceValidation.pages(listOf("https://cdn.x.com/1.webp", "https://cdn.x.com/2.webp")).isEmpty())
    }

    @Test
    fun verdicts() {
        val ok = DiagnosticStep("Novedades", true, "20")
        val searchFailed = DiagnosticStep("Búsqueda", false, "Sin resultados", critical = false)
        val chaptersFailed = DiagnosticStep("Capítulos", false, "Sin capítulos")
        assertEquals(SourceHealth.OK, SourceVerdict.of(listOf(ok)))
        assertEquals(SourceHealth.WARNING, SourceVerdict.of(listOf(ok, searchFailed)))
        assertEquals(SourceHealth.DEGRADED, SourceVerdict.of(listOf(ok, searchFailed, chaptersFailed)))
        assertEquals(SourceHealth.DEGRADED, SourceVerdict.of(emptyList()))
        assertEquals("Capítulos: Sin capítulos", SourceVerdict.summary(listOf(ok, chaptersFailed)))
        assertTrue(SourceValidation.chapters(listOf(Chapter("1", "m", "Cap 1", 1f, "https://x.com/1", ""))).isEmpty())
    }
}
