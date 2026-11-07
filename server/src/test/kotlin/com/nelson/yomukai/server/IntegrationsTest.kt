package com.nelson.yomukai.server

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** A tiny fake Jellyfin: checks the key and remembers what it was asked to scan. */
class FakeJellyfin(private val key: String) : AutoCloseable {
    val scanned = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val ok = ex.requestHeaders.getFirst("X-Emby-Token") == key
            val (code, body) = when {
                !ok -> 401 to "{}"
                ex.requestURI.path == "/System/Info" -> 200 to """{"ServerName":"casa","Version":"10.10.7"}"""
                ex.requestURI.path == "/Library/Media/Updated" -> {
                    scanned += ex.requestBody.readBytes().decodeToString(); 204 to ""
                }
                ex.requestURI.path == "/Items" && ex.requestURI.query.orEmpty().contains("IsPlayed=true") -> 200 to PLAYED
                ex.requestURI.path == "/Items" && ex.requestURI.query.orEmpty().contains("IsResumable") -> 200 to """{"Items":[]}"""
                ex.requestURI.path == "/Items" -> 200 to ITEMS
                ex.requestURI.path == "/Users" -> 200 to """[{"Id":"$JUSER","Name":"Nelson"}]"""
                ex.requestURI.path == "/UserItems/$EP1/UserData" -> { userData += ex.requestURI.query + " " + ex.requestBody.readBytes().decodeToString(); 200 to "{}" }
                ex.requestURI.path == "/Shows/$SERIES/Episodes" -> 200 to EPISODES
                ex.requestURI.path == "/Videos/$EP1/stream" -> {
                    videoAsks += ex.requestURI.query.orEmpty()
                    val range = ex.requestHeaders.getFirst("Range")
                    ex.responseHeaders.add("Content-Type", "video/x-matroska")
                    if (range != null) { ex.responseHeaders.add("Content-Range", "bytes 0-3/10"); 206 to "abcd" } else 200 to "abcdefghij"
                }
                ex.requestURI.path == "/Items/$SERIES/Images/Primary" -> { ex.responseHeaders.add("Content-Type", "image/jpeg"); 200 to "JPEGDATA" }
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"
    val videoAsks = CopyOnWriteArrayList<String>()
    val userData = CopyOnWriteArrayList<String>()
    override fun close() = server.stop(0)

    companion object {
        const val SERIES = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        const val EP1 = "11111111111111111111111111111111"
        const val EP2 = "22222222222222222222222222222222"
        const val JUSER = "33333333333333333333333333333333"
        private const val PLAYED =
            """{"Items":[{"Id":"$EP2","Type":"Episode","SeriesId":"$SERIES","RunTimeTicks":14400000000,"UserData":{"Played":true,"LastPlayedDate":"2026-11-07T10:00:00.0000000Z"}}]}"""
        private const val ITEMS = """{"Items":[{"Id":"$SERIES","Name":"Frieren","Type":"Series","ProductionYear":2023,"Status":"Ended"}]}"""
        private const val EPISODES =
            """{"Items":[{"Id":"$EP1","Name":"El fin del viaje","IndexNumber":1,"ParentIndexNumber":1},{"Id":"$EP2","Name":"Episode 2","IndexNumber":2,"ParentIndexNumber":1}]}"""
    }
}

class IntegrationsTest {
    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("ykint").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
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
    fun jellyfinSettingsAreSealedAndTheKeyNeverLeaves() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        admin.post("/api/admin/users") { setBody("""{"name":"ana","password":"ana-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }
        FakeJellyfin("secreta-123").use { jf ->
            assertEquals(HttpStatusCode.Forbidden, ana.get("/api/admin/integrations").status)
            suspend fun save(body: String) = admin.put("/api/admin/integrations") { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, save("""{"jellyfinUrl":"javascript:alert(1)"}""").status)
            assertEquals(HttpStatusCode.BadRequest, save("""{"animePath":"/media/../etc"}""").status)
            assertEquals(HttpStatusCode.OK, save("""{"jellyfinUrl":"${jf.url}/","jellyfinKey":"secreta-123","animePath":"/media/anime"}""").status)
            // An empty key field keeps the stored key; the panel only learns that one is set.
            val view = admin.put("/api/admin/integrations") { contentType(ContentType.Application.Json); setBody("""{"jellyfinKey":"","comicsServer":"kavita"}""") }.bodyAsText()
            assertFalse(view.contains("secreta-123"))
            assertTrue(JsonParser.parseString(view).asJsonObject["jellyfinKeySet"].asBoolean)
            val stored = s.db.tx { queryOne("SELECT value FROM integrations") { getString(1) } }!!
            assertFalse("stored sealed", stored.contains("secreta-123"))

            val test = JsonParser.parseString(admin.post("/api/admin/integrations/jellyfin/test").bodyAsText()).asJsonObject
            assertTrue(test["ok"].asBoolean)
            assertEquals("casa", test["server"].asString)
            assertTrue(JellyfinClient(jf.url, "secreta-123").scanPath("/media/anime/Frieren (2023)"))
            assertTrue(jf.scanned.single().contains("/media/anime/Frieren (2023)"))
            assertEquals(null, JellyfinClient(jf.url, "mala").info())
            // Pointing the settings somewhere else forgets the key: it can never be sent to a new address.
            val moved = admin.put("/api/admin/integrations") { contentType(ContentType.Application.Json); setBody("""{"jellyfinUrl":"https://otro.example"}""") }.bodyAsText()
            assertFalse(JsonParser.parseString(moved).asJsonObject["jellyfinKeySet"].asBoolean)
        }
    }

    @Test
    fun onlyAdminsQueueDownloadsAndOnlyWorkersGetVideos() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        admin.post("/api/admin/users") { setBody("""{"name":"ana","password":"ana-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }
        val body = """{"sourceId":"animeflv","mediaId":"x","chapterIds":["1"]}"""
        assertEquals(HttpStatusCode.Forbidden, ana.post("/api/admin/jellyfin/download") { contentType(ContentType.Application.Json); setBody(body) }.status)
        // Without the library folder configured the admin is told what is missing.
        assertTrue(admin.post("/api/admin/jellyfin/download") { contentType(ContentType.Application.Json); setBody(body) }.bodyAsText().contains("carpeta de anime"))
        assertEquals(HttpStatusCode.Unauthorized, ana.post("/worker/videos") { contentType(ContentType.Application.Json); setBody("""{"sourceId":"animeflv","url":"x"}""") }.status)
    }
}
