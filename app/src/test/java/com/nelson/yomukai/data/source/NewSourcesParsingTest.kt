package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.anime.AnimeAv1Source
import com.nelson.yomukai.data.source.manga.AsuraScansSource
import com.nelson.yomukai.data.source.manga.LectorTmoSource
import com.nelson.yomukai.data.source.manga.OlympusScansSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers run against real pages captured from each site (src/test/resources). */
class NewSourcesParsingTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    @Test
    fun olympusChapterPagesInOrder() {
        val pages = OlympusScansSource.parsePages(fixture("olympus_chapter.html"))
        assertTrue(pages.size > 20)
        assertEquals("https://media.imagesolymp.xyz/comics/1771/134805/0.1.webp", pages.first())
        assertTrue(pages.all { "/comics/1771/134805/" in it })
    }

    @Test
    fun olympusHomeCards() {
        val items = OlympusScansSource.parseHome(fixture("olympus_home.html"), emptyMap())
        assertTrue(items.size >= 10)
        assertTrue(items.all { !it.id.startsWith("comic-") && it.title.isNotBlank() })
        assertTrue(items.any { it.id == "juicio-del-infierno" && it.coverUrl.startsWith("https://media.imagesolymp.xyz/") })
    }

    @Test
    fun asuraPagesFromIslandProps() {
        val pages = AsuraScansSource.parsePages(fixture("asura_chapter.html"))
        assertEquals(22, pages.size)
        assertTrue(pages.first().contains("/absolute-regression/124/b78e4e.webp"))
    }

    @Test
    fun lectorTmoHomeCards() {
        val items = LectorTmoSource.parseCards(fixture("lectortmo_home.html"))
        assertTrue(items.isNotEmpty())
        val first = items.first()
        assertTrue(first.id.matches(Regex("https://lectortmo.vip/manga/[^/]+/")))
        assertTrue(first.coverUrl.contains("/wp-content/uploads/"))
    }

    @Test
    fun animeAv1CatalogDetailsEpisodesAndServers() {
        val catalog = AnimeAv1Source.parseCatalog(fixture("av1_catalog.html"))
        assertTrue(catalog.any { it.id == "one-piece" && it.title == "One Piece" })

        val details = AnimeAv1Source.parseDetails(fixture("av1_media.html"), "ao-ashi-season-2")
        assertEquals("Ao Ashi Season 2", details.title)
        assertEquals("https://cdn.animeav1.com/covers/4462.jpg", details.coverUrl)
        assertEquals("Segunda temporada de Ao Ashi.", details.description)

        val episodes = AnimeAv1Source.parseEpisodes(fixture("av1_media.html"), "ao-ashi-season-2")
        assertEquals(listOf(1f, 2f, 3f, 4f), episodes.map { it.chapterNumber })
        assertEquals("https://animeav1.com/media/ao-ashi-season-2/4", episodes.last().url)

        val servers = AnimeAv1Source.parseVideoSources(fixture("av1_episode.html"))
        assertEquals(5, servers.size)
        val hls = servers.first()
        assertTrue(hls.isDirect)
        assertEquals("https://player.zilla-networks.com/m3u8/b1876b9469a06fdb2d45f9f5a536e4a7", hls.videoUrl)
        assertTrue(servers.drop(1).none { it.isDirect })
    }

    @Test
    fun detectsFirewallBlockPages() {
        assertEquals("Wordfence", NetworkHelper.detectProtection(503,
            "<html><title>Tu acceso a este sitio ha sido limitado por el propietario del sitio.</title></html>"))
        assertEquals("Cloudflare", NetworkHelper.detectProtection(403, "<title>Attention Required! | Cloudflare</title>"))
        assertEquals("Cloudflare", NetworkHelper.detectProtection(503, "<html><title>Just a moment...</title></html>"))
        assertNull(NetworkHelper.detectProtection(200, fixture("olympus_home.html")))
        assertNull(NetworkHelper.detectProtection(200, fixture("av1_media.html")))
    }
}
