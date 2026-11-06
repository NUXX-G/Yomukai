package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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

class ThemesTest {
    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("ykthemes").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
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

    private suspend fun HttpClient.json(path: String): JsonObject = JsonParser.parseString(get(path).bodyAsText()).asJsonObject
    private suspend fun HttpClient.send(method: String, path: String, body: String): HttpResponse =
        if (method == "PUT") put(path) { contentType(ContentType.Application.Json); setBody(body) } else post(path) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun themesAreValidatedSharedAndUsed() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        admin.send("POST", "/api/admin/users", """{"name":"ana","password":"ana-password-1"}""")
        val ana = session().also { it.login("ana", "ana-password-1") }

        assertTrue(ana.json("/api/themes")["builtIn"].asJsonArray.size() >= 7)
        // Free CSS never gets in; contrast problems are reported (not refused).
        assertEquals(HttpStatusCode.BadRequest, ana.send("POST", "/api/themes", """{"theme":{"name":"Malo","colors":{"accent":"red;}body{display:none"}}}""").status)
        val check = JsonParser.parseString(ana.send("POST", "/api/themes/check", """{"name":"Gris","colors":{"text":"#777777","canvas":"#808080"}}""").bodyAsText()).asJsonObject
        assertTrue(check["issues"].asJsonArray.size() > 0)

        // Ana publishes a theme; the admin uses it: it is his now, and Ana's "used by a friend" counts once.
        val id = JsonParser.parseString(
            ana.send("POST", "/api/themes", """{"theme":{"name":"Mío","colors":{"accent":"#33aaff"},"font":"serif"},"published":true}""").bodyAsText()
        ).asJsonObject["id"].asLong
        assertEquals(HttpStatusCode.BadRequest, admin.send("PUT", "/api/themes/$id", """{"theme":{"name":"Robado","colors":{}}}""").status)
        admin.send("PUT", "/api/themes/use", """{"id":$id}""")
        admin.send("PUT", "/api/themes/use", """{"id":$id}""")
        assertEquals("Mío", admin.json("/api/themes/current")["theme"].asJsonObject["name"].asString)
        val anaId = s.db.tx { queryOne("SELECT id FROM users WHERE name = 'ana'") { getLong(1) } }!!
        assertEquals(1, s.events.count(anaId, "theme_used"))
        // A private theme cannot be used by others; built-in ones by id; null goes back to the default.
        val hidden = JsonParser.parseString(ana.send("POST", "/api/themes", """{"theme":{"name":"Secreto","colors":{}}}""").bodyAsText()).asJsonObject["id"].asLong
        assertEquals(HttpStatusCode.NotFound, admin.send("PUT", "/api/themes/use", """{"id":$hidden}""").status)
        admin.send("PUT", "/api/themes/use", """{"builtIn":"sepia"}""")
        assertEquals("Sepia", admin.json("/api/themes/current")["theme"].asJsonObject["name"].asString)
        admin.send("PUT", "/api/themes/use", """{}""")
        assertTrue(admin.json("/api/themes/current")["theme"]?.isJsonNull ?: true)
        // A background must be one's own upload.
        assertEquals(HttpStatusCode.BadRequest, admin.send("POST", "/api/themes", """{"theme":{"name":"Fondo","colors":{},"background":12345}}""").status)
        // Backgrounds stay private until they are part of a published theme.
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(800, 450, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val bg = JsonParser.parseString(ana.post("/api/themes/backgrounds") { setBody(png) }.bodyAsText()).asJsonObject["id"].asLong
        val withBg = JsonParser.parseString(
            ana.send("POST", "/api/themes", """{"theme":{"name":"Con fondo","colors":{},"background":$bg}}""").bodyAsText()
        ).asJsonObject["id"].asLong
        assertEquals(HttpStatusCode.OK, ana.get("/api/themes/backgrounds/$bg").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/themes/backgrounds/$bg").status)
        ana.send("PUT", "/api/themes/$withBg", """{"theme":{"name":"Con fondo","colors":{},"background":$bg},"published":true}""")
        assertEquals(HttpStatusCode.OK, admin.get("/api/themes/backgrounds/$bg").status)
    }
}
