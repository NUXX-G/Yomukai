package com.nelson.yomukai.server

import com.google.gson.JsonParser
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LearningTest {
    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("yklearn").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
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
    fun ocrIsSharedAndWordsAreReviewed() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        admin.post("/api/admin/users") { setBody("""{"name":"ana","password":"ana-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }

        // OCR from a phone: cleaned and bounded, then served to everyone.
        val q = "s=mangadex&c=cap1&p=3"
        assertEquals(HttpStatusCode.NoContent, admin.get("/api/ocr?$q").status)
        val body = """{"language":"en","boxes":[{"x":-1,"y":0.2,"w":0.3,"h":2,"text":"Hello\u0007 there","kind":"BUBBLE"},
            {"x":0.5,"y":0.5,"w":0.1,"h":0.1,"text":"BOOM","kind":"<script>"},{"x":"a","y":0,"w":0,"h":0,"text":"x"}]}"""
        assertEquals(HttpStatusCode.OK, ana.put("/api/ocr?$q") { contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.BadRequest, ana.put("/api/ocr?s=nope&c=x&p=1") { contentType(ContentType.Application.Json); setBody(body) }.status)
        val ocr = JsonParser.parseString(admin.get("/api/ocr?$q").bodyAsText()).asJsonObject
        val boxes = ocr.getAsJsonArray("boxes").map { it.asJsonObject }
        assertEquals(2, boxes.size)
        assertEquals(0.0, boxes[0]["x"].asDouble, 1e-9)
        assertEquals(1.0, boxes[0]["h"].asDouble, 1e-9)
        assertEquals("Hello there", boxes[0]["text"].asString)
        assertEquals("BUBBLE", boxes[1]["kind"].asString)
        assertEquals("device", ocr["engine"].asString)

        // Saved words: one per word and language, reviewed with FSRS, exported for Anki, private to each user.
        val id = JsonParser.parseString(ana.post("/api/words") { setBody("""{"word":"Sword","lang":"en","meaning":"espada","context":"He drew his sword","source":"Berserk"}""") }.bodyAsText())
            .asJsonObject["id"].asLong
        val again = JsonParser.parseString(ana.post("/api/words") { setBody("""{"word":"sword","lang":"en"}""") }.bodyAsText()).asJsonObject["id"].asLong
        assertEquals(id, again)
        assertEquals(1, JsonParser.parseString(ana.get("/api/words/due").bodyAsText()).asJsonArray.size())
        val reviewed = JsonParser.parseString(ana.post("/api/words/$id/review") { setBody("""{"grade":"good"}""") }.bodyAsText()).asJsonObject
        assertTrue(reviewed.getAsJsonObject("card")["due"].asLong > System.currentTimeMillis() + 86_400_000L)
        assertEquals(0, JsonParser.parseString(ana.get("/api/words/due").bodyAsText()).asJsonArray.size())
        assertTrue(ana.get("/api/words/anki").bodyAsText().contains("sword\tespada\tHe drew his sword\tBerserk"))
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/words/$id/review") { setBody("""{"grade":"good"}""") }.status)
        assertEquals(0, JsonParser.parseString(admin.get("/api/words").bodyAsText()).asJsonArray.size())
        assertEquals("LEARNING", JsonParser.parseString(ana.get("/api/words/levels?lang=en").bodyAsText()).asJsonObject["sword"].asString)
    }
}
