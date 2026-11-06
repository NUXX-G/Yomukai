package com.nelson.yomukai.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ActivityStatsTest {
    private val today = LocalDate.of(2026, 11, 6)

    @Test
    fun streaksCountConsecutiveDays() {
        val days = listOf(0L, 1, 2, 5, 6, 7, 8, 9).map { today.minusDays(9 - it) }
        assertEquals(ActivityStats.Streak(current = 5, longest = 5), ActivityStats.streak(days, today))
        // Yesterday still counts as alive; two days ago it is broken.
        assertEquals(5, ActivityStats.streak(days.map { it.minusDays(1) }, today).current)
        assertEquals(0, ActivityStats.streak(days.map { it.minusDays(2) }, today).current)
        assertEquals(ActivityStats.Streak(0, 0), ActivityStats.streak(emptyList(), today))
        assertEquals(ActivityStats.Streak(1, 1), ActivityStats.streak(listOf(today, today), today))
    }

    @Test
    fun hoursAreEstimated() {
        assertEquals(5, ActivityStats.hours(episodes = 12, chapters = 2))
    }

    @Test
    fun burstsGroupWhatWasDoneTogether() {
        val h = 3_600_000L
        val items = (1..12).map { ActivityStats.Done("al:1", "Frieren", "Ep. $it", it * 20 * 60_000L) } +
            ActivityStats.Done("al:1", "Frieren", "Ep. 13", 2 * 24 * h) +
            ActivityStats.Done("al:2", "Berserk", "Cap. 3", 30 * 60_000L)
        val bursts = ActivityStats.bursts(items)
        assertEquals(3, bursts.size)
        assertEquals("Ep. 13", bursts.first().lastLabel)
        val marathon = bursts.single { it.count == 12 }
        assertTrue(marathon.marathon)
        assertEquals("Ep. 1", marathon.firstLabel)
        assertFalse(bursts.single { it.work == "al:2" }.marathon)
    }

    @Test
    fun topGenreIsTheMostCommon() {
        assertEquals("Acción", ActivityStats.topGenre(listOf(listOf("Acción", "Drama"), listOf("Acción"), listOf(" "))))
        assertEquals(null, ActivityStats.topGenre(emptyList()))
    }
}
