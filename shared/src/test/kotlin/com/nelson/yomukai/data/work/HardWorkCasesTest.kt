package com.nelson.yomukai.data.work

import com.nelson.yomukai.data.discovery.MatchConfidence
import com.nelson.yomukai.data.discovery.TitleMatcher
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * F1.6: the unified work must never mix two works nor split one. Real titles, written the way the sources write them.
 * Each case says what it protects against.
 */
class HardWorkCasesTest {
    private fun item(source: String, title: String, type: MediaType = MediaType.ANIME, author: String = "", alt: List<String> = emptyList()) =
        MediaItem("https://$source.com/${title.hashCode()}", title, "https://x/$source.jpg", type, "", "", source, author = author, altTitles = alt)

    private fun same(a: MediaItem, b: MediaItem) = WorkGrouper.group(listOf(a, b)).size == 1

    private fun assertSame(why: String, a: MediaItem, b: MediaItem) = assertEquals(why, true, same(a, b))
    private fun assertApart(why: String, a: MediaItem, b: MediaItem) = assertEquals(why, false, same(a, b))

    @Test
    fun seasonsAndPartsAreDifferentWorksHoweverTheyAreWritten() {
        assertApart("season 2 is not season 1", item("animeflv", "Ao Ashi"), item("jkanime", "Ao Ashi 2nd Season"))
        assertSame("«2nd Season», «Season 2» and «Temporada 2» are the same season", item("animeflv", "Ao Ashi Season 2"), item("jkanime", "Ao Ashi 2nd Season"))
        assertSame(item("animeflv", "Kusuriya no Hitorigoto Temporada 2").title, item("animeflv", "Kusuriya no Hitorigoto Temporada 2"), item("jkanime", "Kusuriya no Hitorigoto 2nd Season"))
        assertApart("part 2 is its own entry", item("animeflv", "Shingeki no Kyojin: The Final Season"), item("jkanime", "Shingeki no Kyojin: The Final Season Part 2"))
        assertSame("«Part 2» and «Parte 2»", item("animeflv", "Dr. Stone Science Future Parte 2"), item("jkanime", "Dr. Stone: Science Future Part 2"))
        assertApart("GATE season 2 has another name and is not season 1", item("anitaku", "Gate: Jieitai Kanochi nite, Kaku Tatakaeri"), item("anitaku", "Gate: Jieitai Kanochi nite, Kaku Tatakaeri 2nd Season"))
    }

    @Test
    fun remakesAreNotTheOriginal() {
        assertApart("the 2024 Ranma is a remake", item("anitaku", "Ranma 1/2"), item("anitaku", "Ranma1/2 (2024)"))
        assertApart("Hunter x Hunter 2011", item("animeflv", "Hunter x Hunter"), item("animeflv", "Hunter x Hunter (2011)"))
        assertApart("Rayearth 2026", item("anitaku", "Magic Knight Rayearth"), item("anitaku", "Magic Knight Rayearth (2026)"))
        assertSame("the same year in two sources is the same remake", item("anitaku", "Ranma1/2 (2024)"), item("jkanime", "Ranma 1/2 (2024)"))
        assertSame("a source note in brackets is not a year", item("animeflv", "One Piece (Sub)"), item("jkanime", "One Piece"))
    }

    @Test
    fun spinOffsAndSequelsWithLongerNamesStayApart() {
        assertApart(item("animeflv", "Naruto").title, item("animeflv", "Naruto"), item("jkanime", "Naruto Shippuden"))
        assertApart(item("animeflv", "Boruto").title, item("animeflv", "Boruto: Naruto Next Generations"), item("jkanime", "Naruto"))
        assertApart("a movie is not the series", item("animeflv", "Kimetsu no Yaiba"), item("jkanime", "Kimetsu no Yaiba Movie: Mugen Ressha-hen"))
        assertApart("Solo Leveling: Ragnarok is another manhwa", item("olympus", "Solo Leveling", MediaType.MANHWA), item("asura", "Solo Leveling: Ragnarok", MediaType.MANHWA))
        assertApart("Dragon Ball Super and Dragon Ball", item("animeflv", "Dragon Ball"), item("animeflv", "Dragon Ball Super"))
    }

    @Test
    fun oneWorkUnderNamesInSeveralLanguages() {
        val en = item("anitaku", "The Apothecary Diaries", alt = listOf("Kusuriya no Hitorigoto"))
        assertSame("Spanish source uses the romaji name", en, item("animeflv", "Kusuriya no Hitorigoto"))
        assertSame("spacing and case", item("animeflv", "SPY x FAMILY"), item("jkanime", "Spy × Family"))
        assertSame("Ao Ashi / Aoashi", item("animeflv", "Ao Ashi"), item("jkanime", "Aoashi"))
        assertSame("accents and punctuation", item("olympus", "¡Tensei Shitara Slime Datta Ken!", MediaType.MANGA, alt = listOf("That Time I Got Reincarnated as a Slime")), item("mangadex_en", "That Time I Got Reincarnated as a Slime", MediaType.MANGA))
    }

    @Test
    fun novelMangaAndAnimeOfTheSameNameAreThreeWorks() {
        val anime = item("animeflv", "Mushoku Tensei", MediaType.ANIME)
        val manga = item("mangadex", "Mushoku Tensei", MediaType.MANGA)
        val novel = item("skynovels", "Mushoku Tensei", MediaType.NOVEL)
        assertEquals(3, WorkGrouper.group(listOf(anime, manga, novel)).size)
        assertNotEquals(WorkKey.of(MediaType.NOVEL, "Mushoku Tensei"), WorkKey.of(MediaType.MANGA, "Mushoku Tensei"))
        assertNotEquals(WorkKey.of(MediaType.ANIME, "Mushoku Tensei"), WorkKey.of(MediaType.MANGA, "Mushoku Tensei"))
        assertSame("manhwa and its manga edition are one work (same author)", item("olympus", "Solo Leveling", MediaType.MANHWA, author = "Chugong"), item("mangadex", "Solo Leveling", MediaType.MANGA, author = "Chugong, DUBU", alt = listOf("Na Honjaman Level Up")))
    }

    @Test
    fun aShortSharedNameNeedsMoreThanTheName() {
        // Several different «Leviathan» exist; without authors that agree they are not merged.
        val a = item("asura", "Leviathan", MediaType.MANHWA, author = "Lee Gyuntak")
        val b = item("mangadex", "Leviathan", MediaType.MANGA, author = "Shiro Kuroi")
        assertApart("same short name, other author", a, b)
        assertEquals(MatchConfidence.DIFFERENT_AUTHOR, TitleMatcher.compare(listOf(a.title), a.author, a.type, b))
    }

    @Test
    fun keysOfRemakesDifferButKeepStableForTheSameTitle() {
        assertNotEquals(WorkKey.of(MediaType.ANIME, "Ranma 1/2"), WorkKey.of(MediaType.ANIME, "Ranma 1/2 (2024)"))
        assertEquals(WorkKey.of(MediaType.ANIME, "Ranma 1/2 (2024)"), WorkKey.of(MediaType.ANIME, "Ranma 1/2 [2024]"))
        assertEquals("a manhwa with the year a site adds is the same work", WorkKey.of(MediaType.MANHWA, "Regicide"), WorkKey.of(MediaType.MANHWA, "Regicide (2026)"))
        assertEquals("seasons however written", WorkKey.of(MediaType.ANIME, "Ao Ashi 2nd Season"), WorkKey.of(MediaType.ANIME, "Ao Ashi Temporada 2"))
    }
}
