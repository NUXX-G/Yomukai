package com.nelson.yomukai.ui

import com.nelson.yomukai.domain.model.VideoSource
import com.nelson.yomukai.ui.player.pickServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerServerTest {
    private fun s(name: String, direct: Boolean) = VideoSource("HD", "https://x/$name", name, direct)

    @Test
    fun prefersSameServerThenFirstDirect() {
        val servers = listOf(s("web", false), s("HLS", true), s("MP4", true))
        assertEquals("MP4", pickServer(servers, "mp4")?.serverName)
        assertEquals("HLS", pickServer(servers, "")?.serverName)
    }

    @Test
    fun noDirectServerMeansNoAutoplay() {
        assertNull(pickServer(listOf(s("web", false)), "web"))
    }
}
