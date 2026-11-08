package com.nelson.yomukai.server

import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WorkIndexTest {
    private val s = Services(Config.fromEnv(mapOf("DATA_DIR" to Files.createTempDirectory("ykindex").toString(), "BACKUPS_ENABLED" to "false")))
    private val index = WorkIndex(s)

    private fun item(source: String, id: String, title: String, type: MediaType = MediaType.MANHWA, alt: List<String> = emptyList()) =
        MediaItem(id, title, "https://x/$id.jpg", type, "", "", source, altTitles = alt)

    @Test
    fun titlesOfManySourcesBecomeOneWorkPerLanguage() = runBlocking {
        index.record("asura", "en", listOf(item("asura", "a1", "Solo Leveling"), item("asura", "a2", "Overgeared")), fresh = false)
        index.record("olympus", "es", listOf(item("olympus", "o1", "Solo Leveling"), item("olympus", "o2", "Na Honjaman Level Up", alt = listOf("Overgeared"))), fresh = false)
        index.record("skynovels", "es", listOf(item("skynovels", "n1", "Solo Leveling", MediaType.NOVEL)), fresh = false)
        index.groupPending()

        val es = index.list("es", null, "az", 1)
        val manhwa = es.single { it.type == "MANHWA" && it.title == "Solo Leveling" }
        val novel = es.single { it.type == "NOVEL" }
        assertNotEquals("a novel never merges with its manhwa", manhwa.key, novel.key)
        assertEquals(setOf("en", "es"), manhwa.langs.toSet())
        // In Spanish the work keeps its Spanish source's name; in English, the English one.
        assertTrue(index.list("en", null, "az", 1).any { it.key == manhwa.key })
        assertEquals(listOf("olympus" to "o1"), index.sourcesOf(manhwa.key, "es"))
        assertEquals(listOf("asura" to "a1"), index.sourcesOf(manhwa.key, "en"))
        // Found by any name, accents and case ignored, only once.
        assertEquals(listOf(manhwa.key), index.search("solo lev", "es", setOf("MANHWA")).map { it.key })
        val overgeared = index.search("overgeared", "es", null)
        assertEquals("the same work under two names is one", 1, overgeared.map { it.key }.distinct().size)
        assertTrue(index.list("es", setOf("NOVEL"), "az", 1).all { it.type == "NOVEL" })
    }

    @Test
    fun aTitleFromAniListOpensItsWorkByNameButNeverAnotherSeason() = runBlocking {
        index.record("animeflv", "es", listOf(item("animeflv", "f1", "Ao Ashi Season 2", MediaType.ANIME), item("animeflv", "f2", "Ao Ashi", MediaType.ANIME)), fresh = false)
        index.record("anitaku", "en", listOf(item("anitaku", "t1", "SPY x FAMILY", MediaType.ANIME)), fresh = false)
        index.groupPending()
        val season2 = index.match(MediaType.ANIME, listOf("Aoashi Season 2", "Ao Ashi Season 2"), anilistId = 999_999, lang = "es")
        assertTrue(season2 != null && index.sourcesOf(season2, "es").single().second == "f1")
        assertEquals(index.match(MediaType.ANIME, listOf("Ao Ashi"), null, "es"), index.match(MediaType.ANIME, listOf("Ao Ashi"), null, "en"))
        assertTrue(index.sourcesOf(index.match(MediaType.ANIME, listOf("Ao Ashi"), null, "es")!!, "es").single().second == "f2")
        // "Spy x Family" with other spacing and case is the same work; a manga of the same name is not.
        assertTrue(index.match(MediaType.ANIME, listOf("Spy × Family", "SPY x FAMILY"), null, "es") != null)
        assertEquals(null, index.match(MediaType.MANGA, listOf("SPY x FAMILY"), null, "es"))
        assertEquals(null, index.match(MediaType.ANIME, listOf("Nada parecido"), null, "es"))
    }

    @Test
    fun sexualContentIsHiddenUnlessTheUserSwitchesItOn() = runBlocking {
        val adult = item("olympus", "h1", "Obra Picante").copy(genres = listOf("Acción", "Hentai"))
        val ecchi = item("olympus", "e1", "Obra Ecchi").copy(genres = listOf("ecchi"))
        val clean = item("olympus", "c1", "Obra Limpia").copy(genres = listOf("Acción", "Mature"))
        index.record("olympus", "es", listOf(adult, ecchi, clean), fresh = false)
        index.groupPending()
        // Ecchi is not hidden, only sexual content; "Mature" (violent, serious) is not either.
        assertEquals(listOf("Obra Ecchi", "Obra Limpia"), index.list("es", null, "az", 1).map { it.title })
        assertEquals(3, index.list("es", null, "az", 1, hideAdult = false).size)
        assertTrue(index.search("obra", "es", null).none { it.title == "Obra Picante" })
        // Settings without the switch hide it; with it, nothing is added.
        assertTrue(UserPrefs.parse("{}").globalExclusions.containsAll(UserPrefs.ADULT_GENRES))
        assertTrue(UserPrefs.parse(null).globalExclusions.containsAll(UserPrefs.ADULT_GENRES))
        assertTrue(UserPrefs.parse("""{"showAdult":true}""").globalExclusions.isEmpty())
    }

    @Test
    fun crawlingASourceRecordsItsPagesAndStopsAtTheEnd() = runBlocking {
        val source = object : CatalogueSource {
            override val id = "fake"
            override val name = "Fake"
            override val baseUrl = "https://fake.example"
            override val language = "ES"
            override val supportedTypes = setOf(MediaType.MANGA)
            override suspend fun fetchLatestUpdates(page: Int) =
                Result.success(if (page <= 2) (1..20).map { item("fake", "p$page-$it", "Obra $page $it", MediaType.MANGA) } else emptyList())
            override suspend fun searchMedia(query: String) = Result.success(emptyList<MediaItem>())
            override suspend fun fetchChapterList(mediaId: String) = Result.success(emptyList<Chapter>())
        }
        assertEquals(20, index.crawlPage(source, 1, full = true).size)
        assertEquals(20, index.crawlPage(source, 2, full = true).size)
        assertTrue(index.crawlPage(source, 3, full = true).isEmpty())
        index.groupPending()
        assertEquals(40, index.list("es", null, "updated", 1).size)
        assertEquals(WorkIndex.PAGE_SIZE.coerceAtMost(40), index.list("es", null, "az", 1).size)
    }
}
