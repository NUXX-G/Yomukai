package com.nelson.yomukai.worker

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MediaLibraryHandlersTest {
    @Test
    fun filesStayInsideTheLibrary() {
        val root = Files.createTempDirectory("lib").toFile()
        assertTrue(inside(root, "Serie (2020)/Season 01/Serie - S01E01.mkv").path.startsWith(root.canonicalPath))
        assertTrue(runCatching { inside(root, "../fuera.mkv") }.exceptionOrNull() is PermanentJobFailure)
    }

    @Test
    fun aDownloadWithoutTheLibraryFolderFailsForGood() = runBlocking {
        val dir = Files.createTempDirectory("work").toFile()
        val job = ClaimedJob(1, "jellyfin_download", JsonObject().apply { add("config", JsonObject()) }, 1)
        val error = runCatching { JellyfinDownloadHandler().run(job, JobContext(dir, okhttp3.OkHttpClient())) }.exceptionOrNull()
        assertTrue(error is PermanentJobFailure)
        assertEquals("Falta la carpeta de anime en el panel", error!!.message)
    }
}
