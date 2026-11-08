package com.nelson.yomukai.data.source

import com.google.gson.JsonParser
import com.nelson.yomukai.data.source.anime.JkAnimeSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JKAnime against pages the site really served (test/resources/jkanime_*). */
class JkAnimeSourceTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test
    fun homeDirectoryAndSearchGiveTitles() {
        val home = JkAnimeSource.parseHome(fixture("jkanime_home.html"), "jkanime")
        assertTrue(home.size >= 20)
        val kanata = home.first { it.id == "kanata-kara" }
        assertEquals("Kanata kara", kanata.title)
        assertEquals("https://cdn.jkdesa.com/assets/images/animes/image/kanata-kara.jpg", kanata.coverUrl)

        val directory = JkAnimeSource.parseDirectory(fixture("jkanime_directory.html"), "jkanime")
        assertTrue(directory.size >= 20)
        assertTrue(directory.all { it.type == MediaType.ANIME && it.title.isNotBlank() && it.coverUrl.startsWith("https://cdn.jkdesa.com/") })

        val found = JkAnimeSource.parseSearch(fixture("jkanime_search.html"), "jkanime")
        assertTrue(found.any { it.id == "black-clover-tv" && it.title == "Black Clover (TV)" })
    }

    @Test
    fun detailsAndEpisodes() {
        val base = MediaItem("black-clover-2nd-season", "Black Clover 2nd Season", "", MediaType.ANIME, "", "", "jkanime")
        val d = JkAnimeSource.parseDetails(fixture("jkanime_anime.html"), base)
        assertEquals("Black Clover 2nd Season", d.title)
        assertTrue(d.coverUrl.startsWith("https://cdn.jkdesa.com/"))
        val eps = JkAnimeSource.parseEpisodes(JsonParser.parseString(fixture("jkanime_episodes.json")).asJsonObject, base.id)
        assertEquals(6, eps.size)
        assertEquals("https://jkanime.net/black-clover-2nd-season/1/", eps.first().url)
        assertEquals("2026-10-03", eps.first().dateUploaded)
    }

    @Test
    fun anEpisodeHasTheSitesOwnStreamAndItsHosts() {
        val page = fixture("jkanime_episode.html")
        val players = JkAnimeSource.parsePlayers(page)
        assertEquals(2, players.size)
        assertTrue(JkAnimeSource.streamIn(fixture("jkanime_player.html"))!!.contains(".m3u8"))
        val servers = JkAnimeSource.parseServers(page)
        assertTrue(servers.any { it.serverName == "VOE" && it.videoUrl.startsWith("https://voe.sx/e/") })
        assertTrue(servers.any { it.serverName == "Mp4upload" && it.videoUrl.contains("mp4upload.com") })
        assertTrue(servers.all { it.videoUrl.startsWith("https://") && !it.isDirect })
        assertTrue("an entry without a video id is left out", servers.none { it.videoUrl.endsWith("/embed/") })
    }
}
