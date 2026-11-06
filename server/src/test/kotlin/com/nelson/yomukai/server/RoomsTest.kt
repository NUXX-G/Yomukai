package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Several people in one room over real WebSockets: state, loading pause, chat, reactions, host hand-over, origin check. */
class RoomsTest {
    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("ykrooms").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
            "BACKUPS_ENABLED" to "false"
        )
    )

    private fun ApplicationTestBuilder.session(): HttpClient = createClient {
        install(HttpCookies)
        install(WebSockets)
    }

    private suspend fun HttpClient.login(name: String, password: String) {
        repeat(50) {
            val r = post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"name":"$name","password":"$password"}""") }
            if (r.status == HttpStatusCode.OK) return
            delay(100)
        }
        error("no se pudo entrar como $name")
    }

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    /** Next message of type [type] (others are skipped). */
    private suspend fun DefaultClientWebSocketSession.next(type: String): JsonObject = withTimeout(5_000) {
        while (true) {
            val f = incoming.receive()
            if (f is Frame.Text) json(f.readText()).takeIf { it["t"].asString == type }?.let { return@withTimeout it }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun DefaultClientWebSocketSession.say(text: String) = send(Frame.Text(text))

    @Test
    fun aRoomKeepsEveryoneTogether() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        listOf("ana", "beto").forEach { admin.post("/api/admin/users") { setBody("""{"name":"$it","password":"$it-password-1"}""") } }
        val ana = session().also { it.login("ana", "ana-password-1") }
        val beto = session().also { it.login("beto", "beto-password-1") }

        val code = json(admin.get("/api/rooms/mine").bodyAsText())["code"].asString
        assertEquals("the persistent room is always the same", code, json(admin.get("/api/rooms/mine").bodyAsText())["code"].asString)
        assertEquals(HttpStatusCode.NotFound, ana.get("/api/rooms/ZZZZZZ").status)

        admin.webSocket("/api/rooms/$code/ws") {
            val host = this
            val hello = next("welcome")
            val adminId = hello["you"].asLong
            assertEquals(adminId, hello["host"].asLong)
            ana.webSocket("/api/rooms/${code.lowercase()}/ws") {
                next("welcome")
                val anaSocket = this
                // The host plays an episode: everyone gets the state stamped by the server.
                host.next("members")
                host.say("""{"t":"state","state":{"sourceId":"animeflv","mediaId":"m","chapterId":"e1","title":"Obra","episode":"Ep. 1","position":30,"playing":true}}""")
                val st = anaSocket.next("state")["state"].asJsonObject
                host.next("state") // the host hears its own state too
                assertEquals("e1", st["chapterId"].asString)
                assertTrue(st["playing"].asBoolean)

                // Ana cannot drive: her state is ignored; the host can let everyone drive.
                anaSocket.say("""{"t":"state","state":{"sourceId":"animeflv","mediaId":"m","chapterId":"e9","position":1,"playing":true}}""")
                anaSocket.say("""{"t":"ping"}""")
                anaSocket.next("pong")

                // Ana loading pauses the party for everyone until she is ready.
                anaSocket.say("""{"t":"loading"}""")
                val paused = host.next("state")
                assertFalse(paused["state"].asJsonObject["playing"].asBoolean)
                assertEquals("ana", paused["waiting"].asJsonArray.single().asString)
                anaSocket.say("""{"t":"ready"}""")
                assertTrue(host.next("state")["state"].asJsonObject["playing"].asBoolean)

                // Chat: cleaned, mentions found, kept for whoever comes later. Reactions only from the fixed set.
                anaSocket.say("""{"t":"chat","text":"  hola @admin \u0007","spoiler":true}""")
                val chat = host.next("chat")["msg"].asJsonObject
                assertEquals("hola @admin", chat["text"].asString)
                assertEquals("admin", chat["mentions"].asJsonArray.single().asString)
                assertTrue(chat["spoiler"].asBoolean)
                anaSocket.say("""{"t":"react","emoji":"<script>"}""")
                anaSocket.say("""{"t":"react","emoji":"🔥"}""")
                assertEquals("🔥", host.next("react")["emoji"].asString)

                beto.webSocket("/api/rooms/$code/ws") {
                    val late = next("welcome")
                    assertEquals("e1", late["state"].asJsonObject["chapterId"].asString)
                    assertEquals("hola @admin", late["history"].asJsonArray.last().asJsonObject["text"].asString)
                }
                assertTrue(s.rooms.active().single()["members"].toString().contains("ana"))
            }
        }
        // Everyone left: the room is no longer live, but it still exists.
        delay(200)
        assertTrue(s.rooms.active().isEmpty())
        assertEquals(1, s.events.count(1, "room_hosted"))
    }

    @Test
    fun theHostPassesOnAndOtherSitesCannotConnect() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        admin.post("/api/admin/users") { setBody("""{"name":"ana","password":"ana-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }
        val code = json(admin.get("/api/rooms/mine").bodyAsText())["code"].asString

        ana.webSocket("/api/rooms/$code/ws") {
            val anaId = next("welcome")["you"].asLong
            admin.webSocket("/api/rooms/$code/ws") { next("welcome") }
            // The admin came and went; Ana was first, so she hosts all along.
            val members = next("members")
            assertEquals(anaId, members["host"].asLong)
        }

        // A page on another site opening the socket with our cookie is refused.
        admin.webSocket("/api/rooms/$code/ws", request = { header("Origin", "https://malo.example") }) {
            val closed = incoming.receiveCatching()
            assertTrue(closed.isClosed || closed.getOrNull() is Frame.Close)
        }
        // Guests cannot open rooms; the switch in the admin panel turns rooms off.
        s.features.set("rooms", false)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/rooms/mine").status)
    }
}
