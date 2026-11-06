package com.nelson.yomukai.data.work

import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// ---------- Where do I continue? ----------

/**
 * "This anime, up to episode [untilEpisode], adapts [targetKey] up to [chapter] / [volume]". Comes from users (and can
 * be corrected by any of them, with history); AniList only says that the adaptation exists.
 */
data class AdaptationPoint(val animeKey: String, val untilEpisode: Int, val targetKey: String, val chapter: Float?, val volume: Int?)

data class Continuation(val targetKey: String, val chapter: Float?, val volume: Int?)

object WhereNext {
    /** After watching up to [episode], where the source material goes on (the next chapter after the adapted one). */
    fun afterAnime(points: List<AdaptationPoint>, animeKey: String, episode: Int): List<Continuation> =
        points.filter { it.animeKey == animeKey && it.untilEpisode <= episode }
            .groupBy { it.targetKey }
            .map { (target, list) ->
                val furthest = list.maxBy { it.untilEpisode }
                Continuation(target, furthest.chapter?.let { (it + 1f).coerceAtLeast(1f) }, furthest.volume?.let { v -> if (furthest.chapter == null) v + 1 else v })
            }

    /** In the manga or novel: up to which chapter some anime already adapted it (null when no mapping is known). */
    fun animatedUpTo(points: List<AdaptationPoint>, targetKey: String): Float? =
        points.filter { it.targetKey == targetKey }.mapNotNull { it.chapter }.maxOrNull()
}

// ---------- Spoiler shield ----------

object SpoilerShield {
    /** A chapter's title or thumbnail is hidden when it is more than one ahead of where the user is. */
    fun hideChapter(enabled: Boolean, chapterNumber: Float, furthestRead: Float?): Boolean =
        enabled && chapterNumber > (furthestRead ?: 0f) + 1f

    /** A later season's synopsis is hidden until the current one is finished. */
    fun hideSequelSynopsis(enabled: Boolean, relation: String?, finishedCurrent: Boolean): Boolean =
        enabled && !finishedCurrent && relation != null && relation.contains("Secuela", ignoreCase = true)

    /** A comment marked "spoiler up to N" is hidden from whoever has not reached N. */
    fun hideComment(enabled: Boolean, spoilerUpTo: Float?, furthestRead: Float?): Boolean =
        enabled && spoilerUpTo != null && (furthestRead ?: 0f) < spoilerUpTo
}

// ---------- Opening / ending by audio fingerprint ----------

/**
 * Finds the opening and ending of an episode by comparing its Chromaprint fingerprint (`fpcalc -raw`) with another
 * episode of the same series: the longest stretch that sounds the same in both is the opening (near the start) or the
 * ending (near the end). Pure, so it runs and is tested anywhere; the worker computes the fingerprints.
 */
object AudioFingerprint {
    /** Seconds per item of a raw Chromaprint fingerprint. */
    const val ITEM_SECONDS = 0.1238
    private const val MAX_BIT_ERRORS = 10
    private const val MIN_SECONDS = 20.0
    private const val MAX_SECONDS = 150.0

    data class Segment(val startSec: Double, val endSec: Double)
    data class Markers(val opening: Segment?, val ending: Segment?)

    /** The longest run of matching items between [a] and [b] at any shift: (start in a, start in b, length). */
    fun longestMatch(a: IntArray, b: IntArray, maxErrors: Int = MAX_BIT_ERRORS): Triple<Int, Int, Int>? {
        var best: Triple<Int, Int, Int>? = null
        for (shift in -(b.size - 1) until a.size) {
            var run = 0
            var i = maxOf(0, shift)
            var j = i - shift
            while (i < a.size && j < b.size) {
                if (Integer.bitCount(a[i] xor b[j]) <= maxErrors) {
                    run++
                    if (best == null || run > best.third) best = Triple(i - run + 1, j - run + 1, run)
                } else {
                    run = 0
                }
                i++
                j++
            }
        }
        return best
    }

