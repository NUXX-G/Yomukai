package com.nelson.yomukai.data

import com.nelson.yomukai.data.sync.ServerSync
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSessionScopeTest {
    private val server = "https://yomukai.example.com".toHttpUrl()

    @Test
    fun theSessionOnlyGoesToTheServerItself() {
        assertTrue(ServerSync.sameServer(server, "https://yomukai.example.com/api/jellyfin/stream/1?q=720".toHttpUrl()))
        assertTrue(ServerSync.sameServer(server, "https://YOMUKAI.example.com/api/x".toHttpUrl()))
        assertFalse(ServerSync.sameServer(server, "http://yomukai.example.com/api/x".toHttpUrl()))
        assertFalse(ServerSync.sameServer(server, "https://yomukai.example.com:8443/api/x".toHttpUrl()))
        assertFalse(ServerSync.sameServer(server, "https://evil.example.com/yomukai.example.com".toHttpUrl()))
        assertFalse(ServerSync.sameServer(server, "https://cdn.animeflv.net/video.mp4".toHttpUrl()))
    }
}
