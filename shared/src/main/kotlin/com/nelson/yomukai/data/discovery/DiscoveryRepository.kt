package com.nelson.yomukai.data.discovery

import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** The same work in another source. */
data class Alternative(
    val item: MediaItem,
    val sourceName: String,
    val language: String,
    val confidence: MatchConfidence,
    /** Null when the list could not be read. */
    val chapterCount: Int? = null,
    val lastChapter: Float? = null,
    /** Found by a similar name rather than the same one: the user should confirm it. */
    val approximate: Boolean = false
)

/** Finds what is similar to a title and where else the same title is available. */
class DiscoveryRepository(
    private val sources: SourceManager,
    /** Ids of the sources the user has on; null means the defaults. */
    private val enabledIds: () -> Set<String>?,
    private val aniList: AniListClient,
    private val savedTitles: () -> List<MediaItem>,
    private val filter: () -> TitleFilter
) {
    private fun readableIn(source: CatalogueSource, type: MediaType): Boolean = when (type) {
        MediaType.ANIME -> source is AnimeSource
        MediaType.NOVEL -> source is NovelSource
        else -> source is MangaSource
    }

    private fun candidates(type: MediaType, excludeSourceId: String?): List<CatalogueSource> =
        sources.enabled(enabledIds())
            .filter { it.id != excludeSourceId && readableIn(it, type) }

    /** AniList's names first: they are what other sources tend to use. Latin script searches best. */
    private suspend fun allNames(media: MediaItem): List<String> {
        val own = listOf(media.title) + media.altTitles
        val known = runCatching { aniList.lookup(own, media.type) }.getOrNull()?.names.orEmpty()
        return (own + known).filter { it.isNotBlank() }.distinct()
    }

    /** The last recommendations with what they were built from: entering the tab again costs nothing. */
    @Volatile
    private var lastForYou: Pair<List<String>, List<RelatedTitle>>? = null

    /**
     * Recommendations from [seen]: a title suggested by several of them comes first, and whatever
     * the user already has is left out.
     */
    suspend fun forYou(seen: List<MediaItem>): List<RelatedTitle> {
        // A manhwa and its novel share a name but are two works: the type family is part of every key.
        val seeds = seen.distinctBy { TitleMatcher.familyKey(it.type, it.title) }.take(MAX_SEEDS)
        val seedKey = seeds.map { TitleMatcher.familyKey(it.type, it.title) }
        lastForYou?.takeIf { it.first == seedKey }?.let { return it.second }
        val votes = LinkedHashMap<String, Pair<RelatedTitle, Int>>()
        seeds.forEach { seed ->
            val entry = runCatching { aniList.lookup(listOf(seed.title) + seed.altTitles, seed.type) }.getOrNull() ?: return@forEach
            entry.related.forEach { related ->
                val key = TitleMatcher.familyKey(related.type, related.title)
                votes[key] = related to (votes[key]?.second ?: 0) + 1
            }
        }
        val found = votes.values.sortedByDescending { it.second }.map { it.first }
            // What the user already has is left out, but only in the same format: reading the manhwa
            // still lets its novel or anime be recommended.
            .filterNot { related -> seen.any { TitleMatcher.sameFamily(it.type, related.type) && TitleMatcher.shareName(related.names, listOf(it.title) + it.altTitles) } }
            .filter(filter()::allows)
            .take(MAX_FOR_YOU)
        if (found.isNotEmpty()) lastForYou = seedKey to found
        return found
    }

    /**
     * A random title that respects the excluded genres. When AniList is out of reach it comes from
     * the titles saved from the user's own sources, so the button always gives something.
     */
    suspend fun random(kind: RandomKind, genre: String?): RelatedTitle? {
        repeat(RANDOM_ATTEMPTS) {
            val pick = withTimeoutOrNull(RANDOM_TIMEOUT_MS) { aniList.random(kind, genre) } ?: return@repeat
            if (filter().allows(pick)) return pick
        }
        return savedTitles().filter { kind.type == null || it.type == kind.type }.randomOrNull()
            ?.let { RelatedTitle(it.title, listOf(it.title), it.coverUrl, it.type, item = it) }
    }

    /** Latin-script names of what [query] means, for sites that list it under another one. */
    suspend fun aliases(query: String): List<String> =
        withTimeoutOrNull(ALIAS_TIMEOUT_MS) { runCatching { aniList.otherNames(query) }.getOrNull() }.orEmpty()
            .filter { name -> name.none { it.code > LATIN_LIMIT } && !name.equals(query, ignoreCase = true) }

    private fun searchable(names: List<String>, count: Int = MAX_QUERIES): List<String> =
        names.distinctBy(SmartSearch::duplicateKey).sortedBy { name -> name.any { it.code > LATIN_LIMIT } }.take(count)

    /**
     * Titles like [media]: its community's recommendations, or for works AniList does not cover
     * (Western comics, mostly) what its own source returns for the title's main word.
     */
    suspend fun similar(media: MediaItem): List<RelatedTitle> {
        val own = listOf(media.title) + media.altTitles
        val entry = runCatching { aniList.lookup(own, media.type) }.getOrNull()
        if (entry != null && entry.related.isNotEmpty()) {
            return entry.related.distinctBy { it.title to it.type }.filter(filter()::allows).take(MAX_SIMILAR)
        }
        val word = media.title.split(' ').filter { it.length >= MIN_WORD }.maxByOrNull { it.length } ?: return emptyList()
        val source = sources.get(media.sourceId) ?: return emptyList()
        return source.searchMedia(word).getOrDefault(emptyList())
            .filter { it.id != media.id }
            .take(MAX_SIMILAR)
            .map { RelatedTitle(it.title, listOf(it.title), it.coverUrl, it.type, item = it) }
    }

    /**
     * Copies of [media] in the other enabled sources. [onFound] reports each one as soon as it is
     * confirmed, so slow sources do not hold back the fast ones.
     */
    suspend fun alternatives(media: MediaItem, onFound: (Alternative) -> Unit) {
        val names = allNames(media)
        forEachSource(media.type, media.sourceId) { source ->
            match(source, names, media.author, media.type)?.let { found ->
                val chapters = withTimeoutOrNull(CHAPTERS_TIMEOUT_MS) { source.fetchChapterList(found.item.id) }?.getOrNull()
                onFound(found.copy(chapterCount = chapters?.size, lastChapter = chapters?.maxOfOrNull { it.chapterNumber }))
            }
        }
    }

    /** Places already found for recommended titles in this session, so opening one again is instant. */
    private val located = java.util.concurrent.ConcurrentHashMap<String, List<Alternative>>()

    private fun locateKey(related: RelatedTitle) = related.type.name + "|" + related.names.joinToString("|") { SmartSearch.duplicateKey(it) }

    /**
     * Where a recommended title can be read, for opening it. A title that already comes from a source
     * opens there. Otherwise every enabled source is searched with each of its names; when no entry
     * carries exactly one of them, the closest one that is clearly the same name (another spelling,
     * a subtitle cut off) is offered too, marked as unconfirmed. Titles of excluded genres are left out.
     */
    suspend fun locate(related: RelatedTitle, deadlineMs: Long? = null): List<Alternative> {
        related.item?.let { item ->
            val source = sources.get(item.sourceId)
            if (source != null) return listOf(Alternative(item, source.name, source.language, MatchConfidence.SAME_WORK))
        }
        located[locateKey(related)]?.let { return it }
        val names = (related.names + related.title).filter { it.isNotBlank() }.distinct()
        // Sites search literally: "Sousou no Frieren" finds what "Sousou no Frieren: ..." does not.
        val queries = (searchable(names, MAX_LOCATE_QUERIES) + shortNames(names)).distinctBy(SmartSearch::duplicateKey)
        val found = java.util.Collections.synchronizedList(mutableListOf<Alternative>())
        val search: suspend () -> Unit = {
            forEachSource(related.type, null) { source ->
                val hit = match(source, names, "", related.type, queryList = queries) ?: closest(source, names, related.type)
                if (hit != null && filter().known(source, listOf(hit.item)).isNotEmpty()) found += hit
            }
        }
        // With a deadline, whatever was found by then is the answer: slow sites do not hold it back.
        if (deadlineMs != null) withTimeoutOrNull(deadlineMs) { search() } else search()
        val result = synchronized(found) { found.toList() }.sortedWith(compareBy({ it.confidence.ordinal }, { it.sourceName }))
        if (result.isNotEmpty()) located[locateKey(related)] = result
        return result
    }

    /** The part of each name before a subtitle ("Re:Zero kara..." keeps its colon: too short to cut). */
    private fun shortNames(names: List<String>): List<String> = names.mapNotNull { name ->
        val cut = name.substringBefore(": ").substringBefore(" - ").trim()
        cut.takeIf { it != name.trim() && it.length >= MIN_SHORT_NAME && it.none { c -> c.code > LATIN_LIMIT } }
    }.take(MAX_SHORT_NAMES)

    /**
     * A random title together with where it can be read. Each AniList pick is looked for in the
     * user's sources and only kept when some source has it; after a few misses the pick comes from
     * titles already seen in the user's sources, so the answer always opens.
     */
    suspend fun randomAvailable(kind: RandomKind, genre: String?): Pair<RelatedTitle, List<Alternative>>? {
        repeat(RANDOM_LOCATE_ATTEMPTS) {
            val pick = withTimeoutOrNull(RANDOM_TIMEOUT_MS) { aniList.random(kind, genre) }?.takeIf(filter()::allows) ?: return@repeat
            val places = locate(pick, RANDOM_LOCATE_MS)
            if (places.isNotEmpty()) return pick to places
        }
        val saved = savedTitles().filter { kind.type == null || it.type == kind.type }.randomOrNull() ?: return null
        val pick = RelatedTitle(saved.title, listOf(saved.title), saved.coverUrl, saved.type, saved.genres, item = saved)
        return pick to locate(pick)
    }

    /** The entry whose title is the same name as one of [names] in both directions, for spellings no key catches. */
    private suspend fun closest(source: CatalogueSource, names: List<String>, type: MediaType): Alternative? {
        val query = searchable(names).firstOrNull() ?: return null
        val results = source.searchMedia(query).getOrNull() ?: return null
        val best = results.filter { TitleMatcher.sameFamily(type, it.type) }
            .map { item -> item to names.maxOf { name -> TitleMatcher.closeness(name, item) } }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= CLOSE_SCORE }
            ?: return null
        return Alternative(best.first, source.name, source.language, MatchConfidence.UNCONFIRMED, approximate = true)
    }

    private suspend fun forEachSource(type: MediaType, exclude: String?, block: suspend (CatalogueSource) -> Unit) = coroutineScope {
        val limiter = Semaphore(MAX_PARALLEL)
        candidates(type, exclude).map { source ->
            async { limiter.withPermit { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { block(source) } } }
        }.awaitAll()
    }

    /** Searches [source] for the title and returns the entry that is the same work, if any. */
    private suspend fun match(
        source: CatalogueSource, names: List<String>, author: String, type: MediaType, queryList: List<String>? = null
    ): Alternative? {
        for (query in queryList ?: searchable(names)) {
            val results = source.searchMedia(query).getOrNull() ?: continue
            pick(results, names, author, type)?.let { (item, confidence) ->
                // Search pages rarely list authors: the verdict comes from the title's own page.
                val full = if (item.author.isBlank()) source.fetchMediaDetails(item).getOrNull() ?: item else item
                val checked = TitleMatcher.compare(names, author, type, full).takeIf { it != MatchConfidence.NONE } ?: confidence
                return Alternative(full, source.name, source.language, checked)
            }
            // Search pages rarely list other names: the closest entries' own pages may have them.
            results.filter { TitleMatcher.nearMatch(names, it) }.take(MAX_DETAILS).forEach { near ->
                val full = source.fetchMediaDetails(near).getOrNull() ?: return@forEach
                val confidence = TitleMatcher.compare(names, author, type, full)
                if (confidence != MatchConfidence.NONE) return Alternative(full, source.name, source.language, confidence)
            }
        }
        return null
    }

    private fun pick(results: List<MediaItem>, names: List<String>, author: String, type: MediaType): Pair<MediaItem, MatchConfidence>? =
        results.map { it to TitleMatcher.compare(names, author, type, it) }
            .filter { it.second != MatchConfidence.NONE }
            .minByOrNull { it.second.ordinal }

    private companion object {
        const val ALIAS_TIMEOUT_MS = 3_000L
        const val MAX_SIMILAR = 20
        const val MAX_SEEDS = 8
        const val MAX_FOR_YOU = 30
        const val RANDOM_ATTEMPTS = 4
        const val RANDOM_TIMEOUT_MS = 6_000L
        const val MAX_QUERIES = 3
        const val MAX_LOCATE_QUERIES = 4
        const val MAX_SHORT_NAMES = 2
        const val MIN_SHORT_NAME = 4
        const val RANDOM_LOCATE_ATTEMPTS = 3
        const val RANDOM_LOCATE_MS = 12_000L
        const val CLOSE_SCORE = 0.75
        const val MAX_DETAILS = 2
        const val MAX_PARALLEL = 4
        const val MIN_WORD = 4
        const val LATIN_LIMIT = 0x24F
        const val SOURCE_TIMEOUT_MS = 30_000L
        const val CHAPTERS_TIMEOUT_MS = 20_000L
    }
}
