package com.nelson.yomukai.ui

import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.repository.PageData
import com.nelson.yomukai.ui.reader.NovelItem
import com.nelson.yomukai.ui.reader.NovelItems
import com.nelson.yomukai.ui.reader.ReaderItem
import com.nelson.yomukai.ui.reader.ReaderItems
import com.nelson.yomukai.ui.reader.Segment
import com.nelson.yomukai.ui.reader.chunkForSpeech
import com.nelson.yomukai.ui.reader.filterChapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderItemsTest {
    private fun chapter(id: String, n: Float, title: String = "Capítulo $n") =
        ChapterEntity("s", id, "m", title, n, "u$id", "", n.toInt())

    private fun pages(count: Int) = List(count) { PageData("img$it", emptyMap()) }

    private val segments = listOf(
        Segment(chapter("c1", 1f), pages(4), 2),
        Segment(chapter("c2", 2f), pages(3), 0)
    )

    @Test
    fun buildsHeadersAndPagesAcrossChapters() {
        val items = ReaderItems.build(segments, withHeaders = true)
        assertEquals(2 + 4 + 3, items.size)
        assertTrue(items[0] is ReaderItem.Header && items[5] is ReaderItem.Header)
        assertEquals(2, ReaderItems.build(segments, withHeaders = false).count { it is ReaderItem.Header } + 2)
    }

    @Test
    fun locatesAndFindsPositions() {
        val items = ReaderItems.build(segments, withHeaders = true)
        assertEquals(3, ReaderItems.indexOf(items, "c1", 2))
        assertEquals("c2" to 1, ReaderItems.locate(items, 7))
        assertEquals("c2" to 0, ReaderItems.locate(items, 5)) // header counts as first page
        assertNull(ReaderItems.locate(items, 99))
    }

    @Test
    fun nearEndTriggersLoadingTheNextChapter() {
        val items = ReaderItems.build(segments, withHeaders = true)
        assertFalse(ReaderItems.nearEnd(items, 2))
        assertTrue(ReaderItems.nearEnd(items, items.lastIndex - 3))
    }

    @Test
    fun novelItemsAndSearch() {
        val novel = listOf(
            Segment(chapter("c1", 1f), listOf("Había una vez", "Un dragón ÁRBOL", "Fin"), 0),
            Segment(chapter("c2", 2f), listOf("Otro dragón"), 0)
        )
        val items = NovelItems.build(novel)
        assertEquals(1 + 3 + 1 + 1, items.size)
        assertEquals("c2" to 0, NovelItems.locate(items, 5))
        // only the requested chapter, accent-insensitive
        assertEquals(listOf(2), NovelItems.search(items, "c1", "arbol"))
        assertEquals(listOf(2), NovelItems.search(items, "c1", "dragon"))
        assertEquals(emptyList<Int>(), NovelItems.search(items, "c1", "x"))
        assertTrue(items[0] is NovelItem.Title)
    }

    @Test
    fun speechChunksRespectLimitAndKeepText() {
        val sentence = "Una frase corta. "
        val text = sentence.repeat(400).trim()
        val chunks = chunkForSpeech(text, 500)
        assertTrue(chunks.all { it.length <= 500 })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertEquals(listOf("hola"), chunkForSpeech("hola"))
    }

    @Test
    fun chapterIndexSearchMatchesTitleAndNumber() {
        val list = listOf(chapter("a", 1f, "El comienzo"), chapter("b", 12f, "Reencarnación"), chapter("c", 13f, "Final"))
        assertEquals(listOf("b"), filterChapters(list, "reencarnacion").map { it.chapterId })
        assertEquals(listOf("b"), filterChapters(list, "12").map { it.chapterId })
        assertEquals(3, filterChapters(list, "").size)
    }
}
