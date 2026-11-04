package com.nelson.yomukai.data.metadata

import com.nelson.yomukai.data.search.SearchGroup
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterHealthTest {
    private fun item(id: String, source: String = "s") = MediaItem(id, id, "", MediaType.MANGA, "", "", source)

    @Test
    fun classifiesChapterLists() {
        assertEquals(ChapterHealth.EMPTY, ChapterHealth.of(emptyList()))
        assertEquals(ChapterHealth.GOOD, ChapterHealth.of((1..40).map { it.toFloat() }))
        assertEquals(ChapterHealth.GOOD, ChapterHealth.of(listOf(0f) + (1..10).map { it.toFloat() }))
        assertEquals(ChapterHealth.ISSUES, ChapterHealth.of((25..60).map { it.toFloat() }))
        assertEquals(ChapterHealth.ISSUES, ChapterHealth.of((1..20).map { it.toFloat() } + 7f))
        assertEquals(ChapterHealth.GOOD, ChapterHealth.of(listOf(0f, 0f)))
        assertEquals(ChapterHealth.EMPTY, ChapterHealth.of(Result.failure(NoChaptersException())))
        assertEquals(null, ChapterHealth.of(Result.failure(java.io.IOException("caída"))))
    }

    @Test
    fun listsHideEmptyAndPutGoodFirst() {
        val registry = ChapterHealthRegistry()
        val items = listOf(item("a"), item("b"), item("c"), item("d"))
        registry.record("s", "a", ChapterHealth.ISSUES)
        registry.record("s", "b", ChapterHealth.EMPTY)
        registry.record("s", "d", ChapterHealth.GOOD)
        assertEquals(listOf("d", "c", "a"), registry.arrange(items).map { it.id })

        val groups = listOf(SearchGroup(item("a"), listOf(item("x", "t")), 1.0), SearchGroup(item("b"), emptyList(), 0.9), SearchGroup(item("c"), emptyList(), 0.8))
        registry.record("t", "x", ChapterHealth.GOOD)
        val arranged = registry.arrangeGroups(groups)
        assertEquals(listOf("x", "c"), arranged.map { it.primary.id })

        val copy = ChapterHealthRegistry().apply { import(registry.export()) }
        assertEquals(ChapterHealth.EMPTY, copy.get("s", "b"))
    }
}
