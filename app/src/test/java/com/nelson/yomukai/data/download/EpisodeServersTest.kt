package com.nelson.yomukai.data.download

import com.nelson.yomukai.data.network.HttpException
import com.nelson.yomukai.data.source.anime.AnimeAv1Source
import com.nelson.yomukai.domain.model.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeServersTest {
    private val hls = VideoSource("Sub español • HLS", "https://player.zilla-networks.com/m3u8/abc", "HLS", true, mapOf("Referer" to "https://player.zilla-networks.com/"))
    private val mp4 = VideoSource("Sub español • MP4", "https://a4.mp4upload.com:183/d/x/video.mp4", "MP4Upload", true, mapOf("Referer" to "https://www.mp4upload.com/"))
    private val voe = VideoSource("Sub español", "https://voe.sx/e/abc", "Voe")
    private val plain = VideoSource("MP4", "http://example.com/v.mp4", "Plano", true)

    @Test
    fun onlyDirectHttpsServersAreDownloadable() {
        assertTrue(EpisodeServers.isDownloadable(hls))
        assertTrue(EpisodeServers.isDownloadable(mp4))
        assertFalse(EpisodeServers.isDownloadable(voe))
        assertFalse(EpisodeServers.isDownloadable(plain))
    }

    @Test
    fun allDownloadableServersAreTriedInSourceOrder() {
        assertEquals(listOf("HLS", "MP4Upload"), EpisodeServers.candidates(listOf(hls, voe, mp4, plain), "").map { it.serverName })
    }

    @Test
    fun chosenServerGoesFirst() {
        assertEquals(listOf("MP4Upload", "HLS"), EpisodeServers.candidates(listOf(hls, voe, mp4), "mp4upload").map { it.serverName })
        // A preference that is no longer offered falls back to the rest.
        assertEquals(listOf("HLS", "MP4Upload"), EpisodeServers.candidates(listOf(hls, mp4), "Voe").map { it.serverName })
    }

    @Test
    fun realAnimeAv1EpisodeKeepsEveryDirectServerAsCandidate() {
        // Embed block captured from animeav1.com/media/tensei-shitara-ken-deshita-ii/5 (2026-10-29).
        val html = """embeds:{SUB:[{server:"HLS",url:"https://player.zilla-networks.com/play/3ff0969ec3011beae4b412fa297e3b43"},""" +
            """{server:"UPNShare",url:"https://animeav1.uns.bio/#ed91yl"},{server:"Voe",url:"https://voe.sx/e/ymx8rir6nikd"},""" +
            """{server:"Byse",url:"https://byselapuix.com/e/l9oi771whzkq"},{server:"MP4Upload",url:"https://www.mp4upload.com/embed-anp74eb29ltk.html"}]},downloads:{}"""
        val servers = AnimeAv1Source.parseVideoSources(html)
        val resolvedMp4 = servers.map { if (it.serverName == "MP4Upload") mp4.copy(serverName = "MP4Upload") else it }
        assertEquals(listOf("HLS", "MP4Upload"), EpisodeServers.candidates(resolvedMp4, "").map { it.serverName })
    }

    @Test
    fun resolvedServerIsNotReportedAsBrowserOnly() {
        // VideoExtractors returns the resolved MP4Upload stream next to its original embed.
        val embed = VideoSource("Sub español", "https://www.mp4upload.com/embed-x.html", "MP4Upload")
        assertEquals(listOf("Voe"), EpisodeServers.browserOnly(listOf(hls, mp4, voe, embed)))
    }

    @Test
    fun tlsErrorsAreSummarised() {
        // Seen on the API 26 emulator: a multi-line BoringSSL dump in the download error.
        val error = javax.net.ssl.SSLProtocolException(
            "Read error: ssl=0x7a: Failure in SSL library, usually a protocol error\nerror:1e000065:Cipher functions:OPENSSL_internal:BAD_DECRYPT"
        )
        assertEquals("se cortó la conexión segura (TLS)", EpisodeServers.reason(error))
        assertEquals("primera línea", EpisodeServers.reason(java.io.IOException("primera línea\nsegunda")))
    }

    @Test
    fun reasonsAreReadable() {
        assertEquals("el servidor bloquea la descarga (HTTP 403)", EpisodeServers.reason(HttpException(403, "u")))
        assertEquals("el vídeo ya no está disponible (HTTP 404)", EpisodeServers.reason(HttpException(404, "u")))
        assertEquals("no responde (tiempo de espera agotado)", EpisodeServers.reason(java.net.SocketTimeoutException()))
    }

    @Test
    fun failureMessageNamesEveryServerAndHowToContinue() {
        val message = EpisodeServers.failureMessage(
            listOf("HLS" to "el servidor bloquea la descarga (HTTP 403)", "MP4Upload" to "error del servidor (HTTP 500)"),
            listOf("Voe")
        )
        assertTrue(message, "HLS: el servidor bloquea la descarga (HTTP 403)" in message)
        assertTrue(message, "MP4Upload: error del servidor (HTTP 500)" in message)
        assertTrue(message, "Voe solo se puede ver en el navegador" in message)
        assertTrue(message, "elegir otro servidor" in message)
        assertEquals(
            "Este episodio no tiene servidores descargables. Voe, Byse solo se puede ver en el navegador.",
            EpisodeServers.failureMessage(emptyList(), listOf("Voe", "Byse"))
        )
    }

    @Test
    fun hlsProbeFindsInitSegmentOfRealPlaylist() {
        // Head of the zilla-networks media playlist for the episode above.
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:20
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="https://player.zilla-networks.com/segs/3ff0969ec3011beae4b412fa297e3b43/init.html"
            #EXTINF:10.291667,
            https://player.zilla-networks.com/segs/3ff0969ec3011beae4b412fa297e3b43/000.html
        """.trimIndent()
        val url = "https://player.zilla-networks.com/m3u8/3ff0969ec3011beae4b412fa297e3b43"
        assertNull(EpisodeServers.hlsFirstVariant(playlist, url))
        assertEquals("https://player.zilla-networks.com/segs/3ff0969ec3011beae4b412fa297e3b43/init.html", EpisodeServers.hlsFirstSegment(playlist, url))
    }

    @Test
    fun hlsProbeResolvesRelativeVariantsAndSegments() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n720/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=400000\n480/index.m3u8\n"
        assertEquals("https://cdn.example.com/v/720/index.m3u8", EpisodeServers.hlsFirstVariant(master, "https://cdn.example.com/v/master.m3u8"))
        val media = "#EXTM3U\n#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXT-X-ENDLIST\n"
        assertEquals("https://cdn.example.com/v/720/seg0.ts", EpisodeServers.hlsFirstSegment(media, "https://cdn.example.com/v/720/index.m3u8"))
    }
}
