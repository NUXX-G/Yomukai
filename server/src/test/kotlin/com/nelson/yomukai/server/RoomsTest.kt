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

    @Test
    fun loggingOutClosesTheRoomSocket() = testApplication {
        val c = config()
        val s = Services(c)
        s.rooms.sessionCheckMs = 200
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        val code = json(admin.get("/api/rooms/mine").bodyAsText())["code"].asString
        admin.webSocket("/api/rooms/$code/ws") {
            next("welcome")
            admin.post("/auth/logout")
            val end = withTimeout(5_000) {
                var f = incoming.receiveCatching()
                while (f.getOrNull() != null && f.getOrNull() !is Frame.Close) f = incoming.receiveCatching()
                f
            }
            assertTrue(end.isClosed || end.getOrNull() is Frame.Close)
        }
    }

    /** F4.1: a common room open to everyone, its queue (several animes), reordering, votes to skip and moving on alone. */
    @Test
    fun commonRoomsQueueVotesAndMoveOnByThemselves() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        listOf("ana", "beto").forEach { admin.post("/api/admin/users") { setBody("""{"name":"$it","password":"$it-password-1"}""") } }
        val ana = session().also { it.login("ana", "ana-password-1") }
        val beto = session().also { it.login("beto", "beto-password-1") }

        val room = json(ana.post("/api/rooms") { contentType(ContentType.Application.Json); setBody("""{"name":"Viernes de anime","open":true}""") }.bodyAsText())
        val code = room["code"].asString
        assertTrue(room["open"].asBoolean)
        // Only an admin makes a lasting one; nobody else opens or closes someone's room.
        assertEquals(HttpStatusCode.BadRequest, ana.post("/api/rooms") { contentType(ContentType.Application.Json); setBody("""{"lasting":true}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, beto.post("/api/rooms/$code/open") { contentType(ContentType.Application.Json); setBody("""{"open":false}""") }.status)
        val lasting = json(admin.post("/api/rooms") { contentType(ContentType.Application.Json); setBody("""{"name":"La sala de todos","lasting":true}""") }.bodyAsText())
        // The admin's personal room is still his own, not the common one.
        assertTrue(json(admin.get("/api/rooms/mine").bodyAsText())["code"].asString != lasting["code"].asString)
        val listed = com.google.gson.JsonParser.parseString(beto.get("/api/rooms/open").bodyAsText()).asJsonArray.map { it.asJsonObject["code"].asString }
        assertEquals(listOf(lasting["code"].asString, code), listed)

        ana.webSocket("/api/rooms/$code/ws") {
            val host = this
            next("welcome")
            say("""{"t":"state","state":{"sourceId":"animeflv","mediaId":"frieren","chapterId":"f1","title":"Frieren","episode":"Ep. 1","position":0,"playing":true}}""")
            next("state")
            beto.webSocket("/api/rooms/$code/ws") {
                val guest = this
                val hello = next("welcome")
                assertEquals(0, hello["queue"].asJsonArray.size())
                // Beto queues another anime, Ana one more; bad items are refused with a notice.
                say("""{"t":"queue_add","item":{"sourceId":"animeflv","mediaId":"dandadan","chapterId":"d3","title":"Dandadan","episode":"Ep. 3","cover":"javascript:x"}}""")
                var q = host.next("queue")
                while (q["items"].asJsonArray.size() < 1) q = host.next("queue")
                assertEquals("", q["items"].asJsonArray[0].asJsonObject["cover"].asString)
                host.say("""{"t":"queue_add","item":{"sourceId":"animeflv","mediaId":"frieren","chapterId":"f2","title":"Frieren","episode":"Ep. 2"}}""")
                while (q["items"].asJsonArray.size() < 2) q = guest.next("queue")
                guest.say("""{"t":"queue_add","item":{"sourceId":"animeflv","mediaId":"dandadan","chapterId":"d3","title":"Dandadan"}}""")
                assertTrue(guest.next("notice")["text"].asString.contains("ya está"))
                // Beto does not drive: his reordering is ignored; Ana's goes through.
                val ids = q["items"].asJsonArray.map { it.asJsonObject["id"].asLong }
                guest.say("""{"t":"queue_move","id":${ids[1]},"to":0}""")
                host.say("""{"t":"queue_move","id":${ids[1]},"to":0}""")
                q = guest.next("queue")
                assertEquals(listOf(ids[1], ids[0]), q["items"].asJsonArray.map { it.asJsonObject["id"].asLong })

                // Skipping takes more than half: Beto alone is not enough among two; with Ana it skips to the queue's head.
                guest.say("""{"t":"skip"}""")
                q = guest.next("queue")
                assertEquals(2, q["skip"].asJsonObject["needed"].asInt)
                assertEquals(1, q["skip"].asJsonObject["votes"].asJsonArray.size())
                host.say("""{"t":"skip"}""")
                val skipped = guest.next("state")
                assertTrue(skipped["advance"].asBoolean)
                assertEquals("f2", skipped["state"].asJsonObject["chapterId"].asString)
                q = guest.next("queue")
                assertEquals(listOf(ids[0]), q["items"].asJsonArray.map { it.asJsonObject["id"].asLong })

                // The episode ends: the room moves on by itself, once, even if two people say so.
                guest.say("""{"t":"ended","chapterId":"f2"}""") // Beto does not drive: ignored
                host.say("""{"t":"ended","chapterId":"f2"}""")
                host.say("""{"t":"ended","chapterId":"f2"}""")
                val auto = guest.next("state")
                assertEquals("d3", auto["state"].asJsonObject["chapterId"].asString)
                assertTrue(auto["state"].asJsonObject["playing"].asBoolean)

                // A shared card in the chat arrives checked.
                guest.say("""{"t":"chat","text":"mirad esto","card":{"kind":"work","sourceId":"mangadex","mediaId":"x","title":"Dandadan","type":"MANGA"}}""")
                val chat = host.next("chat")["msg"].asJsonObject
                assertEquals("Dandadan", chat["card"].asJsonObject["title"].asString)
            }
        }
        delay(200)
        // The queue lives in the database: the room's list says what comes next even with nobody inside.
        val again = com.google.gson.JsonParser.parseString(beto.get("/api/rooms/open").bodyAsText()).asJsonArray.first { it.asJsonObject["code"].asString == code }.asJsonObject
        assertEquals(0, again["queued"].asInt)
        assertEquals(HttpStatusCode.OK, ana.post("/api/rooms/$code/open") { contentType(ContentType.Application.Json); setBody("""{"open":false}""") }.status)
        assertTrue(com.google.gson.JsonParser.parseString(beto.get("/api/rooms/open").bodyAsText()).asJsonArray.none { it.asJsonObject["code"].asString == code })
        s.features.set("open_rooms", false)
        assertEquals("[]", beto.get("/api/rooms/open").bodyAsText())
    }
}
