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
