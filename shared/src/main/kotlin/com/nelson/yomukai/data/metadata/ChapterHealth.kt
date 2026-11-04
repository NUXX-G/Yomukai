package com.nelson.yomukai.data.metadata

import com.nelson.yomukai.data.search.SearchGroup
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import java.util.concurrent.ConcurrentHashMap

/**
 * How readable a title's chapter list looked the last time it was read. Lists put the [GOOD] ones
 * first and hide the [EMPTY] ones (they exist on the site but have nothing to read yet); [ISSUES] stay
 * visible, only further down. Order of the constants = order in the lists.
 */
enum class ChapterHealth {
    /** Starts at chapter 1 (or 0), no repeated numbers, no disorder, no big holes. */
    GOOD,

    /** Never checked: treated as neutral. */
    UNKNOWN,

    /** Has chapters, but starts late, repeats numbers, is out of order or has holes. */
    ISSUES,

    /** No chapters at all. */
    EMPTY;

    companion object {
        /** Health of a chapter list in any order. Numbers of 0 or below are unknown (prologues, extras). */
        fun of(numbers: List<Float>): ChapterHealth {
            if (numbers.isEmpty()) return EMPTY
            val known = numbers.filter { it > 0f }
            // A list without numbers cannot be judged: it reads fine as far as anyone can tell.
            if (known.isEmpty()) return GOOD
            val ordered = numbers.sorted()
            if (known.min() > FIRST_CHAPTER_MAX && ordered.first() > 0f) return ISSUES
            if (known.size != known.toSet().size) return ISSUES
            return if (ChapterIntegrity.check(ordered).isEmpty()) GOOD else ISSUES
        }

        /** Health from a source's answer: an empty list or [NoChaptersException] is [EMPTY]; other failures say nothing. */
        fun of(result: Result<List<Chapter>>): ChapterHealth? = result.fold(
            onSuccess = { of(it.map { c -> c.chapterNumber }) },
            onFailure = { if (it is NoChaptersException) EMPTY else null }
        )

        /** A first chapter numbered up to this still counts as starting from the beginning (1, 1.1...). */
        private const val FIRST_CHAPTER_MAX = 1.5f
    }
}

/**
 * What is known about the chapter lists of titles, shared by every list and search. Filled whenever a
 * chapter list is read (opening a title, checking search results), kept for a while: titles change.
 */
class ChapterHealthRegistry(private val maxEntries: Int = 20_000, private val goodForMs: Long = 12 * HOUR, private val emptyForMs: Long = 3 * HOUR) {
    private class Entry(val health: ChapterHealth, val at: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(sourceId: String, mediaId: String) = "$sourceId|$mediaId"

    /** Called after every change, e.g. to save the registry. */
    @Volatile var onChange: (() -> Unit)? = null

    fun record(sourceId: String, mediaId: String, health: ChapterHealth?) {
        if (health == null || health == ChapterHealth.UNKNOWN) return
        if (entries.size >= maxEntries) trim()
        val previous = entries.put(key(sourceId, mediaId), Entry(health, System.currentTimeMillis()))
        if (previous?.health != health) onChange?.invoke()
    }

    fun record(sourceId: String, mediaId: String, result: Result<List<Chapter>>) = record(sourceId, mediaId, ChapterHealth.of(result))

    fun get(sourceId: String, mediaId: String): ChapterHealth {
        val e = entries[key(sourceId, mediaId)] ?: return ChapterHealth.UNKNOWN
        // A title without chapters is checked again sooner: the first one may have come out.
        val ttl = if (e.health == ChapterHealth.EMPTY) emptyForMs else goodForMs
        return if (System.currentTimeMillis() - e.at > ttl) ChapterHealth.UNKNOWN else e.health
    }

    fun get(item: MediaItem) = get(item.sourceId, item.id)

    /** Hides titles known to have no chapters and moves the well-kept ones up, otherwise keeping the order. */
    fun arrange(items: List<MediaItem>): List<MediaItem> =
        items.map { it to get(it) }.filter { it.second != ChapterHealth.EMPTY }.sortedBy { it.second.ordinal }.map { it.first }

    /**
     * Search groups: none made only of titles without chapters, each led by its best-kept source, and
     * the groups whose best source still has problems after the rest (otherwise in relevance order).
     */
    fun arrangeGroups(groups: List<SearchGroup>): List<SearchGroup> =
        groups.mapNotNull { g ->
            val members = g.all.filter { get(it) != ChapterHealth.EMPTY }
            if (members.isEmpty()) return@mapNotNull null
            val best = members.minBy { get(it).ordinal }
            g.copy(primary = best, others = members - best)
        }.sortedBy { g -> if (get(g.primary) == ChapterHealth.ISSUES) 1 else 0 }

    /** One line per title ("source|id", health, time): to keep what was learnt between runs. */
    fun export(): String = entries.entries.joinToString("\n") { (k, e) -> "$k\t${e.health.name}\t${e.at}" }

    fun import(text: String) {
        text.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@forEach
            val health = runCatching { ChapterHealth.valueOf(parts[1]) }.getOrNull() ?: return@forEach
            val at = parts[2].toLongOrNull() ?: return@forEach
            entries.putIfAbsent(parts[0], Entry(health, at))
        }
    }

    /** Titles that have never been checked, in order. */
    fun unknown(items: List<MediaItem>): List<MediaItem> = items.filter { get(it) == ChapterHealth.UNKNOWN }

    private fun trim() {
        val oldest = entries.entries.sortedBy { it.value.at }.take(maxEntries / 5)
        oldest.forEach { entries.remove(it.key) }
    }

    companion object {
        private const val HOUR = 60 * 60_000L
    }
}
