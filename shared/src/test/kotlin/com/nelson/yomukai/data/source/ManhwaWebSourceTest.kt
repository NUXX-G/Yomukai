package com.nelson.yomukai.data.source

import com.google.gson.JsonParser
import com.nelson.yomukai.data.source.manga.ManhwaWebSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ManhwaWeb against answers its API really gave (test/resources/manhwaweb_*.json). */
class ManhwaWebSourceTest {
    private fun json(name: String) = JsonParser.parseString(javaClass.getResource("/$name")!!.readText()).asJsonObject

    @Test
    fun theLibraryGivesTitlesWithTypeGenresAndTheEroticOnesAsAdult() {
        val items = ManhwaWebSource.parseLibrary(json("manhwaweb_library.json"), "manhwaweb")
        assertEquals(30, items.size)
        val eldlive = items.first { it.id == "ldlive_1781589982404" }
        assertEquals("ēlDLIVE", eldlive.title)
        assertEquals(MediaType.MANGA, eldlive.type)
        assertTrue(eldlive.coverUrl.startsWith("https://img2mw.xyz/manhwas/ldlive_1781589982404/"))
        assertTrue("Romance" in eldlive.genres && "Ciencia ficción" in eldlive.genres)
        assertTrue("Adulto" !in eldlive.genres)
        assertEquals(18, items.count { "Adulto" in it.genres })
    }

    @Test
    fun theNewestChaptersInSpanish() {
        val latest = ManhwaWebSource.parseLatest(json("manhwaweb_latest.json"), "manhwaweb")
        assertTrue(latest.size in 10..35)
        assertTrue(latest.all { it.id.isNotBlank() && it.title.isNotBlank() && it.coverUrl.startsWith("https://") })
    }

    @Test
    fun aTitlesPageChaptersAndImages() {
        val see = json("manhwaweb_see.json")
        val base = MediaItem("ldlive_1781589982404", "ēlDLIVE", "", MediaType.MANGA, "", "", "manhwaweb")
        val d = ManhwaWebSource.parseDetails(see, base)
        assertEquals("Akira Amano", d.author)
        assertTrue(d.description.startsWith("Chuuta Kokonose"))
        assertTrue("elDLIVE" in d.altTitles)
        val chapters = ManhwaWebSource.parseChapters(see, base.id)
        assertEquals(50, chapters.size)
        val first = chapters.first { it.chapterNumber == 1f }
        assertEquals("ldlive_1781589982404-1_01", first.url)
        assertTrue(first.dateUploaded.startsWith("2026-"))
        val pages = ManhwaWebSource.parsePages(json("manhwaweb_chapter.json"))
        assertEquals(68, pages.size)
        assertEquals("https://img2mw.xyz/manhwas/ldlive_1781589982404/chapter_1/ver_01/001.webp", pages.first())
    }
}
