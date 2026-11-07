package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.domain.model.Chapter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MediaDownloadsTest {
    private fun ch(n: Float) = Chapter("c$n", "m", "Episodio $n", n, "https://x/$n", "")

    @Test
    fun onlyWhatCameOutAfterTheLastOneOldestFirstAndCapped() {
        val list = listOf(ch(12f), ch(11f), ch(10.5f), ch(10f), ch(9f), ch(0f), ch(11f))
        assertEquals(listOf(10.5f, 11f, 12f), MediaDownloads.newOnes(list, 10.0, 5).map { it.chapterNumber })
        assertEquals("capped", listOf(10.5f, 11f), MediaDownloads.newOnes(list, 10.0, 2).map { it.chapterNumber })
        assertTrue("nothing new", MediaDownloads.newOnes(list, 12.0, 5).isEmpty())
        assertEquals("never more than the global cap", MediaDownloads.MAX_PER_RUN, MediaDownloads.newOnes((1..100).map { ch(it.toFloat()) }, 0.0, 999).size)
    }

    @Test
    fun preferredServerIsTheFirstThatMatchesElseTheFirst() {
        val names = listOf("Okru", "MP4Upload", "Streamtape")
        assertEquals(1, preferServer(names, "mp4upload"))
        assertEquals(0, preferServer(names, "nada"))
        assertEquals(0, preferServer(names, ""))
    }

    @Test
    fun rulesAreClamped() {
        assertEquals(MediaDownloads.MAX_PER_RUN, MediaDownloads.Rules("x".repeat(100), 500).clean().maxPerRun)
        assertEquals(40, MediaDownloads.Rules("x".repeat(100), 0).clean().preferredServer.length)
        assertEquals(1, MediaDownloads.Rules("", -3).clean().maxPerRun)
    }

    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("ykdl").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
            "BACKUPS_ENABLED" to "false"
        )
    )

    private fun ApplicationTestBuilder.session(): HttpClient = createClient { install(HttpCookies) }

    private suspend fun HttpClient.login(name: String, password: String) {
        repeat(50) {
            val r = post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"name":"$name","password":"$password"}""") }
            if (r.status == HttpStatusCode.OK) return
            delay(100)
        }
        error("no se pudo entrar como $name")
    }

    @Test
    fun followsAreForAdminsAndAFinishedDownloadCountsAndTells() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        admin.post("/api/admin/users") { setBody("""{"name":"ana","password":"ana-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }
        assertEquals(HttpStatusCode.Forbidden, ana.get("/api/admin/follows").status)
        assertEquals(HttpStatusCode.Forbidden, ana.post("/api/admin/follows") { contentType(ContentType.Application.Json); setBody("""{"sourceId":"animeflv","mediaId":"x"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, ana.delete("/api/admin/follows/1").status)
        assertEquals("[]", admin.get("/api/admin/follows").bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, admin.post("/api/admin/follows") { contentType(ContentType.Application.Json); setBody("""{"sourceId":"nope","mediaId":"x"}""") }.status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/admin/follows/99").status)

        val adminId = s.db.tx { queryOne("SELECT id FROM users WHERE name = 'admin'") { getLong(1) } }!!
        val payload = JsonParser.parseString("""{"title":"Frieren","number":3.0,"auto":true}""").asJsonObject
        val job = Job(
            1, JobTypes.JELLYFIN_DOWNLOAD, 0, payload, null, JobStatus.DONE, 1, 5, 1, adminId, false, 0, 0, Long.MAX_VALUE, null, null
        )
        s.library.finished(job)
        assertEquals(1, s.events.count(adminId, "jellyfin_download"))
        val bell = admin.get("/api/notifications").bodyAsText()
        assertTrue(bell, bell.contains("Ya está en tu Jellyfin: Frieren ep. 3"))
        s.library.failed(job.copy(status = JobStatus.FAILED, error = "Queda poco espacio", payload = JsonObject().apply { addProperty("title", "Frieren"); addProperty("number", 4.0) }))
        assertTrue(admin.get("/api/notifications").bodyAsText().contains("No se pudo descargar Frieren (4): Queda poco espacio"))
    }
}
