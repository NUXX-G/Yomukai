package com.nelson.yomukai.ui

import com.nelson.yomukai.domain.model.VideoSource
import com.nelson.yomukai.ui.player.embedsToSniff
import com.nelson.yomukai.ui.player.pickServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerServerTest {
    @Test
    fun sniffingIsOnlyForWebPagesAndNeverLeavesTheSite() {
        val servers = listOf(
            com.nelson.yomukai.domain.model.VideoSource("HD", "https://voe.sx/e/1", "Voe"),
            com.nelson.yomukai.domain.model.VideoSource("HD", "intent://evil", "Malo"),
            com.nelson.yomukai.domain.model.VideoSource("HD", "https://a.com/v.mp4", "Directo", isDirect = true),
            com.nelson.yomukai.domain.model.VideoSource("HD", "https://b.com/e/2", "B"),
            com.nelson.yomukai.domain.model.VideoSource("HD", "https://c.com/e/3", "C")
        )
        assertEquals(listOf("Voe", "B"), embedsToSniff(servers, emptySet()).map { it.serverName })
        assertEquals(listOf("B", "C"), embedsToSniff(servers, setOf("Voe")).map { it.serverName })
        assertTrue(com.nelson.yomukai.data.source.EmbedSniffer.isStreamUrl("https://cdn.x.com/hls/master.m3u8?t=1"))
        assertTrue(com.nelson.yomukai.data.source.EmbedSniffer.isStreamUrl("https://cdn.x.com/v/1080.mp4"))
        assertFalse(com.nelson.yomukai.data.source.EmbedSniffer.isStreamUrl("https://ads.x.com/ads/preroll.mp4"))
        assertFalse(com.nelson.yomukai.data.source.EmbedSniffer.isStreamUrl("https://x.com/player.js"))
        assertTrue(com.nelson.yomukai.data.source.EmbedSniffer.allowedNavigation("voe.sx", "www.voe.sx"))
        assertTrue(com.nelson.yomukai.data.source.EmbedSniffer.allowedNavigation("player.voe.sx", "cdn.voe.sx"))
        assertFalse(com.nelson.yomukai.data.source.EmbedSniffer.allowedNavigation("voe.sx", "casino-ads.com"))
    }

    private fun s(name: String, direct: Boolean) = VideoSource("HD", "https://x/$name", name, direct)

    @Test
    fun prefersSameServerThenFirstDirect() {
        val servers = listOf(s("web", false), s("HLS", true), s("MP4", true))
        assertEquals("MP4", pickServer(servers, "mp4")?.serverName)
        assertEquals("HLS", pickServer(servers, "")?.serverName)
    }

    @Test
    fun skipsFailedServersAndGivesUpWhenNoneIsLeft() {
        val servers = listOf(s("web", false), s("HLS", true), s("MP4", true))
        assertEquals("MP4", com.nelson.yomukai.ui.player.nextServer(servers, setOf("HLS"))?.serverName)
        assertNull(com.nelson.yomukai.ui.player.nextServer(servers, setOf("HLS", "MP4")))
    }

    @Test
    fun episodeProgressIsTimeNotPages() {
        assertEquals("12:34", com.nelson.yomukai.ui.details.formatEpisodeTime(754))
        assertEquals("1:06:40", com.nelson.yomukai.ui.details.formatEpisodeTime(4000))
    }

    @Test
    fun noDirectServerMeansNoAutoplay() {
        assertNull(pickServer(listOf(s("web", false)), "web"))
    }
}
