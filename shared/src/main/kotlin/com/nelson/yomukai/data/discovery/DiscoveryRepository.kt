package com.nelson.yomukai.data.discovery

import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    private val filter: () -> TitleFilter,
    /** Whether random picks are looked for ahead of time; off for guests on the server. */
    private val prefetch: Boolean = true
) {
    /**
     * Only sources that publish that kind of work are asked: anime in anime sites, novels in novel sites, comics in
     * comic sites. Manga, manhwa and manhua count as one family (sites label them loosely).
     */
    private fun readableIn(source: CatalogueSource, type: MediaType): Boolean = when (type) {
        MediaType.ANIME -> source is AnimeSource
        MediaType.NOVEL -> source is NovelSource
        MediaType.COMIC -> source is MangaSource && MediaType.COMIC in source.supportedTypes
        else -> source is MangaSource && source.supportedTypes.any { it in ASIAN_COMICS }
    }

    private val ASIAN_COMICS = setOf(MediaType.MANGA, MediaType.MANHWA, MediaType.MANHUA)

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
     * Recommendations from [seen] that can actually be read: each candidate is looked for in the
     * user's sources (all at once, a few candidates at a time) and only the ones some source has are
     * kept, so tapping one always opens it, and at once, since where it is stays known.
     */
    suspend fun forYou(seen: List<MediaItem>): List<RelatedTitle> {
        val seedKey = seedKeyOf(seen)
        lastForYou?.takeIf { it.first == seedKey }?.let { return it.second }
        val candidates = recommendations(seen)
        val available = java.util.concurrent.ConcurrentHashMap<Int, RelatedTitle>()
        val limiter = Semaphore(FOR_YOU_CHECK_PARALLEL)
        val finished = withTimeoutOrNull(FOR_YOU_CHECK_MS) {
            coroutineScope {
                candidates.mapIndexed { i, title ->
                    launch { limiter.withPermit { if (locateQuick(title).isNotEmpty()) available[i] = title } }
                }
            }
            true
        } ?: false
        val found = available.toSortedMap().values.take(MAX_FOR_YOU)
        // A check cut short is not kept: next time the known places and misses make it quick anyway.
        if (finished && found.isNotEmpty()) lastForYou = seedKey to found
        return found
    }

    // A manhwa and its novel share a name but are two works: the type family is part of every key.
    private fun seedsOf(seen: List<MediaItem>) = seen.distinctBy { TitleMatcher.familyKey(it.type, it.title) }.take(MAX_SEEDS)
    private fun seedKeyOf(seen: List<MediaItem>) = seedsOf(seen).map { TitleMatcher.familyKey(it.type, it.title) }

    /** AniList's recommendations from [seen]: a title suggested by several comes first; what the user has is left out. */
    private suspend fun recommendations(seen: List<MediaItem>): List<RelatedTitle> {
        val seeds = seedsOf(seen)
        val votes = LinkedHashMap<String, Pair<RelatedTitle, Int>>()
        // The lookups go out together (a few at a time, AniList limits the pace) and are counted in order.
        val limiter = Semaphore(FOR_YOU_PARALLEL)
        val entries = coroutineScope {
            seeds.map { seed ->
                async { limiter.withPermit { withTimeoutOrNull(FOR_YOU_LOOKUP_MS) { runCatching { aniList.lookup(listOf(seed.title) + seed.altTitles, seed.type) }.getOrNull() } } }
            }.awaitAll()
        }
        entries.forEach { entry ->
            entry ?: return@forEach
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
            .take(FOR_YOU_CANDIDATES)
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

    /**
     * The other sources of [media]'s work, remembered like [locate]'s answers: the second time a title's page opens,
     * its sister sources are known at once (and the search is not repeated).
     */
    suspend fun alternativesCached(media: MediaItem, onFound: (Alternative) -> Unit) {
        val key = "alt|" + media.sourceId + "|" + media.id
        located[key]?.let { known -> known.forEach(onFound); return }
        val all = java.util.Collections.synchronizedList(mutableListOf<Alternative>())
        alternatives(media) { found -> all += found; onFound(found) }
        val result = synchronized(all) { all.toList() }
        if (result.isNotEmpty()) {
            located[key] = result
            runCatching { onLocated(key, result) }
        }
    }

    /** Places already found for recommended titles, so opening one again is instant (the app keeps them on disk). */
    private val located = java.util.concurrent.ConcurrentHashMap<String, List<Alternative>>()

    /** Told each time a title's places are found, to keep them between runs. */
    @Volatile
    var onLocated: (key: String, places: List<Alternative>) -> Unit = { _, _ -> }

    /** Places remembered from earlier runs. */
    fun preloadLocated(saved: Map<String, List<Alternative>>) {
        saved.forEach { (k, v) -> if (v.isNotEmpty()) located.putIfAbsent(k, v) }
    }

    /** Recommended titles none of the user's sources has, already checked this session. */
    private val nowhere = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun locateKey(related: RelatedTitle) = related.type.name + "|" + related.names.joinToString("|") { SmartSearch.duplicateKey(it) }

    /**
     * Where a recommended title can be read, for opening it. A title that already comes from a source
     * opens there. Otherwise every enabled source is searched with each of its names; when no entry
     * carries exactly one of them, the closest one that is clearly the same name (another spelling,
     * a subtitle cut off) is offered too, marked as unconfirmed. Titles of excluded genres are left out.
     */
    suspend fun locate(related: RelatedTitle, deadlineMs: Long? = null, onFound: (Alternative) -> Unit = {}): List<Alternative> {
        related.item?.let { item ->
            val source = sources.get(item.sourceId)
            if (source != null) return listOf(Alternative(item, source.name, source.language, MatchConfidence.SAME_WORK)).onEach(onFound)
        }
        located[locateKey(related)]?.let { known -> return known.onEach(onFound) }
        val names = (related.names + related.title).filter { it.isNotBlank() }.distinct()
        // Sites search literally: "Sousou no Frieren" finds what "Sousou no Frieren: ..." does not.
        val queries = (searchable(names, MAX_LOCATE_QUERIES) + shortNames(names)).distinctBy(SmartSearch::duplicateKey)
        val found = java.util.Collections.synchronizedList(mutableListOf<Alternative>())
        val search: suspend () -> Unit = {
            forEachSource(related.type, null) { source ->
                val hit = match(source, names, "", related.type, queryList = queries) ?: closest(source, names, related.type)
                if (hit != null && filter().known(source, listOf(hit.item)).isNotEmpty()) {
                    found += hit
                    // The first confirmed place can open at once: the others keep coming in behind it.
                    runCatching { onFound(hit) }
                }
            }
        }
        // With a deadline, whatever was found by then is the answer: slow sites do not hold it back.
        if (deadlineMs != null) withTimeoutOrNull(deadlineMs) { search() } else search()
        val result = synchronized(found) { found.toList() }.sortedWith(compareBy({ it.confidence.ordinal }, { it.sourceName }))
        if (result.isNotEmpty()) {
            located[locateKey(related)] = result
            runCatching { onLocated(locateKey(related), result) }
        }
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
    suspend fun randomAvailable(kind: RandomKind, requestedGenre: String?): Pair<RelatedTitle, List<Alternative>>? {
        // Only the genres the app offers: anything else would be a new key with its own background work.
        val genre = requestedGenre?.takeIf { it in RANDOM_GENRES }
        val key = "${kind.name}|${genre.orEmpty()}"
        // A pick found ahead of time answers at once; the next ones are looked for meanwhile.
        val ready = synchronized(prefetched) { prefetched[key]?.removeFirstOrNull() }
        refill(kind, genre, key)
        ready?.takeIf { filter().allows(it.first) }?.let { return it }
        findRandom(kind, genre)?.let { return it }
        val saved = savedTitles().filter { kind.type == null || it.type == kind.type }.randomOrNull() ?: return null
        val pick = RelatedTitle(saved.title, listOf(saved.title), saved.coverUrl, saved.type, saved.genres, item = saved)
        return pick to locate(pick)
    }

    /** Random picks already found in the user's sources, per kind and genre. */
    private val prefetched = HashMap<String, ArrayDeque<Pair<RelatedTitle, List<Alternative>>>>()
    private val refilling = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun refill(kind: RandomKind, genre: String?, key: String) {
        if (!prefetch) return
        // A handful of kind/genre pairs per user is plenty; the queue for a new one waits its turn.
        if (synchronized(prefetched) { key !in prefetched && prefetched.size >= MAX_PREFETCH_KEYS }) return
        if (!refilling.add(key)) return
        background.launch {
            try {
                // Background searches of every user share a few slots, so the sites are never flooded.
                prefetchSlots.withPermit {
                    while (synchronized(prefetched) { prefetched[key]?.size ?: 0 } < PREFETCH) {
                        val found = findRandom(kind, genre) ?: break
                        synchronized(prefetched) { prefetched.getOrPut(key) { ArrayDeque() }.addLast(found) }
                    }
                }
            } finally {
                refilling.remove(key)
            }
        }
    }

    /** Stops what is being looked for ahead of time; called when this user's instance is replaced. */
    fun close() = background.cancel()

    /** Two AniList picks are looked for at the same time; the first one some source has wins. */
    private suspend fun findRandom(kind: RandomKind, genre: String?): Pair<RelatedTitle, List<Alternative>>? {
        repeat(RANDOM_LOCATE_ATTEMPTS) {
            val picks = (1..RANDOM_RACE)
                .mapNotNull { withTimeoutOrNull(RANDOM_TIMEOUT_MS) { aniList.random(kind, genre) }?.takeIf(filter()::allows) }
                .distinctBy { TitleMatcher.familyKey(it.type, it.title) }
            val tasks: List<suspend () -> Pair<RelatedTitle, List<Alternative>>?> = picks.map { pick ->
                { locateQuick(pick).takeIf { it.isNotEmpty() }?.let { pick to it } }
            }
            firstOf(tasks)?.let { return it }
        }
        return null
    }

    /** The first non-null answer of [tasks], run together; the rest are stopped. */
    private suspend fun <T : Any> firstOf(tasks: List<suspend () -> T?>): T? = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope null
        val answers = Channel<T?>(tasks.size)
        val jobs = tasks.map { task -> launch { answers.send(runCatching { task() }.getOrNull()) } }
        var found: T? = null
        repeat(tasks.size) { if (found == null) found = answers.receive() }
        jobs.forEach { it.cancel() }
        found
    }

    /**
     * Where [related] can be read, asking every source at once and answering soon after the first
     * one has it: for a random pick one place is enough to open it, and slow sites do not hold it up.
     */
    private suspend fun locateQuick(related: RelatedTitle): List<Alternative> {
        if (related.item != null) return locate(related)
        located[locateKey(related)]?.let { return it }
        if (locateKey(related) in nowhere) return emptyList()
        val names = (related.names + related.title).filter { it.isNotBlank() }.distinct()
        val queries = (searchable(names, MAX_LOCATE_QUERIES) + shortNames(names)).distinctBy(SmartSearch::duplicateKey)
        val found = java.util.Collections.synchronizedList(mutableListOf<Alternative>())
        val completed = withTimeoutOrNull(RANDOM_LOCATE_MS) {
            coroutineScope {
                val first = CompletableDeferred<Unit>()
                val searches = candidates(related.type, null).map { source ->
                    async {
                        withTimeoutOrNull(QUICK_SOURCE_MS) {
                            val hit = match(source, names, "", related.type, queryList = queries) ?: closest(source, names, related.type)
                            if (hit != null && filter().known(source, listOf(hit.item)).isNotEmpty()) {
                                found += hit
                                first.complete(Unit)
                            }
                        }
                    }
                }
                launch { runCatching { searches.awaitAll() }; first.complete(Unit) }
                first.await()
                // A short moment more, so other sources that already have it are listed too.
                withTimeoutOrNull(QUICK_GRACE_MS) { runCatching { searches.awaitAll() } }
                coroutineContext.cancelChildren()
            }
            true
        } != null
        val result = synchronized(found) { found.toList() }.sortedWith(compareBy({ it.confidence.ordinal }, { it.sourceName }))
        if (result.isNotEmpty()) located[locateKey(related)] = result
        // Every source answered without it: not looked for again this session.
        else if (completed) nowhere += locateKey(related)
        return result
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

    companion object {
        /** The AniList genres the random picker offers (same list in the app and the web). */
        val RANDOM_GENRES = setOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
            "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller"
        )
        private const val MAX_PREFETCH_KEYS = 4

        /** Background random searches running at once, across every user of the process. */
        private val prefetchSlots = Semaphore(2)
        private const val ALIAS_TIMEOUT_MS = 3_000L
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
        const val RANDOM_RACE = 2
        const val PREFETCH = 2
        const val QUICK_SOURCE_MS = 10_000L
        const val QUICK_GRACE_MS = 1_200L
        const val CLOSE_SCORE = 0.75
        const val MAX_DETAILS = 2
        const val MAX_PARALLEL = 12
        const val FOR_YOU_PARALLEL = 4
        const val FOR_YOU_CANDIDATES = 40
        const val FOR_YOU_CHECK_PARALLEL = 6
        const val FOR_YOU_CHECK_MS = 15_000L
        const val FOR_YOU_LOOKUP_MS = 8_000L
        const val MIN_WORD = 4
        const val LATIN_LIMIT = 0x24F
        const val SOURCE_TIMEOUT_MS = 15_000L
        const val CHAPTERS_TIMEOUT_MS = 10_000L
    }
}
