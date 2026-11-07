package com.nelson.yomukai.server

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class JellyfinSourceTest {
    private fun ep(id: Char, season: Int, index: Int, name: String = "") =
        JsonParser.parseString("""{"Id":"${id.toString().repeat(32)}","Name":"$name","ParentIndexNumber":$season,"IndexNumber":$index}""").asJsonObject

    @Test
    fun oneSeasonKeepsItsNumbersSeveralAreNumberedOnSpecialsLast() {
        val one = JellyfinSource.episodes("s", listOf(ep('a', 1, 2), ep('b', 1, 1, "Inicio")))
        assertEquals(listOf(2f, 1f), one.map { it.chapterNumber })
        assertEquals("Episodio 1: Inicio", one.last().title)
        val many = JellyfinSource.episodes("s", listOf(ep('a', 2, 1), ep('b', 1, 1), ep('c', 1, 2), ep('d', 0, 1, "OVA")))
        assertEquals(listOf(0f, 3f, 2f, 1f), many.map { it.chapterNumber })
        assertEquals("T2 · Ep. 1", many[1].title)
        assertEquals("Especial: OVA", many[0].title)
        assertTrue(many.all { it.url.startsWith("jellyfin:") })
    }

    @Test
    fun onlyRealIdsAndNoBitratesOutOfRange() {
        assertEquals("a".repeat(32), JellyfinSource.safeId("a".repeat(32)))
        assertTrue(runCatching { JellyfinSource.safeId("../../System/Info") }.isFailure)
        val api = JellyfinApi("http://x", "k")
        assertTrue(api.videoPath("a", "orig").contains("static=true"))
        assertTrue(api.videoPath("a", "999999").contains("VideoBitrate=40000000"))
    }

    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("ykjfs").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
            "BACKUPS_ENABLED" to "false"
        )
    )

    private suspend fun HttpClient.login(name: String, password: String) {
        repeat(50) {
            val r = post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"name":"$name","password":"$password"}""") }
            if (r.status == HttpStatusCode.OK) return
            delay(100)
        }
        error("no se pudo entrar como $name")
    }

    @Test
    fun theNasShowsAsASourceAndItsKeyNeverReachesTheBrowser() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = createClient { install(HttpCookies) }.also { it.login("admin", "admin-password-1") }
        FakeJellyfin("clave-nas-9").use { jf ->
            assertNull(s.sources.get("jellyfin"))
            admin.put("/api/admin/integrations") { contentType(ContentType.Application.Json); setBody("""{"jellyfinUrl":"${jf.url}","jellyfinKey":"clave-nas-9"}""") }
            assertTrue("registered once it answers", s.sources.get("jellyfin") != null)
            val latest = admin.get("/api/sources/jellyfin/latest").bodyAsText()
            assertTrue(latest, latest.contains("Frieren") && latest.contains("/api/jellyfin/image/"))
            val chapters = admin.get("/api/chapters?s=jellyfin&id=${FakeJellyfin.SERIES}").bodyAsText()
            assertTrue(chapters, chapters.contains("El fin del viaje") && chapters.contains("Episodio 2"))
            val videos = admin.get("/api/videos?s=jellyfin&c=jellyfin:${FakeJellyfin.EP1}").bodyAsText()
            assertTrue(videos, videos.contains("/api/jellyfin/stream/${FakeJellyfin.EP1}?q=orig"))
            assertFalse("the key never leaves", latest.contains("clave-nas-9") || chapters.contains("clave-nas-9") || videos.contains("clave-nas-9"))
            val full = admin.get("/api/jellyfin/stream/${FakeJellyfin.EP1}?q=orig")
            assertEquals(HttpStatusCode.OK, full.status)
            assertEquals("abcdefghij", full.bodyAsText())
            val part = admin.get("/api/jellyfin/stream/${FakeJellyfin.EP1}?q=orig") { headers { append("Range", "bytes=0-3") } }
            assertEquals(HttpStatusCode.PartialContent, part.status)
            assertEquals("bytes 0-3/10", part.headers["Content-Range"])
            assertEquals(HttpStatusCode.BadRequest, admin.get("/api/jellyfin/stream/not-an-id").status)
            assertEquals("JPEGDATA", admin.get("/api/jellyfin/image/${FakeJellyfin.SERIES}").bodyAsText())
        }
        // Gone when the home server stops answering.
        s.jellyfin.refresh()
        assertNull(s.sources.get("jellyfin"))
        assertEquals(HttpStatusCode.ServiceUnavailable, admin.get("/api/jellyfin/stream/${FakeJellyfin.EP1}").status)
    }
}
