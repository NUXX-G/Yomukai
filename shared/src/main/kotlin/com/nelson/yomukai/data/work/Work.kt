package com.nelson.yomukai.data.work

import com.nelson.yomukai.data.discovery.MatchConfidence
import com.nelson.yomukai.data.discovery.TitleMatcher
import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType

/**
 * A work, not a source's page about it: "Solo Leveling (manhwa)" is one work whichever sources have it. Identified by
 * AniList's id when known, then MyAnimeList's, then by name and format family. Manhwa and novel of the same name are
 * always two works (the family is part of every key).
 */
object WorkKey {
    fun family(type: MediaType): String = if (type.isReadableAsImages) "images" else type.name.lowercase()

    fun of(type: MediaType, title: String, anilistId: Int? = null, malId: Int? = null): String = when {
        anilistId != null && anilistId > 0 -> "al:$anilistId"
        malId != null && malId > 0 -> "mal:${family(type)}:$malId"
        else -> "t:" + TitleMatcher.familyKey(type, title)
    }

    /** Keys by name are guesses; keys by database id are certain. */
    fun isCertain(key: String) = key.startsWith("al:") || key.startsWith("mal:")

    /** One source's page about a work: "sourceId|mediaId". */
    fun itemKey(item: MediaItem) = itemKey(item.sourceId, item.id)
    fun itemKey(sourceId: String, mediaId: String) = "$sourceId|$mediaId"
}

/**
 * One card per work. [items] are the same work in several sources, best first. When [uncertainWith] is set, this card
 * may be the same work as that other card, but it could not be confirmed (different authors, a short name...): they
 * are shown apart, with a note, rather than merged wrongly.
 */
data class WorkCard(val key: String, val items: List<MediaItem>, val uncertainWith: String? = null) {
    val primary: MediaItem get() = items.first()
    val sources: List<String> get() = items.map { it.sourceId }.distinct()
}

object WorkGrouper {
    /**
     * Groups [items] from many sources into works. [knownKeys] holds what is already known (item key → work key, e.g.
     * from AniList); the rest is decided by name with [TitleMatcher]: the same name and format family joins only when
     * the match is confirmed. Order follows the first appearance of each work; [preferredSources] picks the primary item.
     */
    fun group(items: List<MediaItem>, knownKeys: Map<String, String> = emptyMap(), preferredSources: List<String> = emptyList()): List<WorkCard> {
        val cards = LinkedHashMap<String, MutableList<MediaItem>>()
        val uncertain = HashMap<String, String>()
        val byName = HashMap<String, String>()
        for (item in items) {
            val itemKey = WorkKey.itemKey(item)
            knownKeys[itemKey]?.let { key ->
                cards.getOrPut(key) { mutableListOf() } += item
                names(item).forEach { byName.putIfAbsent(it, key) }
                continue
            }
            val candidateKey = names(item).firstNotNullOfOrNull { byName[it] }
            val existing = candidateKey?.let { cards[it] }
            val confidence = existing?.maxOfOrNull { other -> rank(TitleMatcher.compare(listOf(other.title) + other.altTitles, other.author, other.type, item)) }
            when {
                existing != null && confidence == rank(MatchConfidence.SAME_WORK) -> existing += item
                else -> {
                    var key = WorkKey.of(item.type, item.title)
                    if (cards.containsKey(key)) key = key + "#" + itemKey
                    cards[key] = mutableListOf(item)
                    if (existing != null && candidateKey != null) uncertain[key] = candidateKey
                    names(item).forEach { byName.putIfAbsent(it, key) }
                }
            }
        }
        return cards.map { (key, list) ->
            val sorted = list.sortedWith(compareBy({ preferredSources.indexOf(it.sourceId).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.coverUrl.isBlank() }))
            WorkCard(key, sorted.distinctBy { WorkKey.itemKey(it) }, uncertain[key])
        }
    }

    /**
     * Keys ("source|id") of the entries to show when a list must have one entry per work (library, history): for each
     * work, the one that comes first in [items] (so the most recent, when the list is by recency). Doubtful matches
     * stay as separate entries, and a novel never hides a manhwa of the same name.
     */
    fun firstOfEachWork(items: List<MediaItem>): Set<String> {
        val order = items.withIndex().associate { (i, it) -> WorkKey.itemKey(it) to i }
        return group(items).mapTo(LinkedHashSet()) { card -> WorkKey.itemKey(card.items.minBy { order[WorkKey.itemKey(it)] ?: Int.MAX_VALUE }) }
    }

    private fun names(item: MediaItem) =
        (listOf(item.title) + item.altTitles).map { WorkKey.family(item.type) + "|" + SmartSearch.duplicateKey(it) }.filter { it.length > 10 }

    private fun rank(c: MatchConfidence) = when (c) {
        MatchConfidence.SAME_WORK -> 3
        MatchConfidence.UNCONFIRMED -> 2
        MatchConfidence.DIFFERENT_AUTHOR -> 1
        MatchConfidence.NONE -> 0
    }
}

