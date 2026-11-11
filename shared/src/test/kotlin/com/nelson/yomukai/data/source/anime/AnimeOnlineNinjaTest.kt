package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.domain.model.AudioTrack
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F3.2: AnimeOnline.Ninja read from its real pages (saved 2026-11-11 through a real browser). */
class AnimeOnlineNinjaTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun theCastellanoSectionGivesTitlesMarkedAsSpainsDub() {
        val cards = AnimeOnlineNinjaSource.parseCards(res("ninja_genre.html"), "animeonlineninja", AudioTrack.CASTELLANO)
        assertTrue("a full page of titles: ${cards.size}", cards.size >= 30)
        assertTrue("films too, as «pelicula/slug»", cards.any { it.id.startsWith("pelicula/") })
        assertEquals("https://ww4.animeonline.ninja/pelicula/x/", AnimeOnlineNinjaSource.animePage("pelicula/x"))
        val yaiba = cards.first { it.id == "shin-samurai-den-yaiba" }
        assertEquals("Shin Samurai-den YAIBA", yaiba.title)
        assertTrue(yaiba.coverUrl.startsWith("https://ww4.animeonline.ninja/wp-content/uploads/"))
        assertTrue(cards.all { it.audio == AudioTrack.CASTELLANO && it.type == MediaType.ANIME })
    }

    @Test
    fun searchResults() {
        val found = AnimeOnlineNinjaSource.parseSearch(res("ninja_search.html"), "animeonlineninja")
        assertTrue(found.any { it.id == "dragon-ball-super-beerus" && it.title == "Dragon Ball Super: Beerus" })
        assertTrue("real covers, not the empty placeholder", found.all { it.coverUrl.startsWith("https://") })
    }

    @Test
    fun detailsAndEpisodes() {
        val html = res("ninja_anime.html")
        val base = MediaItem("shin-samurai-den-yaiba", "x", "", MediaType.ANIME, "", "", "animeonlineninja")
        val details = AnimeOnlineNinjaSource.parseDetails(html, base)
        assertEquals("Shin Samurai-den YAIBA", details.title)
        assertTrue(details.description.contains("Yaiba"))
        assertTrue("the site's SEO words are not genres", details.genres.isEmpty())
        val eps = AnimeOnlineNinjaSource.parseEpisodes(html, "shin-samurai-den-yaiba")
        assertTrue(eps.size >= 10)
        val first = eps.first { it.chapterNumber == 1f }
        assertEquals("https://ww4.animeonline.ninja/episodio/shin-samurai-den-yaiba-cap-1/", first.url)
        assertEquals("2026-11-05", first.dateUploaded)
    }

    @Test
    fun anEpisodesServersSayWhatTheyAreHeardIn() {
        val options = AnimeOnlineNinjaSource.parsePlayerOptions(res("ninja_episode.html"))
        assertEquals(listOf(AnimeOnlineNinjaSource.PlayerOption("224773", "1", "tv", "MULTISERVER")), options)
        assertEquals("https://ww4.animeonline.ninja/wp-json/dooplayer/v1/post/224773?type=tv&source=1", AnimeOnlineNinjaSource.playerApi(options[0]))
        assertEquals("https://saidochesto.top/embed.php?id=44814", AnimeOnlineNinjaSource.embedUrl(res("ninja_player.json")))
        assertEquals(null, AnimeOnlineNinjaSource.embedUrl("""{"embed_url":"javascript:alert(1)"}"""))
        val servers = AnimeOnlineNinjaSource.parseMultiserver(res("ninja_multiserver.html"))
        val audios = servers.map { it.audio }.toSet()
        assertTrue("subtitled, Latin American and Spain's servers: $audios", audios.containsAll(listOf(AudioTrack.ORIGINAL, AudioTrack.LATINO, AudioTrack.CASTELLANO)))
        assertTrue(servers.all { it.videoUrl.startsWith("https://") })
        assertTrue(servers.any { it.serverName == "STREAMWISH" && it.audio == AudioTrack.ORIGINAL })
    }

    @Test
    fun datesAsTheSiteWritesThem() {
        assertEquals("2026-11-05", AnimeOnlineNinjaSource.date("Nov. 05, 2026"))
        assertEquals("2025-04-12", AnimeOnlineNinjaSource.date("Apr. 12, 2025"))
        assertEquals("", AnimeOnlineNinjaSource.date("ayer"))
    }
}
