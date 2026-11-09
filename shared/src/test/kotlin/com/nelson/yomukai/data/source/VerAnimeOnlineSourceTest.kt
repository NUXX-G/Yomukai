package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.anime.VerAnimeOnlineSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** VerAnimeOnline against pages the site really served (test/resources/veranime_*). */
class VerAnimeOnlineSourceTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test
    fun listsAndTheNewestEpisodes() {
        val list = VerAnimeOnlineSource.parseCards(fixture("veranime_list.html"), "veranimeonline")
        assertEquals(20, list.size)
        assertTrue(list.all { it.type == MediaType.ANIME && it.coverUrl.startsWith("https://veranimeonline.co/wp-content/uploads/") })
        val letter = VerAnimeOnlineSource.parseCards(fixture("veranime_letter.html"), "veranimeonline")
        assertTrue(letter.any { it.id == "buta-no-liver-wa-kanetsu-shiro" && it.title == "Buta no Liver wa Kanetsu Shiro" })
        val latest = VerAnimeOnlineSource.parseLatestEpisodes(fixture("veranime_home.html"), "veranimeonline")
        assertTrue(latest.any { it.id == "one-piece" && it.title == "One Piece" })
    }

    @Test
    fun aTitlesEpisodesAndPlayer() {
        val base = MediaItem("bleach", "Bleach", "", MediaType.ANIME, "", "", "veranimeonline")
        val d = VerAnimeOnlineSource.parseDetails(fixture("veranime_anime.html"), base)
        assertTrue(d.title.startsWith("Bleach"))
        val eps = VerAnimeOnlineSource.parseEpisodes(fixture("veranime_anime.html"), base.id)
        assertTrue(eps.size > 300)
        val first = eps.first { it.chapterNumber == 1f }
        assertEquals("https://veranimeonline.co/episodio/bleach-episodio-1/", first.url)
        assertEquals("2004-10-05", first.dateUploaded)
        val players = VerAnimeOnlineSource.parsePlayers(fixture("veranime_episode.html"))
        assertEquals("Blogger", players.single().serverName)
        assertTrue(players.single().videoUrl.startsWith("https://www.blogger.com/video.g?token="))
    }
}
