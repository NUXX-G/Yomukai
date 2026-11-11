package com.nelson.yomukai.server

import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** O1: one work, one card; titles nobody can identify do not show (western comics and novels aside). */
class IdentifiedWorksTest {
    private val s = Services(Config.fromEnv(mapOf("DATA_DIR" to Files.createTempDirectory("ykident").toString(), "BACKUPS_ENABLED" to "false")))
    private val index = WorkIndex(s)

    private fun item(source: String, id: String, title: String, type: MediaType) = MediaItem(id, title, "https://x/$id.jpg", type, "", "", source)

    @Test
    fun onlyIdentifiedWorksShowAndEachOnce() = runBlocking {
        index.record("asura", "en", listOf(item("asura", "a1", "Solo Leveling", MediaType.MANHWA), item("asura", "a2", "Random Fan Work", MediaType.MANHWA)), fresh = false)
        index.record("marmota", "en", listOf(item("marmota", "c1", "Batman", MediaType.COMIC)), fresh = false)
        index.groupPending()
        // Nothing confirmed yet: no manhwa card (two names of one work could still be two cards), the comic shows.
        assertEquals(listOf("COMIC"), index.list("en", null, "az", 1).map { it.type })
        assertTrue(index.search("random fan work", "en", null).isEmpty())

        // AniList confirms Solo Leveling: it becomes "al:…" and shows once; the fan work stays out.
        val provisional = s.db.tx { queryOne("SELECT work_key FROM index_items WHERE media_id = 'a1'") { getString(1) } }!!
        s.db.tx {
            update("UPDATE works SET key = 'al:105398', anilist_id = 105398 WHERE key = ?", provisional)
            update("UPDATE index_items SET work_key = 'al:105398' WHERE work_key = ?", provisional)
        }
        val shown = index.list("en", null, "az", 1)
        assertEquals(1, shown.count { it.key == "al:105398" })
        assertTrue(shown.none { it.title == "Random Fan Work" })
        assertEquals(mapOf(105398 to "al:105398"), index.present(listOf(105398, 1)))
    }
}
