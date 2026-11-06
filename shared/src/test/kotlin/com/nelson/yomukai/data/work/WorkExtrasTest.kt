package com.nelson.yomukai.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class WorkExtrasTest {
    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource(name)).readText()

    @Test
    fun whereToContinueAfterTheAnime() {
        val points = listOf(
            AdaptationPoint("al:anime", 12, "al:manga", 52f, 6),
            AdaptationPoint("al:anime", 24, "al:manga", 110f, 13),
            AdaptationPoint("al:anime", 24, "al:novel", null, 4)
        )
        val after24 = WhereNext.afterAnime(points, "al:anime", 24)
        assertEquals(Continuation("al:manga", 111f, 13), after24.first { it.targetKey == "al:manga" })
        assertEquals("only the volume known: the next one", Continuation("al:novel", null, 5), after24.first { it.targetKey == "al:novel" })
        assertEquals(53f, WhereNext.afterAnime(points, "al:anime", 15).single().chapter)
        assertTrue(WhereNext.afterAnime(points, "al:anime", 5).isEmpty())
        assertEquals(110f, WhereNext.animatedUpTo(points, "al:manga"))
        assertNull(WhereNext.animatedUpTo(points, "al:otra"))
    }

    @Test
    fun spoilerShield() {
        assertFalse(SpoilerShield.hideChapter(true, 11f, 10f))
        assertTrue(SpoilerShield.hideChapter(true, 12f, 10f))
        assertFalse(SpoilerShield.hideChapter(false, 99f, 1f))
        assertTrue("nothing read yet", SpoilerShield.hideChapter(true, 5f, null))
        assertTrue(SpoilerShield.hideSequelSynopsis(true, "Secuela", finishedCurrent = false))
        assertFalse(SpoilerShield.hideSequelSynopsis(true, "Secuela", finishedCurrent = true))
        assertFalse(SpoilerShield.hideSequelSynopsis(true, "Precuela", finishedCurrent = false))
        assertTrue(SpoilerShield.hideComment(true, 120f, 100f))
        assertFalse(SpoilerShield.hideComment(true, 120f, 130f))
    }

    @Test
    fun theSharedMusicIsFoundInBothEpisodes() {
        val rnd = Random(7)
        val opening = IntArray(700) { rnd.nextInt() } // ~87 s
        fun episode(before: Int, after: Int) = IntArray(before) { rnd.nextInt() } + opening.map { it xor (1 shl rnd.nextInt(32)) } + IntArray(after) { rnd.nextInt() }
        val a = episode(300, 1000)
        val b = episode(80, 1200)
        val (startA, startB, length) = AudioFingerprint.longestMatch(a, b)!!
        assertEquals(300, startA)
        assertEquals(80, startB)
        assertEquals(700, length)
        val markers = AudioFingerprint.markers(a, b, IntArray(0), IntArray(0), durationA = 1440.0, window = 360.0)
        assertEquals(300 * AudioFingerprint.ITEM_SECONDS, markers.opening!!.startSec, 0.01)
        assertEquals((300 + 700) * AudioFingerprint.ITEM_SECONDS, markers.opening!!.endSec, 0.01)
        assertNull(markers.ending)
        assertEquals(3, AudioFingerprint.parseRaw("DURATION=10\nFINGERPRINT=1,-2,3\n").size)
    }

    @Test
    fun releaseRhythm() {
        val today = LocalDate.of(2026, 11, 6)
        assertEquals(LocalDate.of(2026, 10, 31), ReleaseCadence.parseDate("2026-10-31T10:00:00Z", today))
        assertEquals(LocalDate.of(2026, 10, 31), ReleaseCadence.parseDate("31/10/2026", today))
        assertEquals(LocalDate.of(2026, 10, 31), ReleaseCadence.parseDate("Oct 31, 2026", today))
        assertEquals(LocalDate.of(2026, 11, 3), ReleaseCadence.parseDate("hace 3 días", today))
        assertEquals(LocalDate.of(2026, 10, 23), ReleaseCadence.parseDate("2 weeks ago", today))
        assertNull(ReleaseCadence.parseDate("ayer por la tarde", today))
        val weekly = (0..8).map { today.minusWeeks(it.toLong()) }
        val c = ReleaseCadence.estimate(weekly)!!
        assertEquals(7.0, c.everyDays, 0.01)
        assertTrue(c.regular)
        assertEquals(today.plusDays(7), c.nextDay)
        assertNull(ReleaseCadence.estimate(listOf(today)))
        val messy = listOf(0L, 2L, 30L, 31L, 90L).map { today.minusDays(it) }
        assertFalse(ReleaseCadence.estimate(messy)!!.regular)
    }

    @Test
    fun fillerGuideFromTheRealPage() {
        val kinds = FillerGuide.parse(fixture("fillerlist_bleach.html"))
        assertEquals(366, kinds.size)
        assertEquals(163, kinds.values.count { it == EpisodeKind.FILLER })
        assertEquals(EpisodeKind.CANON, kinds[1])
        assertNotNull(kinds[33])
        assertEquals("one-piece", FillerGuide.slug("One Piece"))
        assertEquals("my-hero-academia", FillerGuide.slug("My Hero Academia!"))
    }
}
