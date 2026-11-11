package com.nelson.yomukai.server

import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** F2.2: the next chapter is ready before the user asks; nothing is fetched twice in a row. */
class PrefetcherTest {
    private val asked = CopyOnWriteArrayList<String>()

    private val fake = object : MangaSource {
        override val id = "fake"
        override val name = "Falsa"
        override val baseUrl = "https://fake.example"
        override val language = "ES"
        override val supportedTypes = setOf(MediaType.MANGA)
        override suspend fun fetchLatestUpdates(page: Int) = Result.success(emptyList<MediaItem>())
        override suspend fun searchMedia(query: String) = Result.success(emptyList<MediaItem>())

        // Out of order on purpose, like many sites (newest first).
        override suspend fun fetchChapterList(mediaId: String) = Result.success(
            listOf(3f, 1f, 2f).map { Chapter("c${it.toInt()}", mediaId, "Cap. ${it.toInt()}", it, "https://fake.example/c${it.toInt()}", "") }
        )

        override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> {
            asked += chapterUrl
            return Result.success(listOf("$chapterUrl/1.jpg"))
        }
    }

    @Test
    fun theNextChapterIsReadyBeforeTheUserAsks() = runBlocking {
        val s = Services(Config.fromEnv(mapOf("DATA_DIR" to Files.createTempDirectory("ykpre").toString(), "BACKUPS_ENABLED" to "false")))
        s.sources.setExtra(listOf(fake))
        val pre = Prefetcher(s.catalog, s.db, s.sources)
        assertEquals("c2", pre.nextOf("fake", "m", "c1")?.id)
        assertNull("the last one has no next", pre.nextOf("fake", "m", "c3"))

        pre.afterProgress("fake", "m", "c1", 0.1)
        repeat(50) { if (asked.isEmpty()) delay(100) }
        assertEquals(listOf("https://fake.example/c2"), asked.toList())
        // Saving progress again in the same chapter does not fetch again.
        pre.afterProgress("fake", "m", "c1", 0.5)
        delay(300)
        assertEquals(1, asked.size)
        // What the reader asks next is already there.
        assertTrue(s.catalog.pages("fake", "https://fake.example/c2").isSuccess)
        assertEquals(1, asked.size)
    }
}
