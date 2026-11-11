package com.nelson.yomukai.data.discovery

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One entry of the weekly calendar (bloque 5), the same for the app with or without the server: an anime episode with
 * its time ([airingAt], epoch seconds) or a chapter expected on [date] from the source's rhythm.
 */
data class CalendarSlot(
    val key: String,
    val title: String,
    val names: List<String> = emptyList(),
    val cover: String = "",
    val date: LocalDate,
    val airingAt: Long? = null,
    val episode: Int? = null,
    /** "Nuevo capítulo (cada 7 días)" for manga and novels; empty for anime (the episode says it). */
    val label: String = "",
    val estimated: Boolean = false,
    val mine: Boolean = false,
    val anilistId: Int? = null,
    val synopsis: String = "",
    val genres: List<String> = emptyList(),
    val score: Int? = null,
    val sourceId: String? = null,
    val mediaId: String? = null,
    val entry: DiscoverEntry? = null
)

/** Pure helpers behind the calendar screens. */
object WeekCalendar {
    fun dayOf(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()

    /** An AniList episode as a calendar entry ([mine] when the user follows it). */
    fun fromAiring(e: DiscoverEntry, zone: ZoneId = ZoneId.systemDefault(), mine: Boolean = false): CalendarSlot? {
        val at = e.nextAiringAt ?: return null
        return CalendarSlot(
            key = "a:${e.anilistId}:${e.nextEpisode}", title = e.title, names = e.names, cover = e.cover, date = dayOf(at, zone), airingAt = at,
            episode = e.nextEpisode, mine = mine, anilistId = e.anilistId, synopsis = e.synopsis, genres = e.genres, score = e.score, entry = e
        )
    }

    /** Letters and digits only, lower case: "Sousou no Frieren: 2nd Season" and "sousou no frieren 2nd season" match. */
    fun normalize(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Without the server the phone knows its library only by title: an airing anime is "mine" when one of its names
     * is exactly the title of an anime in the library (normalized). Exact on purpose: a near match would mark sequels.
     */
    fun markLibrary(slots: List<CalendarSlot>, libraryTitles: Collection<String>): List<CalendarSlot> {
        val own = libraryTitles.map(::normalize).filter { it.length >= 2 }.toSet()
        if (own.isEmpty()) return slots
        return slots.map { s -> if (!s.mine && (listOf(s.title) + s.names).any { normalize(it) in own }) s.copy(mine = true) else s }
    }

    /**
     * Your entries and the rest, without the same episode twice (the library's entry wins: it opens your source), for
     * [days] days from [from]; each day yours first, then by time. [mineOnly] leaves the rest out.
     */
    fun week(mine: List<CalendarSlot>, airing: List<CalendarSlot>, from: LocalDate, days: Int = 7, mineOnly: Boolean = false): Map<LocalDate, List<CalendarSlot>> {
        val ownIds = mine.mapNotNull { it.anilistId }.toSet()
        val ownTitles = mine.map { normalize(it.title) }.toSet()
        val rest = if (mineOnly) airing.filter { it.mine } else airing
        val all = mine + rest.filter { it.anilistId !in ownIds && normalize(it.title) !in ownTitles }
        return (0 until days).associate { i ->
            val day = from.plusDays(i.toLong())
            day to all.filter { it.date == day }.sortedWith(compareByDescending<CalendarSlot> { it.mine }.thenBy { it.airingAt ?: Long.MAX_VALUE })
        }
    }
}
