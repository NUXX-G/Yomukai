package com.nelson.yomukai.data.work

import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTest {
    private fun item(source: String, title: String, type: MediaType = MediaType.MANHWA, author: String = "", alt: List<String> = emptyList(), cover: String = "https://x/$source.jpg") =
        MediaItem("https://$source.com/${title.hashCode()}", title, cover, type, "", "", source, author = author, altTitles = alt)

    private fun ch(n: Float, title: String = "Capítulo $n") = Chapter("c$n-${title.hashCode()}", "m", title, n, "https://x/$n", "")

    @Test
    fun keys() {
        assertEquals("al:151807", WorkKey.of(MediaType.MANHWA, "Solo Leveling", anilistId = 151807))
        assertEquals("mal:images:121496", WorkKey.of(MediaType.MANHWA, "Solo Leveling", malId = 121496))
        assertNotEquals("the novel is another work", WorkKey.of(MediaType.MANHWA, "Solo Leveling"), WorkKey.of(MediaType.NOVEL, "Solo Leveling"))
        assertEquals("manga and manhwa editions share a family", WorkKey.of(MediaType.MANGA, "Solo Leveling"), WorkKey.of(MediaType.MANHWA, "Solo Leveling"))
        assertTrue(WorkKey.isCertain("al:1"))
    }

    @Test
    fun oneCardPerWorkAcrossSources() {
        val cards = WorkGrouper.group(
            listOf(
                item("asura", "Solo Leveling", author = "Chugong"),
                item("flame", "The Greatest Estate Developer"),
                item("olympus", "Solo Leveling", author = "Chugong, Dubu"),
                item("mangadex", "Na Honjaman Level Up", alt = listOf("Solo Leveling"), author = "Chugong"),
                item("skynovels", "Solo Leveling", type = MediaType.NOVEL, author = "Chugong")
            ),
            preferredSources = listOf("olympus")
        )
        val solo = cards.first()
        assertEquals(listOf("olympus", "asura", "mangadex"), solo.items.map { it.sourceId })
        assertEquals("the novel stays apart", 3, cards.size)
        assertTrue(cards.any { it.primary.type == MediaType.NOVEL })
        assertNull(solo.uncertainWith)
    }

    @Test
    fun doubtfulMatchesAreNotMergedButFlagged() {
        val cards = WorkGrouper.group(
            listOf(
                item("asura", "Leviathan", author = "Lee Gyuntak"),
                item("flame", "Leviathan", author = "Otro Autor")
            )
        )
        assertEquals(2, cards.size)
        assertEquals(cards[0].key, cards[1].uncertainWith)
    }

    @Test
    fun knownIdsWinOverNames() {
        val a = item("asura", "Mercenary Enrollment")
        val b = item("flame", "Teenage Mercenary")
        val cards = WorkGrouper.group(listOf(a, b), knownKeys = mapOf(WorkKey.itemKey(a) to "al:1", WorkKey.itemKey(b) to "al:1"))
        assertEquals(1, cards.size)
        assertEquals("al:1", cards.single().key)
    }

    @Test
    fun chaptersFromAllSourcesBecomeOneList() {
        val merged = ChapterMerge.merge(
            listOf(
                SourceChapters("olympus", "ES", listOf(ch(1f), ch(2f), ch(3f))),
                SourceChapters("asura", "EN", listOf(ch(1f), ch(2f), ch(3f), ch(4f), ch(5f), ch(7f))),
                SourceChapters("flame", "EN", listOf(ch(0f, "Chapter 6"), ch(0f, "Prólogo especial")))
            ),
            MergePrefs(languages = listOf("ES", "EN"))
        )
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f), merged.chapters.map { it.number })
        assertEquals("Spanish first while it lasts", "olympus", merged.chapters[0].chosen.sourceId)
        assertEquals("then the source with most chapters", "asura", merged.chapters[3].chosen.sourceId)
        assertEquals("number taken from the title", "flame", merged.chapters[5].chosen.sourceId)
        assertEquals(1, merged.unnumbered.size)
        assertTrue(merged.gaps.isEmpty())
    }

    @Test
    fun manualChoiceGapsAndDecimals() {
        val merged = ChapterMerge.merge(
            listOf(
                SourceChapters("a", "ES", listOf(ch(1f), ch(2f), ch(2.5f), ch(10f))),
                SourceChapters("b", "ES", listOf(ch(2f)))
            ),
            MergePrefs(overrides = mapOf("2" to "b"))
        )
        assertEquals("b", merged.chapters.first { it.key == "2" }.chosen.sourceId)
        assertEquals("2.5", merged.chapters.first { it.number == 2.5f }.key)
        assertEquals(listOf(3..9), merged.gaps)
    }

    @Test
    fun progressCountsInEverySource() {
        val lists = listOf(
            SourceChapters("a", "ES", listOf(ch(1f), ch(2f))),
            SourceChapters("b", "EN", listOf(ch(1f), ch(2f), ch(3f)))
        )
        val read = WorkProgress.readNumbers(lists, setOf("a" to lists[0].chapters[1].id))
        assertEquals(setOf("2"), read)
        val merged = ChapterMerge.merge(lists)
        assertEquals(3f, WorkProgress.next(merged, read)!!.number)
        assertEquals(1f, WorkProgress.next(merged, emptySet())!!.number)
    }
}
