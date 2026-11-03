package com.nelson.yomukai.data.search

import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSearchTest {
    private fun item(title: String, source: String = "a", id: String = title, alt: List<String> = emptyList()) =
        MediaItem(id, title, "", MediaType.MANGA, "", "", source, altTitles = alt)

    @Test
    fun normalizeIgnoresCaseAccentsAndPunctuation() {
        assertEquals("nandu rapido", SmartSearch.normalize("Ñandú: ¡Rápido!"))
        assertEquals("re zero kara", SmartSearch.normalize("Re:Zero -kara-"))
    }

    @Test
    fun levenshteinAndTrigrams() {
        assertEquals(1, SmartSearch.levenshtein("naruto", "narutu"))
        assertEquals(3, SmartSearch.levenshtein("kitten", "sitting"))
        assertTrue(SmartSearch.trigramSimilarity("batman", "batmen") > 0.5)
        assertTrue(SmartSearch.trigramSimilarity("batman", "zzzzzz") < 0.1)
    }

    @Test
    fun toleratesTyposAndPartialWords() {
        assertTrue(SmartSearch.scoreTitle("narutu", "Naruto") > 0.4)
        assertTrue(SmartSearch.scoreTitle("one pice", "One Piece") > 0.4)
        assertTrue(SmartSearch.scoreTitle("overg", "Overgeared") > 0.6)
        assertEquals(0.0, SmartSearch.scoreTitle("xyz", "Naruto"), 0.0)
    }

    @Test
    fun accentsAndPunctuationMatch() {
        assertEquals(1.0, SmartSearch.scoreTitle("re zero", "Re:Zero"), 0.0)
        assertTrue(SmartSearch.scoreTitle("reencarnacion", "La reencarnación del mejor player") > 0.5)
    }

    @Test
    fun rankingPutsExactBeforePartial() {
        val ranked = SmartSearch.rank("batman", listOf(item("Batman Beyond Returns Again"), item("Batman"), item("Superman")))
        assertEquals(listOf("Batman", "Batman Beyond Returns Again"), ranked.map { it.first.title })
    }

    @Test
    fun alternativeTitlesAreSearched() {
        val found = SmartSearch.rank("el impostor", listOf(item("Batman: The Imposter TPB (2021)", alt = listOf("Batman: El Impostor"))))
        assertEquals(1, found.size)
    }

    @Test
    fun duplicatesAcrossSourcesAreGrouped() {
        val groups = SmartSearch.rankAndGroup(
            "regicide",
            listOf(item("Regicide (2026)", "marmota", "m1"), item("Regicide", "mangadex", "d1"), item("Regicide Reborn", "x", "x1"))
        )
        assertEquals(2, groups.size)
        assertEquals(2, groups.first().all.size)
    }

    @Test
    fun queryVariantsAddRelaxedForms() {
        val v = SmartSearch.queryVariants("Narutú Shippuden")
        assertTrue("narutu shippuden" in v && "shippuden" in v && "shippud" in v)
    }
}
