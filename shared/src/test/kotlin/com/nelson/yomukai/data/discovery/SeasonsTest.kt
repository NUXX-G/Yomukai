package com.nelson.yomukai.data.discovery

import com.nelson.yomukai.data.search.SmartSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonsTest {
    private fun key(name: String) = SmartSearch.duplicateKey(Seasons.canonical(name))

    @Test
    fun everyWayOfWritingASeasonIsTheSame() {
        val same = listOf("GATE Season 2", "Gate 2nd Season", "GATE 2nd season", "Gate Temporada 2", "Gate Segunda Temporada", "Gate 2ª temporada", "Gate S2")
        assertEquals(1, same.map(::key).toSet().size)
        assertEquals(key("Ao Ashi Season 2"), key("Aoashi 2nd Season".replace("Aoashi", "Ao Ashi")))
        assertEquals(key("Shingeki no Kyojin: The Final Season Part 2"), key("Shingeki no Kyojin: The Final Season Parte 2"))
    }

    @Test
    fun differentSeasonsAndPartsStayApart() {
        assertNotEquals(key("Gate Season 2"), key("Gate Season 3"))
        assertNotEquals(key("Gate Season 2"), key("Gate Part 2"))
        assertNotEquals(key("Gate"), key("Gate Season 2"))
        // Numbers that are not seasons are left as they are.
        assertEquals("Kaiju No. 8", Seasons.canonical("Kaiju No. 8"))
        assertEquals("86", Seasons.canonical("86"))
        assertEquals("Sister's 2 Lives", Seasons.canonical("Sister's 2 Lives"))
    }

    @Test
    fun aniListMatchingUnderstandsSeasons() {
        assertTrue(TitleMatcher.shareName(listOf("Gate 2nd Season"), listOf("GATE Season 2", "Gate: Jieitai Kanochi nite, Kaku Tatakaeri - Enryuu-hen")))
        assertFalse(TitleMatcher.shareName(listOf("Gate 2nd Season"), listOf("GATE")))
        // Spaces do not matter either: «Ao Ashi» on a site, «Aoashi» on AniList.
        assertTrue(TitleMatcher.shareName(listOf("Ao Ashi Season 2"), listOf("Aoashi 2nd Season")))
        assertFalse(TitleMatcher.shareName(listOf("Ao Ashi Season 2"), listOf("Aoashi")))
    }

    /** O1: «II» at the end (AnimeFLV) is AniList's «2nd Season»; a numeral inside the name is not a season. */
    @Test
    fun romanNumeralClosingTheTitleIsItsSeason() {
        assertTrue(TitleMatcher.shareName(listOf("Tensei shitara Ken deshita II"), listOf("Tensei Shitara Ken Deshita 2nd Season")))
        assertEquals(Seasons.canonical("Overlord IV"), Seasons.canonical("Overlord Season 4"))
        assertEquals("Final Fantasy VII: Advent Children", Seasons.canonical("Final Fantasy VII: Advent Children"))
        assertFalse(TitleMatcher.shareName(listOf("Tensei shitara Ken deshita II"), listOf("Tensei Shitara Ken Deshita")))
    }
}
