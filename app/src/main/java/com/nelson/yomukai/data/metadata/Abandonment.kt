package com.nelson.yomukai.data.metadata

import com.nelson.yomukai.data.search.SmartSearch
import java.util.Calendar
import java.util.TimeZone

/**
 * Reads the dates sources print next to chapters, which come in many shapes: "05/03/2024",
 * "2024-03-05", "marzo 5, 2024", "5 Mar 2024", "hace 3 días", "2 years ago". Pure.
 */
object ChapterDates {
    private val DMY = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})\b""")
    private val YMD = Regex("""\b(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})(?!\d)""")
    private val RELATIVE = Regex("""(?:hace\s+)?(\d+|un|una|an|a)\s+(segundo|minuto|hora|dia|semana|mes|meses|ano|second|minute|hour|day|week|month|year)s?""")
    private val NUMBER = Regex("""\b\d{1,4}\b""")

    private val MONTHS = mapOf(
        "ene" to 1, "jan" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "ago" to 8, "aug" to 8, "sep" to 9, "set" to 9, "oct" to 10, "nov" to 11, "dic" to 12, "dec" to 12
    )

    private const val DAY_MS = 86_400_000L

    /** Epoch milliseconds (UTC midnight for calendar dates), or null when the text is not a date. */
    fun parse(text: String, now: Long = System.currentTimeMillis()): Long? {
        if (text.isBlank()) return null
        DMY.find(text)?.destructured?.let { (d, m, y) -> return utc(y.toInt(), m.toInt(), d.toInt()) }
        YMD.find(text)?.destructured?.let { (y, m, d) -> return utc(y.toInt(), m.toInt(), d.toInt()) }
        val normalized = SmartSearch.normalize(text)
        if (normalized.startsWith("hoy") || normalized.startsWith("today")) return now
        if (normalized.startsWith("ayer") || normalized.startsWith("yesterday")) return now - DAY_MS
        RELATIVE.find(normalized)?.destructured?.let { (amount, unit) ->
            val n = amount.toIntOrNull() ?: 1
            val days = when (unit) {
                "segundo", "minuto", "hora", "second", "minute", "hour" -> 0.0
                "dia", "day" -> 1.0
                "semana", "week" -> 7.0
                "mes", "meses", "month" -> 30.4
                else -> 365.25
            }
            return now - (n * days * DAY_MS).toLong()
        }
        val words = normalized.split(' ')
        val month = words.firstNotNullOfOrNull { word -> word.takeIf { it.length >= 3 }?.let { MONTHS[it.take(3)] } } ?: return null
        val numbers = NUMBER.findAll(normalized).map { it.value.toInt() }.toList()
        val year = numbers.firstOrNull { it in 1900..2100 } ?: return null
        val day = numbers.firstOrNull { it in 1..31 } ?: 1
        return utc(year, month, day)
    }

    private fun utc(year: Int, month: Int, day: Int): Long? {
        if (year !in 1900..2100 || month !in 1..12 || day !in 1..31) return null
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis
    }
}

/** Ongoing titles that stopped getting chapters long ago. Pure. */
object Abandonment {
    const val YEARS = 2
    private const val THRESHOLD_MS = YEARS * 365L * 86_400_000L

    /** The most recent date among [dates], or null when none can be read. */
    fun latest(dates: List<String>, now: Long = System.currentTimeMillis()): Long? =
        dates.mapNotNull { ChapterDates.parse(it, now) }.filter { it <= now + 86_400_000L }.maxOrNull()

    /**
     * True when the title counts as ongoing ([ongoing]) but its newest chapter is more than two years
     * old. Without a readable date nothing is claimed.
     */
    fun isLikelyAbandoned(ongoing: Boolean, dates: List<String>, now: Long = System.currentTimeMillis()): Boolean {
        if (!ongoing) return false
        val last = latest(dates, now) ?: return false
        return now - last > THRESHOLD_MS
    }
}
