package com.nelson.yomukai.ui

import com.nelson.yomukai.ui.details.ChapterLabel
import com.nelson.yomukai.ui.details.ChapterLabels
import com.nelson.yomukai.ui.theme.CoverPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsLogicTest {
    @Test
    fun chapterNamesLoseTheirRepetition() {
        assertEquals(ChapterLabel("Cap. 133", null), ChapterLabels.of("Capítulo 133", 133f, false))
        assertEquals(ChapterLabel("Cap. 133", "El regreso"), ChapterLabels.of("Capítulo 133: El regreso", 133f, false))
        assertEquals(ChapterLabel("Cap. 133", "El regreso"), ChapterLabels.of("Solo Leveling - Capítulo 133 - El regreso", 133f, false, "Solo Leveling"))
        assertEquals(ChapterLabel("Cap. 12", null), ChapterLabels.of("Chapter 12", 12f, false))
        assertEquals(ChapterLabel("Cap. 12", null), ChapterLabels.of("Cap. 12 - Cap. 12", 12f, false))
        assertEquals(ChapterLabel("Cap. 10,5", null), ChapterLabels.of("Capítulo 10.5", 10.5f, false))
        assertEquals(ChapterLabel("Ep. 7", null), ChapterLabels.of("Episodio 7", 7f, true))
        assertEquals(ChapterLabel("Cap. 3", "Prologue"), ChapterLabels.of("Vol. 1 Chapter 3: Prologue", 3f, false))
    }

    @Test
    fun theNumberSaidTwiceOrTheInitialsAreNotAName() {
        assertEquals(ChapterLabel("Cap. 11", null), ChapterLabels.of("SL - Capítulo 11", 11f, false, "Solo leveling"))
        assertEquals(ChapterLabel("Cap. 11", "El regreso"), ChapterLabels.of("SL - Capítulo 11: El regreso", 11f, false, "Solo leveling"))
        assertEquals("another number stays", ChapterLabel("Cap. 11", "SL - Capítulo 110"), ChapterLabels.of("SL - Capítulo 110", 11f, false, "Solo leveling"))
    }

    @Test
    fun monstersAloneAreNotHorror() {
        val tags = com.nelson.yomukai.data.metadata.KeywordTagger.infer(
            "Hace 10 años una puerta conectó el mundo real con un mundo lleno de monstruos y algunas personas recibieron el poder para pelear contra ellos."
        )
        assertTrue("Terror" !in tags)
    }

    @Test
    fun chaptersWithoutNumberKeepTheirTitle() {
        assertEquals(ChapterLabel("Epílogo", null), ChapterLabels.of("Epílogo", 0f, false))
    }

    @Test
    fun dominantColorIgnoresGray() {
        val red = 0xFFD0302A.toInt()
        val gray = 0xFF808080.toInt()
        val pixels = IntArray(100) { if (it < 30) red else gray }
        val found = assertNotNullAndGet(CoverPalette.dominant(pixels))
        val (h, s, _) = CoverPalette.toHsl(found)
        assertTrue(h < 15f || h > 345f)
        assertTrue(s > 0.5f)
        assertNull("a grayscale cover has no accent", CoverPalette.dominant(IntArray(100) { gray }))
    }

    @Test
    fun tonesKeepTheHueAndReadWell() {
        val seed = 0xFF2A6FD0.toInt()
        val dark = CoverPalette.tone(seed, 0.07f, 0.28f)
        val accent = CoverPalette.tone(seed, 0.74f)
        assertTrue("accent on dark background must be readable", CoverPalette.contrast(accent, dark) >= 4.5)
        val light = CoverPalette.tone(seed, 0.975f, 0.4f)
        val lightAccent = CoverPalette.tone(seed, 0.36f)
        assertTrue(CoverPalette.contrast(lightAccent, light) >= 4.5)
        val (h1, _, _) = CoverPalette.toHsl(seed)
        val (h2, _, _) = CoverPalette.toHsl(accent)
        assertEquals(h1, h2, 3f)
    }

    private fun assertNotNullAndGet(value: Int?): Int {
        assertNotNull(value)
        return value!!
    }
}
