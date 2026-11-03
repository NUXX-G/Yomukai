package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.anime.AnimeFlvSource
import com.nelson.yomukai.data.source.anime.VideoExtractors
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeFlvParsingTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    private val seriesUrl = "https://animeflv.or.at/anime/kimi-wo-aisuru-ki-wa-nai-to-itta-jiki-koushaku-sama-ga-nazeka-dekiai-shitekimasu/"

    @Test
    fun catalogueAndSearch() {
        val catalog = AnimeFlvSource.parseCatalog(fixture("animeflv_catalog.html"))
        assertTrue(catalog.size >= 15)
        assertTrue(catalog.all { "/anime/" in it.id && it.title.isNotBlank() && it.coverUrl.startsWith("https://") })
        assertTrue(AnimeFlvSource.parseCatalog(fixture("animeflv_search.html")).isNotEmpty())
    }

    @Test
    fun latestEpisodesPointAtTheirTitle() {
        val latest = AnimeFlvSource.parseLatestEpisodes(fixture("animeflv_home.html"))
        assertTrue(latest.size >= 20)
        val first = latest.first { it.id.contains("dark-summoner") }
        assertEquals("https://animeflv.or.at/anime/dark-summoner-to-dekiteiru/", first.id)
        assertEquals("Dark Summoner to Dekiteiru", first.title)
        assertEquals("Episodio 5", first.status)
    }

    @Test
    fun detailsAndEpisodes() {
        val html = fixture("animeflv_series.html")
        val details = AnimeFlvSource.parseDetails(html, MediaItem(seriesUrl, "", "", MediaType.ANIME, "", "", "animeflv"))
        assertTrue(details.title.contains("Kimi wo Aisuru Ki wa Nai"))
        assertTrue(details.description.startsWith("Un matrimonio arreglado"))
        assertTrue("Romance" in details.genres)
        assertTrue(details.coverUrl.endsWith("4392.jpg"))

        val episodes = AnimeFlvSource.parseEpisodes(html, seriesUrl)
        assertEquals(12, episodes.size)
        assertEquals(12f, episodes.first().chapterNumber)
        assertTrue(episodes.first().url.endsWith("-episodio-12/"))
    }

    @Test
    fun episodeServers() {
        val servers = AnimeFlvSource.parseVideoSources(fixture("animeflv_episode.html"))
        assertTrue(servers.any { it.serverName == "Voe" && it.videoUrl.startsWith("https://voe.sx/e/") })
    }

    @Test
    fun voeRevealsItsStream() {
        val stream = VideoExtractors.voeStream(fixture("voe_embed.html"))
        assertNotNull(stream)
        assertTrue(stream!!.startsWith("https://") && ".mp4" in stream)
    }

    @Test
    fun blockedHlsIsTriedLast() {
        fun s(name: String, url: String, direct: Boolean) = VideoSource("q", url, name, direct)
        val ordered = VideoExtractors.bestFirst(
            listOf(
                s("HLS", "https://player.zilla-networks.com/m3u8/a", true),
                s("Byse", "https://byse/e", false),
                s("Voe", "https://x/a.mp4", true),
                s("MP4Upload", "https://y/b.mp4", true)
            )
        )
        assertEquals(listOf("Voe", "MP4Upload", "HLS", "Byse"), ordered.map { it.serverName })
    }
}
