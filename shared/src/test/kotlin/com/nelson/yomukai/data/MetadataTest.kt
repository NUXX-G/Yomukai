package com.nelson.yomukai.data

import com.nelson.yomukai.data.discovery.AniListClient
import com.nelson.yomukai.data.discovery.GenreRules
import com.nelson.yomukai.data.discovery.TitleMatcher
import com.nelson.yomukai.data.metadata.Abandonment
import com.nelson.yomukai.data.metadata.ChapterDates
import com.nelson.yomukai.data.metadata.ChapterIntegrity
import com.nelson.yomukai.data.metadata.ChapterIssue
import com.nelson.yomukai.data.metadata.GenreTaxonomy
import com.nelson.yomukai.data.metadata.KeywordTagger
import com.nelson.yomukai.data.metadata.KitsuClient
import com.nelson.yomukai.data.metadata.MetadataRepository
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.PublicationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MetadataTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    private fun utc(y: Int, m: Int, d: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d) }.timeInMillis

    // Taxonomy

    @Test
    fun genresGetOneNameInEveryLanguage() {
        assertEquals("Acción", GenreTaxonomy.canonical("Action"))
        assertEquals("Acción", GenreTaxonomy.canonical("accion"))
        assertEquals("BL", GenreTaxonomy.canonical("Boys' Love"))
        assertEquals("Recuentos de la vida", GenreTaxonomy.canonical("Slice of Life"))
        assertEquals("Wholesome", GenreTaxonomy.canonical("wholesome"))
    }

    @Test
    fun mergeDropsRepeatsAcrossLanguages() {
        assertEquals(listOf("Acción", "Fantasía", "Isekai"), GenreTaxonomy.merge(listOf("Acción", "Fantasy"), listOf("Action", "fantasia", "Isekai")))
    }

    @Test
    fun exclusionsUseTheTaxonomy() {
        assertTrue(GenreRules.isExcluded(listOf("Shounen-ai"), setOf("BL")))
        assertTrue(GenreRules.isExcluded(listOf("Reverse Harem"), setOf("Harem")))
        assertFalse(GenreRules.isExcluded(listOf("Drama"), emptySet()))
    }

    // Keyword tagging

    @Test
    fun synopsisHintsAtGenres() {
        val tags = KeywordTagger.infer("Tras morir atropellado, reencarna en otro mundo lleno de magia y dragones donde sube de nivel.")
        assertTrue("Isekai" in tags)
        assertTrue("Fantasía" in tags)
        assertTrue(KeywordTagger.infer("").isEmpty())
    }

    @Test
    fun aPassingWordTagsNothing() {
        assertFalse("Juegos" in KeywordTagger.infer("Una historia sobre el nivel del mar."))
    }

    // Chapter integrity

    @Test
    fun aHugeJumpIsAGap() {
        val numbers = (1..21).map { it.toFloat() } + (120..125).map { it.toFloat() }
        val gaps = ChapterIntegrity.check(numbers).filterIsInstance<ChapterIssue.Gap>()
        assertEquals(1, gaps.size)
        assertEquals(21f, gaps[0].from)
        assertEquals(120f, gaps[0].to)
    }

    @Test
    fun regularListsAndDecimalsStayQuiet() {
        assertTrue(ChapterIntegrity.check((1..200).map { it.toFloat() }).isEmpty())
        assertTrue(ChapterIntegrity.check(listOf(1f, 2f, 2.5f, 3f, 4f, 5f)).isEmpty())
        assertTrue("numbering by tens is its own step", ChapterIntegrity.check(listOf(10f, 20f, 30f, 40f, 50f)).isEmpty())
        assertTrue("one skipped filler chapter is not worth a warning", ChapterIntegrity.check(listOf(1f, 2f, 3f, 5f, 6f)).isEmpty())
    }

    @Test
    fun duplicatesAndReversedListsAreReported() {
        val issues = ChapterIntegrity.check(listOf(1f, 2f, 3f, 3f, 4f, 5f))
        assertTrue(issues.any { it is ChapterIssue.Duplicate && it.number == 3f && it.count == 2 })
        val reversed = ChapterIntegrity.check((30 downTo 1).map { it.toFloat() })
        assertTrue(reversed.any { it is ChapterIssue.OutOfOrder })
    }

    @Test
    fun unknownNumbersAreIgnored() {
        assertTrue(ChapterIntegrity.check(listOf(0f, 0f, 1f, 2f, 3f, -1f)).isEmpty())
    }

    // Dates and abandonment

    @Test
    fun readsTheDateShapesSourcesUse() {
        val now = utc(2026, 11, 3)
        assertEquals(utc(2024, 3, 5), ChapterDates.parse("05/03/2024", now))
        assertEquals(utc(2024, 3, 5), ChapterDates.parse("2024-03-05T10:00:00Z", now))
        assertEquals(utc(2024, 3, 5), ChapterDates.parse("marzo 5, 2024", now))
        assertEquals(utc(2023, 12, 1), ChapterDates.parse("1 Dec 2023", now))
        assertEquals(now - 3 * 86_400_000L, ChapterDates.parse("hace 3 días", now))
        assertNotNull(ChapterDates.parse("2 years ago", now))
        assertNull(ChapterDates.parse("Capítulo 12", now))
        assertNull(ChapterDates.parse("", now))
    }

    @Test
    fun ongoingWithoutChaptersForTwoYearsLooksAbandoned() {
        val now = utc(2026, 11, 3)
        assertTrue(Abandonment.isLikelyAbandoned(true, listOf("01/01/2023", "15/06/2024"), now))
        assertFalse("recent chapter", Abandonment.isLikelyAbandoned(true, listOf("01/01/2023", "01/10/2026"), now))
        assertFalse("finished works are not abandoned", Abandonment.isLikelyAbandoned(false, listOf("01/01/2020"), now))
        assertFalse("no dates, no claim", Abandonment.isLikelyAbandoned(true, listOf("", ""), now))
    }

    // AniList and Kitsu

    @Test
    fun aniListBringsScoreStatusSynopsisAndRelations() {
        val entry = AniListClient.parseEntries(fixture("anilist_lookup.json")).first()
        assertNotNull(entry.score)
        assertTrue(entry.status.isNotBlank())
        assertTrue(entry.description.isNotBlank())
        assertFalse("credits are cut", entry.description.contains("(Source:"))
        assertFalse("no html", entry.description.contains("<br>"))
        assertTrue(entry.relations.isNotEmpty())
        assertTrue(entry.relations.all { it.relation != null && it.names.isNotEmpty() })
        assertTrue("an adaptation in another format", entry.relations.any { it.type == MediaType.ANIME })
    }

    @Test
    fun descriptionCleanup() {
        assertEquals(
            "Line one\nLine two",
            AniListClient.cleanDescription("Line one<br>Line two<br><br>\n(Source: Tappytoon)<br><br>\n<i>Note: extra chapters.</i>")
        )
    }

    @Test
    fun kitsuScoreNeedsTheSameName() {
        val json = fixture("kitsu_manga.json")
        assertEquals(8.449f, KitsuClient.parseScore(json, listOf("Solo Leveling"), MediaType.MANHWA)!!, 0.01f)
        assertNull(KitsuClient.parseScore(json, listOf("Berserk")))
    }

    @Test
    fun kitsuGivesTheBannerAndPosterOfTheSameWorkOnly() {
        val json = fixture("kitsu_gate_images.json")
        val images = KitsuClient.parseImages(json, listOf("Steins;Gate 0"), MediaType.ANIME)!!
        assertEquals("https://media.kitsu.app/anime/cover_images/10788/original.jpeg", images.banner)
        assertTrue(images.poster.startsWith("https://media.kitsu.app/anime/poster_images/10788/"))
        // «Gate» alone is another work: no images.
        assertNull(KitsuClient.parseImages(json, listOf("Gate"), MediaType.ANIME))
    }

    @Test
    fun theNovelNeverTakesTheManhwaScore() {
        val json = fixture("kitsu_manga.json")
        // Kitsu lists the novel as "Only I Level Up"; as "Solo Leveling" only the manhwa exists there.
        assertEquals(8.236f, KitsuClient.parseScore(json, listOf("Solo Leveling", "Only I Level Up"), MediaType.NOVEL)!!, 0.01f)
        assertNull(KitsuClient.parseScore(json, listOf("Solo Leveling Ragnarok Hunter"), MediaType.NOVEL))
    }

    @Test
    fun manhwaAndNovelOfTheSameNameAreTwoWorks() {
        assertTrue(TitleMatcher.familyKey(MediaType.MANHWA, "Solo Leveling") != TitleMatcher.familyKey(MediaType.NOVEL, "Solo Leveling"))
        assertEquals(TitleMatcher.familyKey(MediaType.MANHWA, "Solo Leveling"), TitleMatcher.familyKey(MediaType.MANGA, "SOLO LEVELING"))
        val novel = MediaItem("1", "Solo Leveling", "", MediaType.NOVEL, "", "", "s")
        assertEquals(com.nelson.yomukai.data.discovery.MatchConfidence.NONE, TitleMatcher.compare(listOf("Solo Leveling"), "Chugong", MediaType.MANHWA, novel))
    }

    @Test
    fun layersJoin() {
        val media = MediaItem("1", "X", "", MediaType.MANHWA, "En emisión", "Reencarna en otro mundo con un sistema de niveles y mazmorras.", "s")
        val alone = MetadataRepository.build(media, null, null)
        assertTrue(alone.inferredGenres)
        assertTrue("Isekai" in alone.genres)
        assertNull(alone.score)
        val scored = MetadataRepository.build(media, null, 7.5f)
        assertEquals("Kitsu", scored.scoreSource)
        assertEquals(PublicationStatus.ONGOING, MetadataRepository.statusOf("RELEASING"))
    }

    @Test
    fun closenessRejectsSequels() {
        val item = MediaItem("1", "Solo Leveling: Ragnarok", "", MediaType.MANHWA, "", "", "s")
        assertTrue(TitleMatcher.closeness("Solo Leveling", item) < 0.75)
        val respelled = MediaItem("1", "Solo Levelling", "", MediaType.MANHWA, "", "", "s")
        assertTrue(TitleMatcher.closeness("Solo Leveling", respelled) >= 0.75)
    }
}
