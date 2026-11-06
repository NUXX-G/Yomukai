package com.nelson.yomukai.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryTest {
    @Test
    fun jellyfinPathsFollowItsNamingRules() {
        assertEquals("Sousou no Frieren (2023)/Season 01/Sousou no Frieren - S01E05.mkv", JellyfinNaming.episodePath("Sousou no Frieren", 2023, 1, 5.0))
        assertEquals("Re Zero (2016)/Season 02/Re Zero - S02E12-5.mkv", JellyfinNaming.episodePath("Re:Zero", 2016, 2, 12.5))
        assertEquals("Your Name (2016)/Your Name (2016).mp4", JellyfinNaming.moviePath("Your Name", 2016, ".MP4"))
        assertEquals("_CON", JellyfinNaming.safe("CON"))
        assertEquals("Sin título", JellyfinNaming.safe(" ... "))
        assertFalse(JellyfinNaming.safe("a/b\\c:d*e?f\"g<h>i|j").any { it in "/\\:*?\"<>|" })
        // No climbing out of the library folder.
        assertFalse(JellyfinNaming.episodePath("../../etc", null, 1, 1.0).contains(".."))
    }

    @Test
    fun nfoFilesCarryTheIdsAndAreEscaped() {
        val show = Nfo.tvshow("Tom & Jerry <3", 2020, "Línea 1", listOf("Comedy"), ExternalIds(anilist = 1, mal = 2, tvdb = 3))
        assertTrue(show.contains("<title>Tom &amp; Jerry &lt;3</title>"))
        assertTrue(show.contains("<uniqueid type=\"anilist\">1</uniqueid>"))
        assertTrue(show.contains("<uniqueid type=\"tvdb\" default=\"true\">3</uniqueid>"))
        val ep = Nfo.episode("Frieren", "", 1, 5.0, ExternalIds(anilist = 154587))
        assertTrue(ep.contains("<title>Episodio 5</title>"))
        assertTrue(ep.contains("<episode>5</episode>"))
        assertTrue(Nfo.season(2).contains("<seasonnumber>2</seasonnumber>"))
    }

    @Test
    fun comicInfoForKavitaAndKomga() {
        val info = ComicInfo.Info(
            "Berserk", "12", writers = listOf("Kentaro Miura"), genres = listOf("Acción", "Fantasía"), manga = true,
            ids = ExternalIds(anilist = 30002), pageCount = 20
        )
        val xml = ComicInfo.xml(info)
        assertTrue(xml.contains("<Series>Berserk</Series>"))
        assertTrue(xml.contains("<Manga>YesAndRightToLeft</Manga>"))
        assertTrue(xml.contains("<Web>https://anilist.co/manga/30002</Web>"))
        assertFalse(xml.contains("<Volume>"))
        assertEquals("Berserk/Berserk - Vol. 03 Ch. 012.cbz", ComicInfo.cbzPath("Berserk", 12.0, 3))
        assertEquals("One Piece/One Piece - Ch. 1044.5.cbz", ComicInfo.cbzPath("One Piece", 1044.5, null))
    }
}
