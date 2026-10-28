package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.novel.TuNovelaLigeraSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuNovelaLigeraParsingTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    @Test
    fun homeItemsHaveCleanIds() {
        val items = TuNovelaLigeraSource.parseItems(fixture("tnl_home.html"), ".page-item-detail")
        assertTrue(items.size >= 10)
        assertTrue(items.all { '?' !in it.id && it.title.isNotBlank() && it.coverUrl.isNotBlank() })
        assertTrue(items.any { it.id == "https://tunovelaligera.com/novelas/un-reemplazo-para-el-diablo/" })
    }

    @Test
    fun searchItems() {
        val items = TuNovelaLigeraSource.parseItems(fixture("tnl_search.html"), ".c-tabs-item__content")
        assertTrue(items.isNotEmpty())
        assertTrue(items.any { it.title.startsWith("La reencarnación del mejor player") })
        assertTrue(items.all { '?' !in it.id })
    }

    @Test
    fun ajaxChapterList() {
        val id = "https://tunovelaligera.com/novelas/un-reemplazo-para-el-diablo/"
        val chapters = TuNovelaLigeraSource.parseChapters(fixture("tnl_chapters_ajax.html"), id)
        assertTrue(chapters.size >= 20)
        assertEquals(id + "capitulo-27/", chapters.first().url)
        assertEquals(27f, chapters.first().chapterNumber)
        assertFalse(chapters.any { it.url == "#" })
    }

    @Test
    fun chapterText() {
        val text = TuNovelaLigeraSource.parseChapterText(fixture("tnl_chapter.html"))
        assertTrue(text.contains("La mayoría de reyes luchan por tierra"))
        assertTrue(text.contains("\n\n"))
    }
}
