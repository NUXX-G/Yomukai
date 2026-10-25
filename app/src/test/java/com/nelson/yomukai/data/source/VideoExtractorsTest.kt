package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.anime.VideoExtractors
import com.nelson.yomukai.ui.web.WebCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExtractorsTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    @Test
    fun okruExposesHlsManifest() {
        val url = VideoExtractors.okru(fixture("okru_embed.html"))
        assertTrue(url!!.startsWith("https://") && "video.m3u8" in url)
        assertTrue("&expires=" in url) // JSON & escapes decoded
    }

    @Test
    fun mp4uploadExposesDirectFile() {
        val url = VideoExtractors.mp4upload(fixture("mp4upload_embed.html"))
        assertTrue(url!!.startsWith("https://") && url.endsWith("/video.mp4"))
    }

    @Test
    fun mp4uploadEmbedUrlNormalization() {
        assertEquals("https://www.mp4upload.com/embed-abc123.html", VideoExtractors.embedUrlForMp4upload("https://www.mp4upload.com/abc123"))
        assertEquals("https://www.mp4upload.com/embed-abc123.html", VideoExtractors.embedUrlForMp4upload("https://www.mp4upload.com/embed-abc123.html"))
    }

    @Test
    fun youruploadReadsOgVideoOrPlayerConfig() {
        assertEquals(
            "https://vidcache.net/a/b.mp4",
            VideoExtractors.yourupload("""<meta property="og:video" content="https://vidcache.net/a/b.mp4">""")
        )
        assertEquals(
            "https://vidcache.net/c.mp4?x=1",
            VideoExtractors.yourupload("""<script>jwplayer().setup({ file: 'https://vidcache.net/c.mp4?x=1' })</script>""")
        )
        assertNull(VideoExtractors.yourupload("<html></html>"))
    }

    @Test
    fun webCaptureRecognizesStreamsButNotSegments() {
        assertTrue(WebCapture.isVideoUrl("https://cdn.example.com/master.m3u8?token=1"))
        assertTrue(WebCapture.isVideoUrl("https://player.zilla-networks.com/m3u8/abc"))
        assertTrue(WebCapture.isVideoUrl("https://files.example.com/movie.mp4"))
        assertFalse(WebCapture.isVideoUrl("https://cdn.example.com/seg-001.ts"))
        assertFalse(WebCapture.isVideoUrl("https://example.com/page.html"))
    }

    @Test
    fun webCaptureNormalizesAddressBarInput() {
        assertEquals("https://example.com/a", WebCapture.normalizeUrl(" https://example.com/a "))
        assertEquals("https://example.com", WebCapture.normalizeUrl("example.com"))
        assertTrue(WebCapture.normalizeUrl("one piece").startsWith("https://duckduckgo.com/?q=one+piece"))
        assertEquals("https://duckduckgo.com/", WebCapture.normalizeUrl(""))
    }

    @Test
    fun webCaptureKeepsLazyPagesFromTheDominantFolder() {
        val raw = listOf(
            "120x40 https://site.com/logo.png",
            "900x1300 https://cdn.site.com/ch14/001.webp",
            "900x1300 https://cdn.site.com/ch14/002.webp?v=1",
            "0x0 https://cdn.site.com/ch14/003.webp",
            "0x0 https://cdn.site.com/ch14/004.webp",
            "600x600 https://ads.example/banner.jpg"
        )
        val pages = WebCapture.selectPages(raw.mapNotNull(WebCapture::parseEntry))
        assertEquals(
            listOf(
                "https://cdn.site.com/ch14/001.webp",
                "https://cdn.site.com/ch14/002.webp?v=1",
                "https://cdn.site.com/ch14/003.webp",
                "https://cdn.site.com/ch14/004.webp"
            ),
            pages
        )
    }
}
