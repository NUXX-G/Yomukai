package com.nelson.yomukai.data.discovery

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The week the app shows, from the real AniList schedule (bloque 5). */
class WeekCalendarTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val airing = AniListDiscover.parseAiring(
        JsonParser.parseString(javaClass.classLoader!!.getResource("anilist_airing.json")!!.readText()).asJsonObject.getAsJsonObject("data")
    ).mapNotNull { WeekCalendar.fromAiring(it, madrid) }

    @Test
    fun episodesFallOnTheirLocalDay() {
        val first = airing.first()
        // 1794411900 = 2026-11-11 15:45 UTC = 16:45 in Madrid.
        assertEquals(LocalDate.of(2026, 11, 11), first.date)
        assertEquals(6, first.episode)
        assertEquals(LocalDate.of(2026, 11, 12), WeekCalendar.dayOf(1_794_438_000, madrid)) // 23:00 UTC = 00:00 in Madrid
        assertEquals(LocalDate.of(2026, 11, 11), WeekCalendar.dayOf(1_794_438_000, ZoneId.of("UTC")))
    }

    @Test
    fun theLibraryIsRecognisedByAnExactNameOnly() {
        val marked = WeekCalendar.markLibrary(airing, listOf("Kikansha no Mahou wa Tokubetsu desu 2nd Season", "Girls und Panzer"))
        assertTrue(marked.first { it.anilistId == 172192 }.mine)
        // "Girls und Panzer" is not "Girls und Panzer: Motto Love Love Sakusen desu!".
        assertFalse(marked.first { it.anilistId == 188034 }.mine)
        assertEquals(airing, WeekCalendar.markLibrary(airing, emptyList()))
    }

    @Test
    fun yoursFirstAndNeverTwice() {
        val day = airing.first().date
        val mine = listOf(CalendarSlot("m:x", "A Returner's Magic Should be Special Season 2", date = day, airingAt = 1_794_420_000, mine = true, anilistId = 172192))
        val week = WeekCalendar.week(mine, airing, day)
        assertEquals(7, week.size)
        val today = week.getValue(day)
        assertEquals("m:x", today.first().key)
        assertEquals(1, today.count { it.anilistId == 172192 })
        assertTrue(today.drop(1).zipWithNext().all { (a, b) -> (a.airingAt ?: 0) <= (b.airingAt ?: 0) })
        assertEquals(listOf("m:x"), WeekCalendar.week(mine, airing, day, mineOnly = true).values.flatten().map { it.key })
    }
}