    /**
     * Opening from the first [window] seconds of both episodes, ending from the last [window] seconds. [durationA] is the
     * length of episode a in seconds, to place the ending window.
     */
    fun markers(startA: IntArray, startB: IntArray, endA: IntArray, endB: IntArray, durationA: Double, window: Double): Markers {
        fun segment(a: IntArray, b: IntArray, offset: Double): Segment? {
            val (start, _, length) = longestMatch(a, b) ?: return null
            val seconds = length * ITEM_SECONDS
            if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) return null
            val s = offset + start * ITEM_SECONDS
            return Segment(s, s + seconds)
        }
        return Markers(segment(startA, startB, 0.0), segment(endA, endB, (durationA - window).coerceAtLeast(0.0)))
    }

    fun parseRaw(fpcalcOutput: String): IntArray =
        fpcalcOutput.lineSequence().firstOrNull { it.startsWith("FINGERPRINT=") }?.substringAfter('=')
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull()?.toInt() }?.toIntArray() ?: IntArray(0)
}

// ---------- Release rhythm ----------

/** How often a title gets new chapters, from the dates its sources show. */
data class Cadence(val everyDays: Double, val lastDay: LocalDate, val nextDay: LocalDate, val regular: Boolean)

object ReleaseCadence {
    private val FORMATS = listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "MM/dd/yyyy", "MMMM d, yyyy", "MMM d, yyyy", "d MMMM, yyyy", "d MMM yyyy")
        .flatMap { p -> listOf(DateTimeFormatter.ofPattern(p, Locale.ENGLISH), DateTimeFormatter.ofPattern(p, Locale.forLanguageTag("es"))) }
    private val AGO = Regex("""(\d+)\s*(day|days|día|días|dia|dias|week|weeks|semana|semanas|month|months|mes|meses|hour|hours|hora|horas)""", RegexOption.IGNORE_CASE)

    /** A chapter date as sites write it ("2026-10-31", "31/10/2026", "Oct 31, 2026", "hace 3 días"), or null. */
    fun parseDate(text: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val t = text.trim().removePrefix("hace ").trim()
        if (t.isEmpty()) return null
        AGO.find(t)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2].lowercase()
            return when {
                unit.startsWith("h") -> today
                unit.startsWith("w") || unit.startsWith("sem") -> today.minusWeeks(n)
                unit.startsWith("mo") || unit.startsWith("mes") -> today.minusMonths(n)
                else -> today.minusDays(n)
            }
        }
        val head = t.substringBefore('T').take(30)
        return FORMATS.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(head, f) }.getOrNull() }
    }

    /** Median gap between the most recent releases; null with fewer than 3 dates. Regular when gaps vary little. */
    fun estimate(dates: List<LocalDate>): Cadence? {
        val days = dates.distinct().sorted().takeLast(12)
        if (days.size < 3) return null
        val gaps = days.zipWithNext { a, b -> (b.toEpochDay() - a.toEpochDay()).toDouble() }.filter { it > 0 }
        if (gaps.isEmpty()) return null
        val median = gaps.sorted()[gaps.size / 2]
        val regular = gaps.count { abs(it - median) <= maxOf(1.0, median * 0.25) } * 4 >= gaps.size * 3
        return Cadence(median, days.last(), days.last().plusDays(Math.round(median)), regular)
    }
}

// ---------- Filler ----------

enum class EpisodeKind { CANON, MIXED, FILLER }

object FillerGuide {
    /** Episode number → kind, from an animefillerlist.com show page. */
    fun parse(html: String): Map<Int, EpisodeKind> =
        Jsoup.parse(html).select("table.EpisodeList tr").mapNotNull { row ->
            val n = row.selectFirst("td.Number")?.text()?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val cls = row.className()
            val kind = when {
                cls.contains("mixed") -> EpisodeKind.MIXED
                cls.contains("filler") -> EpisodeKind.FILLER
                else -> EpisodeKind.CANON
            }
            n to kind
        }.toMap()

    /** The site's address for a show: its English title in lower case with dashes. */
    fun slug(title: String): String =
        java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
