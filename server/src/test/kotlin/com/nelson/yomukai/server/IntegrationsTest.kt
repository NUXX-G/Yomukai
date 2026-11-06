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
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
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
}
