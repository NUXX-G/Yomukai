package com.nelson.yomukai.server

import com.nelson.yomukai.data.discovery.AniListDiscover
import com.nelson.yomukai.data.network.HttpFetcher
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/** F5.2 / F5.3: the group's picks of the season and AniList's weekly schedule, with their limits. */
class SeasonCalendarTest {
    private class FakeAniList : HttpFetcher {
        var calls = 0
        override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean) = error("no")
        override suspend fun postString(url: String, headers: Map<String, String>) = error("no")
        override suspend fun postJson(url: String, body: String): String {
            calls++
            return when {
                "airingSchedules" in body ->
                    """{"data":{"p1":{"airingSchedules":[{"episode":3,"airingAt":1794411900,"media":{"id":11,"title":{"romaji":"Uno"},"popularity":5000,""" +
                        """"coverImage":{"extraLarge":"https://s4.anilist.co/a.jpg"}}}]}}}"""
                "id_in" in body ->
                    """{"data":{"list":{"media":[{"id":42,"title":{"romaji":"Kusuriya no Hitorigoto","english":"The Apothecary Diaries"},"popularity":9000,""" +
                        """"coverImage":{"extraLarge":"https://s4.anilist.co/file/x.jpg"}},{"id":66,"title":{"romaji":"Malo"},"coverImage":{"extraLarge":"https://evil.example/x.jpg"}}]}}}"""
                else -> """{"data":{}}"""
            }
        }
        override suspend fun checkImage(url: String, headers: Map<String, String>) = error("no")
    }

    private class Setup(val cal: SeasonCalendar, val http: FakeAniList, val db: Database)

    private fun setup(clock: () -> Long = System::currentTimeMillis): Setup {
        val db = Database(File(Files.createTempDirectory("ykseason").toFile(), "y.db"))
        val http = FakeAniList()
        val cal = SeasonCalendar(
            AniListDiscover(http), db,
            { entries, _ ->
                entries.map { e ->
                    DiscoverItemDto(
                        e.anilistId, e.title, e.names, "/api/img?u=${e.cover}", "", "", e.genres, e.score, e.format, e.type.name, e.synopsis, e.status,
                        e.season, e.year, e.episodes, e.chapters, e.nextEpisode, e.nextAiringAt, null, null, null, e.popularity
                    )
                }
            },
            { "/api/img?u=$it" }, clock, { LocalDate.of(2026, 11, 11) }
        )
        return Setup(cal, http, db)
    }

    private fun user(db: Database, name: String, guest: Boolean = false): User = runBlocking {
        db.tx { update("INSERT INTO users(name, pass_hash, is_admin, is_guest, created_at) VALUES(?,?,0,?,1)", name, "x", if (guest) 1 else 0) }
        val id = db.tx { queryOne("SELECT id FROM users WHERE name = ?", name) { getLong(1) } }!!
        User(id, name, isAdmin = false, isGuest = guest, createdAt = 1, expiresAt = null)
    }

    @Test
    fun theGroupRecommendsAndCommentsAndEveryoneSeesIt() = runBlocking {
        val t = setup()
        val ana = user(t.db, "ana")
        val beto = user(t.db, "beto")
        t.cal.pick(ana, 42, recommend = true, rawComment = "  Maomao es lo mejor\u0007 de la temporada  ")
        t.cal.pick(beto, 42, recommend = false, rawComment = "Aún no la he visto")
        assertEquals(1, t.http.calls) // the title is looked up once, then taken from the table
        val seen = t.cal.picks(beto)
        assertEquals("FALL", seen.season)
        val pick = seen.items.single()
        assertEquals("The Apothecary Diaries", pick.title)
        assertEquals("/api/img?u=https://s4.anilist.co/file/x.jpg", pick.cover)
        assertEquals(1, pick.recommends)
        assertEquals(listOf("Aún no la he visto", "Maomao es lo mejor de la temporada"), pick.comments.map { it.comment })
        assertEquals("beto", pick.mine?.name)
        // Changing your mind replaces your entry; removing it leaves the others.
        t.cal.pick(beto, 42, recommend = true, rawComment = "")
        assertEquals(2, t.cal.picks(ana).items.single().recommends)
        t.cal.unpick(ana, 42)
        val after = t.cal.picks(ana).items.single()
        assertEquals(1, after.recommends)
        assertNull(after.mine)
    }

    @Test
    fun guestsEmptyPicksAndOtherSitesCoversAreRefused() = runBlocking {
        val t = setup()
        val guest = user(t.db, "invitado", guest = true)
        val ana = user(t.db, "ana")
        expectRefused { t.cal.pick(guest, 42, true, "hola") }
        expectRefused { t.cal.pick(ana, 42, recommend = false, rawComment = "   ") }
        expectRefused { t.cal.pick(ana, 0, true, "") }
        expectRefused { t.cal.pick(ana, 12345, true, "") } // AniList does not know it
        // A cover outside AniList is never stored (the proxy would fetch it for anyone).
        t.cal.pick(ana, 66, true, "")
        assertEquals("", t.db.tx { queryOne("SELECT cover FROM season_picks WHERE anilist_id = 66") { getString(1) } })
        assertEquals(500, SeasonCalendar.cleanComment("a".repeat(2_000)).length)
        assertEquals("uno\n\ndos", SeasonCalendar.cleanComment("uno\n\n\n\n\ndos"))
    }

    @Test
    fun tooManyChangesInAMinuteAreSlowedDown() = runBlocking {
        val t = setup()
        val ana = user(t.db, "ana")
        repeat(SeasonCalendar.MAX_WRITES_PER_MINUTE) { t.cal.pick(ana, 42, it % 2 == 0, "comentario $it") }
        expectRefused { t.cal.pick(ana, 42, true, "uno más") }
    }

    @Test
    fun airingIsCachedAndKeptWhenAniListFails() = runBlocking {
        var now = 1_794_400_000_000L
        val t = setup { now }
        val first = t.cal.airing("es")
        assertEquals(listOf(11), first.map { it.anilistId })
        assertEquals(3, first.single().nextEpisode)
        t.cal.airing("es")
        assertEquals(1, t.http.calls)
        now += SeasonCalendar.AIRING_TTL_MS + 1
        assertEquals(first.map { it.anilistId }, t.cal.airing("es").map { it.anilistId })
        assertEquals(2, t.http.calls)
    }

    @Test
    fun picksNeedAnAccountAndTheSwitchOn() = testApplication {
        val c = Config.fromEnv(
            mapOf("DATA_DIR" to Files.createTempDirectory("ykseason2").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1", "BACKUPS_ENABLED" to "false")
        )
        val s = Services(c)
        application { yomukai(c, s) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/calendar/picks").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/calendar/picks/42") { contentType(ContentType.Application.Json); setBody("{}") }.status)
        val admin = createClient { install(HttpCookies) }
        // The admin account is created as the server starts: try again for a moment.
        var tries = 0
        while (tries++ < 50) {
            val r = admin.post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"name":"admin","password":"admin-password-1"}""") }
            if (r.status == HttpStatusCode.OK) break
            kotlinx.coroutines.delay(100)
        }
        assertEquals(HttpStatusCode.OK, admin.get("/api/calendar/picks").status)
        s.features.set("season_social", false)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/calendar/picks").status)
        assertNotEquals(HttpStatusCode.OK, admin.get("/api/calendar/community").status)
    }

    private suspend fun expectRefused(block: suspend () -> Unit) {
        try {
            block()
            fail("should be refused")
        } catch (_: IllegalArgumentException) {
        }
    }
}
