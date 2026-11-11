package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DirectMessagesTest {
    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("ykdm").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
            "BACKUPS_ENABLED" to "false", "GUESTS_ENABLED" to "true"
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
    private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpClient.say(to: String, text: String) = postJson("/api/dm/$to", """{"text":${com.google.gson.Gson().toJson(text)}}""")

    private val card = """{"kind":"episode","sourceId":"animeflv","mediaId":"frieren","chapterId":"frieren-12","title":"Frieren","type":"ANIME",
        "lang":"es","number":12,"label":"Ep. 12","spoiler":true,"cover":"javascript:alert(1)"}"""

    @Test
    fun conversationsUnreadSeenDeleteAndOnlyBetweenTheTwo() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        listOf("ana", "beto", "carla").forEach { admin.postJson("/api/admin/users", """{"name":"$it","password":"$it-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }
        val beto = session().also { it.login("beto", "beto-password-1") }
        val carla = session().also { it.login("carla", "carla-password-1") }

        assertEquals(HttpStatusCode.OK, ana.say("beto", "hola <b>beto</b>").status)
        assertEquals(HttpStatusCode.OK, ana.say("BETO", "¿has visto Frieren?").status)
        val shared = ana.postJson("/api/dm/beto", """{"text":"","card":$card}""")
        assertEquals(HttpStatusCode.OK, shared.status)
        // The card arrives cleaned: the unsafe cover is gone, the rest kept.
        val sharedCard = JsonParser.parseString(shared.bodyAsText()).asJsonObject["card"].asJsonObject
        assertFalse(sharedCard.has("cover") && sharedCard["cover"].asString.isNotEmpty())
        assertEquals("Ep. 12", sharedCard["label"].asString)
        assertEquals(HttpStatusCode.BadRequest, ana.postJson("/api/dm/beto", """{"card":{"kind":"episode","sourceId":"../x","mediaId":"a","chapterId":"b","title":"t"}}""").status)
        assertEquals(HttpStatusCode.BadRequest, ana.say("beto", "   ").status)
        assertEquals(HttpStatusCode.NotFound, ana.say("ana", "yo").status)
        assertEquals(HttpStatusCode.NotFound, ana.say("nadie", "hola").status)

        // Beto: one conversation with three unread, a single notification for all of them, and the badge.
        val list = JsonParser.parseString(beto.get("/api/dm").bodyAsText()).asJsonArray
        assertEquals(1, list.size())
        assertEquals("ana", list[0].asJsonObject["peer"].asJsonObject["name"].asString)
        assertEquals(3, list[0].asJsonObject["unread"].asInt)
        assertEquals(3, beto.json("/api/dm/unread")["unread"].asInt)
        val notes = beto.json("/api/notifications")["items"].asJsonArray.filter { it.asJsonObject["kind"].asString == "dm" }
        assertEquals(1, notes.size)
        assertEquals("/mensajes/ana", notes[0].asJsonObject["link"].asString)
        // Stored as text: shown as text by the clients, never as HTML.
        val history = beto.json("/api/dm/ana")
        val messages = history["messages"].asJsonArray.map { it.asJsonObject }
        assertEquals("hola <b>beto</b>", messages[0]["text"].asString)
        assertFalse(messages[0]["mine"].asBoolean)

        // Seen: Ana sees how far Beto read.
        assertEquals(0L, ana.json("/api/dm/beto")["seenUpTo"].asLong)
        beto.postJson("/api/dm/ana/read", """{"upTo":${messages.last()["id"].asLong}}""")
        assertEquals(messages.last()["id"].asLong, ana.json("/api/dm/beto")["seenUpTo"].asLong)
        assertEquals(0, beto.json("/api/dm/unread")["unread"].asInt)

        // Carla sees none of it, cannot read it and cannot delete Ana's messages.
        assertEquals(0, JsonParser.parseString(carla.get("/api/dm").bodyAsText()).asJsonArray.size())
        assertEquals(0, carla.json("/api/dm/ana")["messages"].asJsonArray.size())
        val first = messages[0]["id"].asLong
        assertEquals(HttpStatusCode.NotFound, carla.delete("/api/dm/messages/$first").status)
        assertEquals(HttpStatusCode.NotFound, beto.delete("/api/dm/messages/$first").status)
        assertEquals(HttpStatusCode.OK, ana.delete("/api/dm/messages/$first").status)
        assertEquals(2, beto.json("/api/dm/ana")["messages"].asJsonArray.size())

        // Search: text and the titles of shared cards, only in your own conversations.
        val found = JsonParser.parseString(beto.get("/api/dm/search?q=frieren").bodyAsText()).asJsonArray
        assertEquals(2, found.size())
        assertEquals(0, JsonParser.parseString(carla.get("/api/dm/search?q=frieren").bodyAsText()).asJsonArray.size())
        assertEquals(HttpStatusCode.BadRequest, beto.get("/api/dm/search?q=a").status)
        // A LIKE wildcard is searched for literally.
        assertEquals(0, JsonParser.parseString(beto.get("/api/dm/search?q=%25%25").bodyAsText()).asJsonArray.size())

        // Blocking: Ana can no longer write to Beto; Beto must unblock to write himself.
        beto.postJson("/api/dm/ana/block", """{"on":true}""")
        assertEquals(HttpStatusCode.Forbidden, ana.say("beto", "¿hola?").status)
        assertEquals(HttpStatusCode.Forbidden, beto.say("ana", "hola").status)
        beto.postJson("/api/dm/ana/block", """{"on":false}""")
        assertEquals(HttpStatusCode.OK, beto.say("ana", "perdona, era sin querer").status)

        // Muting: messages arrive, but no bell and no badge.
        ana.postJson("/api/dm/beto/mute", """{"on":true}""")
        val before = ana.json("/api/notifications")["items"].asJsonArray.size()
        beto.say("ana", "otra cosa")
        assertEquals(before, ana.json("/api/notifications")["items"].asJsonArray.size())
        assertEquals(0, ana.json("/api/dm/unread")["unread"].asInt)
        assertTrue(ana.json("/api/dm/beto")["muted"].asBoolean)

        // Antispam: five messages to someone who never answers, then wait.
        repeat(5) { assertEquals(HttpStatusCode.OK, carla.say("beto", "mensaje $it").status) }
        delay(10)
        val refused = carla.say("beto", "¿estás?")
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(refused.bodyAsText().contains("conteste"))

        // An admin can switch it all off.
        s.features.set("dms", false)
        assertEquals(HttpStatusCode.NotFound, ana.get("/api/dm").status)
        assertEquals(0, ana.json("/api/dm/unread")["unread"].asInt)
    }

    @Test
    fun guestsHaveNoMessagesAndCannotBeWritten() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        admin.postJson("/api/admin/users", """{"name":"ana","password":"ana-password-1"}""")
        val guest = session().also { it.post("/auth/guest") }
        val guestName = s.db.tx { queryOne("SELECT name FROM users WHERE is_guest = 1") { getString(1) } }.orEmpty()
        assertNotEquals(HttpStatusCode.OK, guest.get("/api/dm").status)
        assertNotEquals(HttpStatusCode.OK, guest.say("ana", "hola").status)
        val ana = session().also { it.login("ana", "ana-password-1") }
        if (guestName.isNotEmpty()) assertEquals(HttpStatusCode.NotFound, ana.say(guestName, "hola").status)
        assertEquals(HttpStatusCode.Unauthorized, session().get("/api/dm").status)
    }
}
