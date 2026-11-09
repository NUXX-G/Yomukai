package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.anime.AnimeDLatinoSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AnimeDLatino against pages the site really served (test/resources/animedlatino_*). */
class AnimeDLatinoSourceTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test
    fun homeCatalogueAndSearchGiveSeries() {
        val home = AnimeDLatinoSource.parseCards(fixture("animedlatino_home.html"), "animedlatino")
        assertTrue(home.any { it.id == "ao-ashi-temporada-2" && it.title == "Ao Ashi Temporada 2" })
        assertTrue(home.first { it.id == "ao-ashi-temporada-2" }.coverUrl.startsWith("https://animedlatino.com/carga/portadas/"))
        val catalogue = AnimeDLatinoSource.parseCards(fixture("animedlatino_catalogue.html"), "animedlatino")
        assertEquals(28, catalogue.size)
        assertTrue(catalogue.all { it.type == MediaType.ANIME && it.title.isNotBlank() })
        assertTrue(AnimeDLatinoSource.parseCards(fixture("animedlatino_search.html"), "animedlatino").any { it.title.contains("Black Clover") })
    }

    @Test
    fun aSeriesAndItsEpisodes() {
        val base = MediaItem("ao-ashi-temporada-2", "Ao Ashi Temporada 2", "", MediaType.ANIME, "", "", "animedlatino")
        val d = AnimeDLatinoSource.parseDetails(fixture("animedlatino_serie.html"), base)
        assertEquals("En emisión", d.status)
        val eps = AnimeDLatinoSource.parseEpisodes(fixture("animedlatino_serie.html"), base.id)
        assertEquals("https://animedlatino.com/ver/ao-ashi-temporada-2/1x6", eps.single().url)
        assertEquals(6f, eps.single().chapterNumber)
        val long = AnimeDLatinoSource.parseEpisodes(fixture("animedlatino_serie_long.html"), "black-clover-tv")
        assertEquals(170, long.size)
    }

    @Test
    fun anEpisodesServersAndTheirPlayers() {
        val servers = AnimeDLatinoSource.parseServers(fixture("animedlatino_episode.html"))
        assertTrue(servers.size >= 6)
        assertTrue(servers.any { it.first == "Voe" })
        assertTrue(servers.all { it.second.startsWith("https://animedlatino.com/reproducir?e=") })
        assertEquals("https://voe.sx/e/a5z8g243qtdc", AnimeDLatinoSource.playerIn(fixture("animedlatino_player.html")))
    }
}
