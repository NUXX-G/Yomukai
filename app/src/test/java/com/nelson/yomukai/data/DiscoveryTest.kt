package com.nelson.yomukai.data

import com.nelson.yomukai.data.discovery.AniListClient
import com.nelson.yomukai.data.discovery.MatchConfidence
import com.nelson.yomukai.data.discovery.TitleMatcher
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    private fun item(title: String, type: MediaType, author: String = "", alt: List<String> = emptyList()) =
        MediaItem("id", title, "", type, "", "", "s", author, altTitles = alt)

    @Test
    fun sameNameIgnoresCaseAccentsAndYearSuffix() {
        val names = listOf("Leviathan")
        assertEquals(MatchConfidence.SAME_WORK, TitleMatcher.compare(names, "", MediaType.MANHWA, item("LEVIATHÁN (2020)", MediaType.MANHWA)))
    }

    @Test
    fun alternativeNamesCount() {
        val names = listOf("Yo solo subo de nivel", "Solo Leveling")
        assertEquals(
            MatchConfidence.SAME_WORK,
            TitleMatcher.compare(names, "", MediaType.MANHWA, item("Na Honjaman Level Up", MediaType.MANHWA, alt = listOf("Solo Leveling")))
        )
    }

    @Test
    fun aSequelIsNotTheSameWork() {
        assertEquals(MatchConfidence.NONE, TitleMatcher.compare(listOf("Solo Leveling"), "", MediaType.MANHWA, item("Solo Leveling: Ragnarok", MediaType.MANHWA)))
        assertEquals(MatchConfidence.NONE, TitleMatcher.compare(listOf("Dr. Stone"), "", MediaType.ANIME, item("Dr. Stone 2", MediaType.ANIME)))
    }

    @Test
    fun otherMediumIsNotTheSameWork() {
        assertEquals(MatchConfidence.NONE, TitleMatcher.compare(listOf("Berserk"), "", MediaType.MANGA, item("Berserk", MediaType.ANIME)))
        assertEquals(MatchConfidence.NONE, TitleMatcher.compare(listOf("Overlord"), "", MediaType.MANGA, item("Overlord", MediaType.NOVEL)))
        assertEquals(MatchConfidence.SAME_WORK, TitleMatcher.compare(listOf("Tower"), "", MediaType.MANGA, item("Tower", MediaType.MANHWA)))
    }

    @Test
    fun differentAuthorsLowerConfidenceWithoutRejecting() {
        assertEquals(
            MatchConfidence.PROBABLE,
            TitleMatcher.compare(listOf("Leviathan"), "Lee Gyuntak", MediaType.MANHWA, item("Leviathan", MediaType.MANHWA, author = "Noah Smith"))
        )
        assertEquals(
            MatchConfidence.SAME_WORK,
            TitleMatcher.compare(listOf("Leviathan"), "Lee Gyuntak", MediaType.MANHWA, item("Leviathan", MediaType.MANHWA, author = "Gyuntak Lee, Noah"))
        )
        assertFalse("unknown authors say nothing", TitleMatcher.authorsDisagree("", "Noah"))
    }

    @Test
    fun readsNamesAndRecommendationsFromAniList() {
        val entry = AniListClient.parseEntries(fixture("anilist_media.json")).first()
        assertTrue("Neon Genesis Evangelion" in entry.names)
        assertTrue(entry.related.size >= 10)
        assertTrue(entry.related.all { it.type == MediaType.ANIME && it.coverUrl.startsWith("https://") && it.names.isNotEmpty() })
        assertTrue(entry.tags.isNotEmpty())
    }
}
