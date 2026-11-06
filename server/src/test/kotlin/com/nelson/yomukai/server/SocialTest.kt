package com.nelson.yomukai.server

import com.google.gson.JsonArray
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
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

class SocialTest {
    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("yksocial").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
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
    private suspend fun HttpClient.array(path: String): JsonArray = JsonParser.parseString(get(path).bodyAsText()).asJsonArray
    private suspend fun HttpClient.putJson(path: String, body: String): HttpResponse = put(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private fun png(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun libraryRow(media: String, title: String, status: String = "READING", categories: String = "[]") =
        """{"sourceId":"mangadex","mediaId":"$media","title":"$title","cover":"","type":"MANGA","status":"","author":"",""" +
            """"genres":"Acción|Drama","addedAt":1,"readingStatus":"$status","categories":$categories}"""

    private fun progress(media: String, chapter: String, title: String, label: String) =
        """{"rows":[{"sourceId":"mangadex","chapterId":"$chapter","mediaId":"$media","read":true,"page":0,"total":10,"fraction":1,"updatedAt":${System.currentTimeMillis()}}],
           "history":{"sourceId":"mangadex","mediaId":"$media","chapterId":"$chapter","chapterTitle":"$label","title":"$title","cover":"","type":"MANGA","readAt":${System.currentTimeMillis()}}}"""

    @Test
    fun profilesStatsPrivacyFeedRatingsAndAchievements() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        listOf("ana", "beto").forEach { admin.postJson("/api/admin/users", """{"name":"$it","password":"$it-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }
        val beto = session().also { it.login("beto", "beto-password-1") }

        // Profile: validated fields; images always re-drawn as JPEG with the asked shape.
        assertEquals(HttpStatusCode.BadRequest, ana.putJson("/api/profile", """{"accent":"red"}""").status)
        assertEquals(HttpStatusCode.OK, ana.putJson("/api/profile", """{"bio":"Hola <b>soy</b> Ana","accent":"#FF8A5B","privateCategories":["Secreto"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, ana.putJson("/api/profile", """{"shownTitle":"owl"}""").status)
        assertEquals(HttpStatusCode.OK, ana.post("/api/profile/avatar") { setBody(png(900, 500)) }.status)
        assertEquals(HttpStatusCode.BadRequest, ana.post("/api/profile/banner") { setBody("no soy una imagen".toByteArray()) }.status)
        val avatar = ImageIO.read(beto.get("/api/users/ana/avatar").readRawBytes().inputStream())
        assertEquals(256 to 256, avatar.width to avatar.height)

        // Library with a private category: friends see the rest; the owner sees everything.
        ana.putJson("/api/library", libraryRow("m1", "Berserk"))
        ana.putJson("/api/library", libraryRow("m2", "Diario íntimo", categories = """["Secreto"]"""))
        val seen = beto.json("/api/users/ana")
        assertEquals(listOf("Berserk"), seen["library"].asJsonArray.map { it.asJsonObject["title"].asString })
        assertEquals("Hola <b>soy</b> Ana", seen["profile"].asJsonObject["bio"].asString)
        assertTrue(seen["profile"].asJsonObject["privateCategories"].asJsonArray.isEmpty)
        assertEquals(2, ana.json("/api/users/ana")["library"].asJsonArray.size())

        // Reading becomes feed activity, grouped; bulk marks do not count.
        repeat(3) { ana.postJson("/api/progress", progress("m1", "c$it", "Berserk", "Cap. ${it + 1}")) }
        val bulk = (10..20).joinToString(",") { """{"sourceId":"mangadex","chapterId":"x$it","mediaId":"m1","read":true,"page":0,"total":1,"fraction":1,"updatedAt":1}""" }
        ana.postJson("/api/progress", """{"rows":[$bulk]}""")
        // Reading something kept in a private category is not shown to friends (nor as "watching now").
        ana.postJson("/api/progress", progress("m2", "p1", "Diario íntimo", "Cap. 1"))
        ana.postJson("/api/presence", """{"kind":"reading","title":"Diario íntimo","sourceId":"mangadex","mediaId":"m2"}""")
        assertTrue(beto.json("/api/users/ana")["now"]?.isJsonNull ?: true)
        assertTrue(ana.array("/api/feed").any { it.asJsonObject["title"].asString == "Diario íntimo" })
        val feed = beto.array("/api/feed").map { it.asJsonObject }
        assertTrue(feed.none { it["title"].asString == "Diario íntimo" })
        val entry = feed.single { it["kind"].asString == "chapter" }
        assertEquals(3, entry["count"].asInt)
        assertEquals("Cap. 1", entry["first"].asString)
        assertEquals("Cap. 3", entry["last"].asString)
        val stats = beto.json("/api/users/ana")["stats"].asJsonObject
        assertEquals(15, stats["chapters"].asInt)
        assertEquals(1, stats["streak"].asInt)

        // Who else has it, and how far; hiding the library hides that too.
        assertEquals("ana", beto.array("/api/friends-on?s=mangadex&id=m1").single().asJsonObject["name"].asString)
        ana.putJson("/api/profile", """{"hidden":true}""")
        assertTrue(beto.array("/api/friends-on?s=mangadex&id=m1").isEmpty)
        assertTrue(beto.json("/api/users/ana")["libraryHidden"].asBoolean)
        ana.putJson("/api/profile", """{"hidden":false}""")

        // Ratings: 1..10, group average; a 10 for what the group dislikes is a (secret) achievement.
        assertEquals(HttpStatusCode.BadRequest, beto.putJson("/api/ratings", """{"sourceId":"mangadex","mediaId":"m1","score":11}""").status)
        beto.putJson("/api/ratings", """{"sourceId":"mangadex","mediaId":"m1","title":"Berserk","score":3,"review":"meh"}""")
        ana.putJson("/api/ratings", """{"sourceId":"mangadex","mediaId":"m1","title":"Berserk","score":10}""")
        assertEquals(6.5, ana.json("/api/ratings?s=mangadex&id=m1")["average"].asDouble, 1e-9)
        val anaAchievements = ana.array("/api/achievements").map { it.asJsonObject }
        assertNotNull(anaAchievements.single { it["id"].asString == "questionable_taste" }["unlockedAt"].takeIf { !it.isJsonNull })
        // Beto does not have it: for him it stays secret, even on Ana's profile.
        val onAnaForBeto = beto.json("/api/users/ana")["achievements"].asJsonArray.map { it.asJsonObject }.single { it["id"].asString == "questionable_taste" }
        assertEquals("Gusto cuestionable", onAnaForBeto["name"].asString)
        val forBeto = beto.array("/api/achievements").map { it.asJsonObject }.single { it["id"].asString == "questionable_taste" }
        assertEquals("???", forBeto["name"].asString)
        // The toast is shown once.
        assertTrue(ana.array("/api/achievements/new").map { it.asJsonObject["id"].asString }.contains("questionable_taste"))
        assertTrue(ana.array("/api/achievements/new").isEmpty)
        assertEquals(HttpStatusCode.OK, ana.putJson("/api/profile", """{"shownTitle":"questionable_taste"}""").status)

        // Recommendations: limited text, inbox, and following one counts for the sender.
        assertEquals(HttpStatusCode.BadRequest, ana.postJson("/api/recommend/ana", """{"sourceId":"mangadex","mediaId":"m9"}""").status)
        ana.postJson("/api/recommend/beto", """{"sourceId":"mangadex","mediaId":"m9","title":"Vagabond","message":"Te va a encantar"}""")
        assertEquals("Te va a encantar", beto.array("/api/recommendations").single().asJsonObject["message"].asString)
        beto.putJson("/api/library", libraryRow("m9", "Vagabond"))
        assertEquals(1, s.events.count(s.db.tx { queryOne("SELECT id FROM users WHERE name = 'ana'") { getLong(1) } }!!, "recommendation_followed"))

        // Only a few things can be reported by the apps themselves.
        assertEquals(HttpStatusCode.BadRequest, ana.postJson("/api/achievements/event", """{"kind":"jellyfin_download"}""").status)
        assertEquals(HttpStatusCode.OK, ana.postJson("/api/achievements/event", """{"kind":"skip_opening"}""").status)
    }

    @Test
    fun oldHistoryUnlocksQuietly() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        val id = s.db.tx { queryOne("SELECT id FROM users WHERE name = 'admin'") { getLong(1) } }!!
        // Done before achievements existed: found on the first check, without a toast or a feed entry.
        s.db.tx { update("INSERT INTO progress(user_id, source_id, chapter_id, media_id, read, updated_at) VALUES(?,?,?,?,1,?)", id, "mangadex", "c1", "m1", 1L) }
        s.achievements.check(id, "anything")
        val first = admin.array("/api/achievements").map { it.asJsonObject }.single { it["id"].asString == "first_chapter" }
        assertFalse(first["unlockedAt"].isJsonNull)
        assertTrue(admin.array("/api/achievements/new").isEmpty)
        assertTrue(admin.array("/api/feed").none { it.asJsonObject["kind"].asString == "achievement" })
        assertNull(null)
    }
}
