package com.nelson.yomukai.server

import com.nelson.yomukai.data.work.AdaptationPoint
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

class WorksTest {
    private val config = Config.fromEnv(mapOf("DATA_DIR" to Files.createTempDirectory("ykworks").toString(), "BACKUPS_ENABLED" to "false"))
    private val s = Services(config)
    private val works = s.works
    private val ana = runBlocking { s.db.createUser("ana", "h", admin = false, guest = false, expiresAt = null) }

    private suspend fun seedWork(key: String, type: MediaType, nextAiringAt: Long? = null) = s.db.tx {
        update(
            "INSERT INTO works(key, type, title, cover, next_episode, next_airing_at, updated_at) VALUES(?,?,?,?,?,?,?)",
            key, type.name, "Obra $key", "https://x/c.jpg", 5, nextAiringAt, 1L
        )
    }

    @Test
    fun linkingFillsLibraryAndHistoryWithoutLosingAnything() = runBlocking {
        s.db.putLibrary(ana.id, LibraryRow("asura", "m1", "Solo Leveling", "", "MANHWA", "", "", "", 1, "READING"))
        seedWork("al:1", MediaType.MANHWA)
        works.link("al:1", "asura", "m1", "id")
        val row = s.db.tx { queryOne("SELECT work_key, title FROM library WHERE user_id = ?", ana.id) { getString(1) to getString(2) } }!!
        assertEquals("al:1" to "Solo Leveling", row)
        works.link("al:1", "olympus", "x9", "manual")
        assertEquals(listOf("asura", "olympus"), works.sourcesOf("al:1").map { it.sourceId })
        val item = MediaItem("x9", "Solo", "", MediaType.MANHWA, "", "", "olympus")
        assertEquals(mapOf("olympus|x9" to "al:1"), works.knownKeys(listOf(item)))
        assertEquals(1, s.db.library(ana.id).size)
    }

    @Test
    fun anAutomaticMatchNeverMovesAnExistingLink() = runBlocking {
        seedWork("al:1", MediaType.MANHWA)
        seedWork("al:2", MediaType.MANHWA)
        works.link("al:1", "asura", "m1", "manual")
        works.linkIfNew("al:2", "asura", "m1", "SAME_WORK")
        assertEquals(listOf("asura"), works.sourcesOf("al:1").map { it.sourceId })
        assertTrue(works.sourcesOf("al:2").isEmpty())
        works.linkIfNew("al:2", "olympus", "x9", "SAME_WORK")
        assertEquals(listOf("olympus"), works.sourcesOf("al:2").map { it.sourceId })
    }

    @Test
    fun correctionsKeepTheirHistory() = runBlocking {
        val user = User(ana.id, "ana", false, false, 0, null)
        works.setPoint(user, AdaptationPoint("al:anime", 12, "al:manga", 50f, null))
        works.setPoint(user, AdaptationPoint("al:anime", 12, "al:manga", 52f, 6))
        assertEquals(52f, works.points("al:manga").single().chapter)
        val history = works.history("al:anime")
        assertEquals(2, history.size)
        assertEquals("50.0/-", history.first()["before"])
        assertEquals("52.0/6", history.first()["after"])
        @Suppress("UNCHECKED_CAST")
        val next = works.continuation("al:anime", 12)["next"] as List<com.nelson.yomukai.data.work.Continuation>
        assertEquals(53f, next.single().chapter)
        assertTrue(runCatching { works.setPoint(user, AdaptationPoint("al:anime", 0, "al:manga", 1f, null)) }.isFailure)
    }

    @Test
    fun calendarShowsTheNextEpisodeOfLibraryAnime() = runBlocking {
        val today = LocalDate.of(2026, 11, 6)
        val inThreeDays = today.plusDays(3).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() + 3600
        seedWork("al:9", MediaType.ANIME, inThreeDays)
        s.db.putLibrary(ana.id, LibraryRow("animeflv", "a1", "Anime", "", "ANIME", "", "", "", 1, "READING"))
        works.link("al:9", "animeflv", "a1", "id")
        val entries = works.calendar(User(ana.id, "ana", false, false, 0, null), days = 7, today = today)
        assertEquals(1, entries.size)
        assertEquals(today.plusDays(3).toString(), entries.single().date)
        assertEquals("Episodio 5", entries.single().label)
        assertTrue(works.calendar(User(ana.id, "ana", false, false, 0, null), days = 1, today = today).isEmpty())
    }

    @Test
    fun manualChoicesAreStoredPerUser() = runBlocking {
        val user = User(ana.id, "ana", false, false, 0, null)
        works.choose(user, "al:1", "12", "asura")
        works.choose(user, "al:1", "12", "olympus")
        assertEquals("olympus", s.db.tx { queryOne("SELECT source_id FROM work_choices WHERE user_id = ? AND number_key = '12'", ana.id) { getString(1) } })
        works.choose(user, "al:1", "12", null)
        assertEquals(0, s.db.tx { queryOne("SELECT COUNT(*) FROM work_choices") { getInt(1) } })
    }

    @Test
    fun sharpCoversOnlyComeFromAniListForKnownWorks() = runBlocking {
        s.db.tx {
            update("INSERT INTO works(key, anilist_id, type, title, cover, updated_at) VALUES(?,?,?,?,?,?)", "al:5", 5, "ANIME", "Obra", "https://s4.anilist.co/file/c.png", 1L)
            update("INSERT INTO works(key, type, title, cover, updated_at) VALUES(?,?,?,?,?)", "t:anime:x", "ANIME", "Otra", "https://fuente/x.jpg", 1L)
        }
        works.link("al:5", "animeflv", "a5", "id")
        works.link("t:anime:x", "animeflv", "ax", "name")
        works.loadKnown()
        assertEquals("https://s4.anilist.co/file/c.png", works.betterCover("animeflv", "a5"))
        assertEquals(null, works.betterCover("animeflv", "ax"))
        assertEquals(null, works.betterCover("animeflv", "desconocida"))
    }
}
