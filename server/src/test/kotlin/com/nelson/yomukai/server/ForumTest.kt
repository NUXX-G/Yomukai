package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ForumTest {
    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("ykforum").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
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
    private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post(path) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun threadsRepliesSpoilersModerationAndNotifications() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        listOf("ana", "beto").forEach { admin.postJson("/api/admin/users", """{"name":"$it","password":"$it-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }
        val beto = session().also { it.login("beto", "beto-password-1") }

        // A general thread; the text is kept as written (the web never renders it as HTML).
        assertEquals(HttpStatusCode.BadRequest, ana.postJson("/api/forum", """{"category":"nada","title":"Hola","body":"x"}""").status)
        val id = JsonParser.parseString(
            ana.postJson("/api/forum", """{"category":"offtopic","title":"¿Qué veis?","body":"<img src=x onerror=alert(1)> **hola** @beto"}""").bodyAsText()
        ).asJsonObject["id"].asLong
        val list = JsonParser.parseString(beto.get("/api/forum?c=offtopic").bodyAsText()).asJsonArray
        assertEquals("¿Qué veis?", list.single().asJsonObject["title"].asString)
        // Beto was mentioned: it is in his notifications.
        val notes = beto.json("/api/notifications")
        assertEquals(1, notes["unread"].asInt)
        assertTrue(notes["items"].asJsonArray.single().asJsonObject["text"].asString.contains("mencionado"))
        beto.post("/api/notifications/read")
        assertEquals(0, beto.json("/api/notifications")["unread"].asInt)

        // A spoiler up to chapter 50: hidden (without text) from whoever says they are behind; the author always sees it.
        beto.postJson("/api/forum/$id/posts", """{"body":"Al final muere X","spoilerUntil":50}""")
        val behind = ana.json("/api/forum/$id?upTo=10")["posts"].asJsonArray.map { it.asJsonObject }.last()
        assertTrue(behind["hidden"].asBoolean)
        assertEquals("", behind["body"].asString)
        assertFalse(ana.json("/api/forum/$id?upTo=60")["posts"].asJsonArray.last().asJsonObject["hidden"].asBoolean)
        assertFalse(beto.json("/api/forum/$id")["posts"].asJsonArray.last().asJsonObject["hidden"].asBoolean)
        // F4.3: a shared episode in a post; hidden with the text behind a spoiler; a bad card is refused.
        val card = """{"kind":"chapter","sourceId":"mangadex","mediaId":"m1","chapterId":"c51","title":"Berserk","number":51,"label":"Cap. 51"}"""
        assertEquals(HttpStatusCode.OK, beto.postJson("/api/forum/$id/posts", """{"body":"","card":$card}""").status)
        assertEquals("Berserk", ana.json("/api/forum/$id?upTo=10")["posts"].asJsonArray.last().asJsonObject["card"].asJsonObject["title"].asString)
        beto.postJson("/api/forum/$id/posts", """{"body":"ojo","spoilerUntil":50,"card":$card}""")
        assertFalse(ana.json("/api/forum/$id?upTo=10")["posts"].asJsonArray.last().asJsonObject.has("card"))
        assertEquals(HttpStatusCode.BadRequest, beto.postJson("/api/forum/$id/posts", """{"body":"x","card":{"kind":"work","sourceId":"<x>","mediaId":"m","title":"t"}}""").status)
        // Ana hears about Beto's reply in her thread.
        assertTrue(ana.json("/api/notifications")["items"].asJsonArray.any { it.asJsonObject["kind"].asString == "reply" })

        // Reactions toggle; only the fixed set.
        val postId = ana.json("/api/forum/$id")["posts"].asJsonArray.first().asJsonObject["id"].asLong
        assertEquals(HttpStatusCode.BadRequest, beto.postJson("/api/forum/posts/$postId/react", """{"emoji":"<b>"}""").status)
        beto.postJson("/api/forum/posts/$postId/react", """{"emoji":"🔥"}""")
        assertEquals(1, ana.json("/api/forum/$id")["posts"].asJsonArray.first().asJsonObject["reactions"].asJsonObject["🔥"].asInt)

        // Only the author (or an admin) edits; users cannot moderate.
        assertEquals(HttpStatusCode.BadRequest, beto.put("/api/forum/posts/$postId") { contentType(ContentType.Application.Json); setBody("""{"body":"hackeado"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, beto.put("/api/admin/forum/threads/$id") { contentType(ContentType.Application.Json); setBody("""{"locked":true}""") }.status)

        // Admin closes the thread and silences Beto: both are refused, and it is in the audit log.
        admin.put("/api/admin/forum/threads/$id") { contentType(ContentType.Application.Json); setBody("""{"locked":true,"pinned":true}""") }
        assertEquals(HttpStatusCode.BadRequest, ana.postJson("/api/forum/$id/posts", """{"body":"¿hola?"}""").status)
        admin.postJson("/api/admin/mute", """{"name":"beto","minutes":60,"reason":"spam"}""")
        assertEquals(HttpStatusCode.BadRequest, beto.postJson("/api/forum", """{"category":"offtopic","title":"Otro hilo","body":"x"}""").status)
        // Silenced means silenced: no editing old posts nor reacting either.
        val betoPost = ana.json("/api/forum/$id?upTo=60")["posts"].asJsonArray.last().asJsonObject["id"].asLong
        assertEquals(HttpStatusCode.BadRequest, beto.put("/api/forum/posts/$betoPost") { contentType(ContentType.Application.Json); setBody("""{"body":"cambiado"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, beto.postJson("/api/forum/posts/$postId/react", """{"emoji":"😂"}""").status)
        delay(300)
        assertTrue(admin.get("/api/admin/audit").bodyAsText().contains("POST /mute"))

        // Deleting the only post of a thread deletes the thread too: it leaves the list.
        val lone = JsonParser.parseString(
            ana.postJson("/api/forum", """{"category":"recomendaciones","title":"Hilo solo","body":"uno"}""").bodyAsText()
        ).asJsonObject["id"].asLong
        val lonePost = ana.json("/api/forum/$lone")["posts"].asJsonArray.single().asJsonObject["id"].asLong
        ana.delete("/api/forum/posts/$lonePost")
        assertTrue(JsonParser.parseString(ana.get("/api/forum?c=recomendaciones").bodyAsText()).asJsonArray.isEmpty)

        // The switch in the admin panel turns the forum off.
        s.features.set("forum", false)
        assertEquals(HttpStatusCode.NotFound, ana.get("/api/forum?c=offtopic").status)
    }

    @Test
    fun discordMessagesNeverPingAnyone() {
        val body = JsonParser.parseString(Notifier.discordBody(Notifier.neutralize("@everyone mira [esto](https://x) **ya** <@123>"))).asJsonObject
        val content = body["content"].asString
        assertFalse(content.contains("@everyone"))
        assertFalse(content.contains("<@123>"))
        assertTrue(content.contains("""\[esto\]"""))
        assertTrue(body["allowed_mentions"].asJsonObject["parse"].asJsonArray.isEmpty)
    }
}
