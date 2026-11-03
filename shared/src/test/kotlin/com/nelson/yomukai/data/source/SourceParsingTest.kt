package com.nelson.yomukai.data.source

import com.nelson.yomukai.domain.model.PublicationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceParsingTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    @Test
    fun parsesChapterNumbersFromTitles() {
        assertEquals(12.5f, SourceUtils.parseChapterNumber("Capítulo 12.5 - El regreso", 0f))
        assertEquals(7f, SourceUtils.parseChapterNumber("Chapter 7: Start", 0f))
        assertEquals(3.5f, SourceUtils.parseChapterNumber("Cap. 3,5", 0f))
        assertEquals(42f, SourceUtils.parseChapterNumber("Vol.2 Ch.42", 0f))
        assertEquals(9f, SourceUtils.parseChapterNumber("Prólogo", 9f))
    }

    @Test
    fun convertsHtmlIntoParagraphs() {
        val text = SourceUtils.htmlToText("<div><p>Uno</p><script>x()</script><p> Dos </p><p></p></div>")
        assertEquals("Uno\n\nDos", text)
        assertEquals("a\n\nb", SourceUtils.normalizeParagraphs("a\r\n\r\n\r\nb\n"))
    }

    @Test
    fun normalizesPublicationStatus() {
        assertEquals(PublicationStatus.ONGOING, PublicationStatus.parse("Ongoing"))
        assertEquals(PublicationStatus.ONGOING, PublicationStatus.parse("En emisión"))
        assertEquals(PublicationStatus.COMPLETED, PublicationStatus.parse("Finalizado"))
        assertEquals(PublicationStatus.HIATUS, PublicationStatus.parse("Hiatus"))
        assertEquals(PublicationStatus.UNKNOWN, PublicationStatus.parse(""))
    }

    @Test
    fun extractsFlameSeriesFromNextData() {
        val props = nextPageProps(fixture("flame_series.html"))
        val series = checkNotNull(props.obj("series"))
        assertEquals("Youngest Son of the Renowned Swordsmanship Clan", series.str("title"))
        val chapters = props.arr("chapters").objects()
        assertTrue(chapters.size > 100)
        assertTrue(chapters.all { it.str("token")?.isNotBlank() == true })
    }

    @Test
    fun extractsFlameChapterImagesInOrder() {
        val chapter = checkNotNull(nextPageProps(fixture("flame_chapter.html")).obj("chapter"))
        val names = checkNotNull(chapter.obj("images")).entrySet()
            .sortedBy { it.key.toInt() }
            .map { it.value.asJsonObject.str("name") }
        assertFalse(names.isEmpty())
        assertEquals("YSRSC-218-00.webp", names.first())
        assertEquals(names.sortedBy { it }, names)
    }
}
