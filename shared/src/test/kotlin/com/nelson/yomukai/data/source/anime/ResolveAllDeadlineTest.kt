package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.domain.model.VideoSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W1: one slow host must not hold the whole episode once another server already gives a direct stream. */
class ResolveAllDeadlineTest {
    private val net = object : HttpFetcher {
        override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean): String {
            delay(60_000); return ""
        }
        override suspend fun postString(url: String, headers: Map<String, String>) = ""
        override suspend fun postJson(url: String, body: String) = ""
        override suspend fun checkImage(url: String, headers: Map<String, String>) = ""
    }

    @After fun reset() { VideoExtractors.preferred = null }

    @Test
    fun answersSoonAfterTheFirstDirectStream() = runBlocking {
        VideoExtractors.preferred = { s ->
            if ("fast" in s.videoUrl) s.copy(videoUrl = "https://cdn.example/v.m3u8", isDirect = true) else { delay(60_000); null }
        }
        val started = System.currentTimeMillis()
        val out = VideoExtractors(net).resolveAll(
            listOf(
                VideoSource(quality = "HD", videoUrl = "https://slow.example/e/1", serverName = "Lento"),
                VideoSource(quality = "HD", videoUrl = "https://fast.example/e/1", serverName = "Rápido")
            )
        )
        val took = System.currentTimeMillis() - started
        assertTrue("tardó $took ms", took < 6_000)
        assertEquals("https://cdn.example/v.m3u8", out.first().videoUrl)
        assertTrue(out.any { it.videoUrl == "https://slow.example/e/1" })
    }
}