/** A source's chapter list for merging, with the language of its translation ("ES", "EN"...). */
data class SourceChapters(val sourceId: String, val language: String, val chapters: List<Chapter>)

/** What the user prefers when several sources have the same chapter. */
data class MergePrefs(
    val languages: List<String> = listOf("ES", "EN"),
    val sources: List<String> = emptyList(),
    /** Chosen by hand: chapter number key ("12", "12.5") → source id. */
    val overrides: Map<String, String> = emptyMap()
)

data class ChapterOption(val sourceId: String, val language: String, val chapter: Chapter)

/** One chapter of the work, with every source that has it and the one that will open. */
data class MergedChapter(val number: Float, val options: List<ChapterOption>, val chosen: ChapterOption) {
    val key: String get() = numberKey(number)
    val title: String get() = chosen.chapter.title
}

data class MergedList(val chapters: List<MergedChapter>, val gaps: List<IntRange>, val unnumbered: List<ChapterOption>)

fun numberKey(n: Float): String = if (n % 1f == 0f) n.toInt().toString() else ((n * 10).toInt() / 10f).toString()

object ChapterMerge {
    /**
     * The work's chapters from all its sources: numbers are normalised (taken from the title when a source has none),
     * each number keeps every source that has it, and the one opened follows [prefs]: a manual choice, then the user's
     * languages, then the user's source order, then the source that has the most chapters. Numbers missing everywhere
     * are listed as [MergedList.gaps]; a source that dies or stops only removes options, never chapters others have.
     */
    fun merge(lists: List<SourceChapters>, prefs: MergePrefs = MergePrefs()): MergedList {
        val sizes = lists.associate { it.sourceId to it.chapters.size }
        val byNumber = sortedMapOf<Float, MutableList<ChapterOption>>()
        val unnumbered = mutableListOf<ChapterOption>()
        for (list in lists) {
            for (c in list.chapters) {
            val n = normalizedNumber(c)
            val option = ChapterOption(list.sourceId, list.language.uppercase(), c)
            if (n == null) unnumbered += option else byNumber.getOrPut(n) { mutableListOf() } += option
        }
        }
        val chapters = byNumber.map { (n, options) ->
            val unique = options.distinctBy { it.sourceId }
            MergedChapter(n, unique, choose(numberKey(n), unique, prefs, sizes))
        }
        return MergedList(chapters, gaps(chapters.map { it.number }), unnumbered)
    }

    fun normalizedNumber(c: Chapter): Float? {
        val n = if (c.chapterNumber > 0f) c.chapterNumber else SourceUtils.parseChapterNumber(c.title, -1f)
        return if (n < 0f || n > 100_000f) null else (Math.round(n * 10) / 10f)
    }

    private fun choose(key: String, options: List<ChapterOption>, prefs: MergePrefs, sizes: Map<String, Int>): ChapterOption {
        prefs.overrides[key]?.let { wanted -> options.firstOrNull { it.sourceId == wanted }?.let { return it } }
        // A source with every language (the home Jellyfin: its files carry several audio and subtitle tracks) suits anyone.
        fun langRank(o: ChapterOption) = if (o.language == "MULTI") 0 else prefs.languages.indexOf(o.language).let { if (it < 0) prefs.languages.size else it }
        fun sourceRank(o: ChapterOption) = prefs.sources.indexOf(o.sourceId).let { if (it < 0) prefs.sources.size else it }
        return options.minWith(compareBy<ChapterOption>({ langRank(it) }, { sourceRank(it) }, { -(sizes[it.sourceId] ?: 0) }))
    }

    /** Whole numbers missing between the first and the last chapter (a few ranges at most are worth showing). */
    fun gaps(numbers: List<Float>): List<IntRange> {
        val whole = numbers.filter { it % 1f == 0f && it >= 1f }.map { it.toInt() }.toSortedSet()
        if (whole.size < 2) return emptyList()
        val out = mutableListOf<IntRange>()
        var previous = whole.first()
        for (n in whole.drop(1)) {
            if (n - previous > 1) out += (previous + 1) until n
            previous = n
        }
        return out.take(20)
    }
}

object WorkProgress {
    /**
     * Chapter numbers read in any source of the work: reading chapter 40 in one source marks 40 as read in all of
     * them. [read] are (source, chapter id) pairs marked read.
     */
    fun readNumbers(lists: List<SourceChapters>, read: Set<Pair<String, String>>): Set<String> =
        lists.flatMap { list -> list.chapters.filter { (list.sourceId to it.id) in read }.mapNotNull { ChapterMerge.normalizedNumber(it)?.let(::numberKey) } }.toSet()

    /** The chapter to continue with: the first one after the furthest read, or the first of all. */
    fun next(merged: MergedList, readKeys: Set<String>): MergedChapter? {
        val furthest = merged.chapters.lastOrNull { it.key in readKeys } ?: return merged.chapters.firstOrNull()
        return merged.chapters.firstOrNull { it.number > furthest.number }
    }
}
