package com.nelson.yomukai.server

import com.nelson.yomukai.domain.model.VideoSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YtDlpTest {
    private val ok = """{"id":"x","url":"https://cdn.host.com/v/x.mp4?t=1","http_headers":{"User-Agent":"UA","Referer":"https://host.com/","Cookie":"secreto","Accept":"*/*"}}"""

    @Test
    fun readsTheStreamAndOnlyTheHeadersItNeeds() {
        val (url, headers) = YtDlp.parse("[info] algo\n$ok\n")!!
        assertEquals("https://cdn.host.com/v/x.mp4?t=1", url)
        assertEquals(mapOf("User-Agent" to "UA", "Referer" to "https://host.com/"), headers)
        assertNull(YtDlp.parse("""{"url":"file:///etc/passwd"}"""))
        assertNull(YtDlp.parse("ERROR: Unsupported URL"))
    }

    @Test
    fun theUrlCanNeverBeReadAsAnOption() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val y = YtDlp(File("yt-dlp"), hostIsPublic = { true }, runner = { cmd, _ -> commands += cmd; ok })
        assertNull(y.resolve(VideoSource("HD", "--exec=rm -rf /", "Malo")))
        assertNull(y.resolve(VideoSource("HD", "https://a.com/x y", "Espacios")))
        assertTrue("nothing ran for bad input", commands.isEmpty())
        val found = y.resolve(VideoSource("HD", "https://host.com/e/abc", "Host"))!!
        assertTrue(found.isDirect)
        assertEquals("--", commands.single()[commands.single().size - 2])
        assertEquals("https://host.com/e/abc", commands.single().last())
        assertTrue("--ignore-config" in commands.single())
    }

    @Test
    fun internalAddressesAreNeverFetched() = runBlocking {
        var runs = 0
        val y = YtDlp(File("yt-dlp"), runner = { _, _ -> runs++; ok })
        assertNull(y.resolve(VideoSource("HD", "http://127.0.0.1:8191/v1", "Interno")))
        assertNull(y.resolve(VideoSource("HD", "http://169.254.169.254/latest/meta-data", "Metadatos")))
        assertNull(y.resolve(VideoSource("HD", "http://localhost/e/1", "Local")))
        assertEquals(0, runs)
        assertTrue(YtDlp(File("yt-dlp")).command("https://x.com/e/1").containsAll(listOf("--use-extractors", "default,-generic")))
    }

    @Test
    fun hostsItCannotHandleAreSkippedForADay() = runBlocking {
        var now = 0L
        var runs = 0
        val y = YtDlp(File("yt-dlp"), clock = { now }, hostIsPublic = { true }, runner = { _, _ -> runs++; null })
        val video = VideoSource("HD", "https://raro.com/e/1", "Raro")
        repeat(5) { y.resolve(video) }
        assertEquals("two failures, then skipped", 2, runs)
        now += 25 * 3_600_000L
        y.resolve(video)
        assertEquals(3, runs)
    }
}
