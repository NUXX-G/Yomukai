package com.nelson.yomukai.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone plays the stream inside the server's relay link itself (no video through the VPS). */
class ServerVideosTest {
    @Test
    fun theStreamAndItsHeadersComeOutOfTheRelayLink() {
        // h = base64url of {"Referer":"https://host/"}
        val link = "/api/stream?u=https%3A%2F%2Fcdn.example%2Fv%2Fa.m3u8%3Ft%3D1%26s%3D2&h=eyJSZWZlcmVyIjoiaHR0cHM6Ly9ob3N0LyJ9&k=abc"
        val (url, headers) = ServerVideos.rawStream(link)!!
        assertEquals("https://cdn.example/v/a.m3u8?t=1&s=2", url)
        assertEquals(mapOf("Referer" to "https://host/"), headers)
    }

    @Test
    fun otherLinksAreLeftAlone() {
        assertNull(ServerVideos.rawStream("/api/jellyfin/stream/1"))
        assertNull(ServerVideos.rawStream("https://voe.sx/e/abc"))
    }
}
