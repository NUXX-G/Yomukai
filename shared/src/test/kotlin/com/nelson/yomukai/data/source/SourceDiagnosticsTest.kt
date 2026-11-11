package com.nelson.yomukai.data.source

import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The canary: a newest title without chapters yet (LectorTMO) does not make a working source look broken. */
class SourceDiagnosticsTest {
    private class Fake(private val chaptersOf: (String) -> Result<List<Chapter>>) : MangaSource {
        override val id = "fake"
        override val name = "Fake"
        override val baseUrl = "https://fake.example"
        override val language = "ES"
        override val supportedTypes = setOf(MediaType.MANGA)
        override suspend fun fetchLatestUpdates(page: Int) = Result.success(
            listOf("announced", "real", "other").map { MediaItem(it, "Title $it", "", MediaType.MANGA, "", "", id) }
        )
        override suspend fun searchMedia(query: String) = fetchLatestUpdates(1)
        override suspend fun fetchChapterList(mediaId: String) = chaptersOf(mediaId)
        override suspend fun fetchPageList(chapterUrl: String) = Result.success(listOf("https://fake.example/1.jpg"))
    }

    private fun chapter(media: String) = Chapter("c1", media, "Capítulo 1", 1f, "https://fake.example/$media/1", "")

    @Test
    fun aTitleWithoutChaptersYetIsSkipped() = runBlocking {
        val steps = SourceDiagnostics.run(Fake { id -> if (id == "announced") Result.failure(NoChaptersException()) else Result.success(listOf(chapter(id))) })
        assertTrue(steps.first { it.name == "Capítulos" }.ok)
        assertTrue(steps.first { it.name == "Páginas" }.ok)
    }

    @Test
    fun aRealErrorStillCounts() = runBlocking {
        val steps = SourceDiagnostics.run(Fake { Result.failure(java.io.IOException("HTTP 403")) })
        assertFalse(steps.first { it.name == "Capítulos" }.ok)
    }
}
