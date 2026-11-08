package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nelson.yomukai.data.discovery.AniListClient
import com.nelson.yomukai.data.discovery.DiscoveryRepository
import com.nelson.yomukai.data.discovery.GenreRules
import com.nelson.yomukai.data.discovery.RandomKind
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.data.discovery.TitleFilter
import com.nelson.yomukai.data.metadata.Abandonment
import com.nelson.yomukai.data.metadata.ChapterHealth
import com.nelson.yomukai.data.metadata.ChapterHealthRegistry
import com.nelson.yomukai.data.metadata.ChapterIntegrity
import com.nelson.yomukai.data.metadata.ChapterIssue
import com.nelson.yomukai.data.metadata.MetadataRepository
import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.search.SourceSearch
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.PublicationStatus
import com.nelson.yomukai.ui.details.ChapterLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** What a user's settings say about sources and genres. The web app owns the rest of the document. */
/**
 * [showAdult]: sexual content (adult, hentai, smut…; ecchi is not) is hidden unless the user switched it on; when hidden,
 * those genres join [globalExclusions].
 */
data class UserPrefs(
    val enabledSources: Set<String>?,
    val globalExclusions: Set<String>,
    val perSource: Map<String, Set<String>>,
    val showAdult: Boolean = false
) {
    /** An exact, unambiguous (JSON) form of these settings: two different settings never share a key. */
    fun cacheKey(): String = com.google.gson.Gson().toJson(
        listOf(enabledSources?.sorted(), globalExclusions.sorted(), perSource.toSortedMap().mapValues { it.value.sorted() })
    )

    fun excludedFor(sourceId: String): Set<String> = globalExclusions + perSource[sourceId].orEmpty()

    companion object {
        /** The genre group (see GenreTaxonomy) of sexual content: adult, hentai, smut… (ecchi is not hidden). */
        val ADULT_GENRES = setOf("Adulto")

        val DEFAULT = UserPrefs(null, ADULT_GENRES, emptyMap())

        /**
         * Sources that existed before lists started recording which sources the user had seen. A saved list
         * knows nothing of sources added later: those start switched on, like in the Android app.
         */
        private val LEGACY_KNOWN = setOf(
            "mangadex", "olympus", "asura", "flame", "lectortmo", "zonatmo", "webtoons", "webtoons_en", "marmota",
            "royalroad", "skynovels", "tunovelaligera", "animeav1", "animeflv"
        )

        fun parse(json: String?, allSources: Set<String> = emptySet()): UserPrefs {
            val root = runCatching { JsonParser.parseString(json ?: return DEFAULT).asJsonObject }.getOrNull() ?: return DEFAULT
            fun JsonObject.set(key: String): Set<String>? =
                getAsJsonArray(key)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }?.toSet()
            val exclusions = root.getAsJsonObject("exclusions")
            val showAdult = root.get("showAdult")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            return UserPrefs(
                enabledSources = root.takeIf { it.has("enabledSources") && it.get("enabledSources").isJsonArray }?.set("enabledSources")?.let { saved ->
                    val known = root.set("knownSources") ?: LEGACY_KNOWN
                    saved + (allSources - known)
                },
                globalExclusions = exclusions?.set("global").orEmpty() + (if (showAdult) emptySet() else ADULT_GENRES),
                perSource = exclusions?.getAsJsonObject("perSource")?.entrySet()?.associate { (k, v) ->
                    k to v.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.asString }?.toSet().orEmpty()
                }.orEmpty(),
                showAdult = showAdult
            )
        }
    }
}

/** Genre exclusions of one user, from what lists already say; the server never reads pages just to filter. */
class UserFilter(private val prefs: UserPrefs, private val knownGenres: (MediaItem) -> List<String>) : TitleFilter {
    override suspend fun known(source: CatalogueSource, items: List<MediaItem>): List<MediaItem> {
        val excluded = prefs.excludedFor(source.id)
        if (excluded.isEmpty()) return items
        return items.filterNot { GenreRules.isExcluded(knownGenres(it) + it.type.displayName, excluded) || hiddenAsAdult(it.title) }
    }

    override fun allows(related: RelatedTitle): Boolean =
        !GenreRules.isExcluded(related.genres + related.tags + related.type.displayName, prefs.globalExclusions) && !hiddenAsAdult(related.title)

    /** Sites that do not tag sexual content: a title that says so is hidden too, unless the user shows such content. */
    private fun hiddenAsAdult(title: String) = !prefs.showAdult && com.nelson.yomukai.data.metadata.AdultHints.isExplicit(title)
}

/**
 * Everything about titles: the sources from :shared, with answers shared by every user through
 * short-lived caches. Identical requests in flight at the same time also share one fetch.
 */
class Catalog(
    val sources: SourceManager,
    private val aniList: AniListClient,
    private val metadata: MetadataRepository,
    private val proxy: MediaProxy
) {
    val dtos = Dtos(proxy) { id -> sources.get(id)?.imageHeaders.orEmpty() }
    private val gson = Gson()

    /** Every title seen in a list, so its page can be opened by source and id alone. */
    private val seen = TtlCache<String, MediaItem>(50_000, 12 * HOUR)
    private val latest = TtlCache<String, List<MediaItem>>(2_000, 10 * MINUTE)
    private val details = TtlCache<String, MediaItem>(5_000, 30 * MINUTE)
    private val chapters = TtlCache<String, List<Chapter>>(5_000, 15 * MINUTE)
    private val pages = TtlCache<String, List<String>>(5_000, HOUR)

    // Novel chapters can be 100 KB each: fewer of them.
    private val texts = TtlCache<String, String>(400, HOUR)
    private val videos = TtlCache<String, List<com.nelson.yomukai.domain.model.VideoSource>>(2_000, 10 * MINUTE)
    private val alternativesCache = TtlCache<String, List<AlternativeDto>>(2_000, 30 * MINUTE)
    private val searches = TtlCache<String, com.nelson.yomukai.data.search.SourceSearchResult>(2_000, 15 * MINUTE)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Result<Any?>>>()

    /** What each title's chapter list looked like: lists hide the empty ones and put the well-kept first. */
    val health = ChapterHealthRegistry()
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probing = ConcurrentHashMap.newKeySet<String>()
    private val probeLimit = Semaphore(PROBE_PARALLEL)

    /** One discovery per user: it keeps that user's recent finds. */
    private val discoveries = TtlCache<Long, Pair<UserPrefs, DiscoveryRepository>>(2_000, HOUR)
    private val discoveryLock = Mutex()

    private fun key(vararg parts: String) = parts.joinToString("|")

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> shared(key: String, block: suspend () -> Result<T>): Result<T> {
        val mine = CompletableDeferred<Result<Any?>>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return existing.await() as Result<T>
        return try {
            block().also { mine.complete(it as Result<Any?>) }
        } catch (e: Throwable) {
            mine.complete(Result.failure(e))
            throw e
        } finally {
            inFlight.remove(key)
        }
    }

    /**
     * Stale-while-revalidate: a fresh cached answer as is; one older but within [staleMs] at once too,
     * while a fresh one is fetched behind it for the next request; otherwise a fetch, shared by every
     * identical request in flight.
     */
    private suspend fun <T> swr(cache: TtlCache<String, T>, k: String, staleMs: Long, load: suspend () -> Result<T>): Result<T> {
        cache.get(k)?.let { return Result.success(it) }
        val stale = cache.getAged(k)?.takeIf { it.second < staleMs }?.first
        if (stale != null) {
            probeScope.launch { shared(k) { load().onSuccess { cache.put(k, it) } } }
            return Result.success(stale)
        }
        return shared(k) { load().onSuccess { cache.put(k, it) } }
    }

    private fun remember(items: List<MediaItem>): List<MediaItem> {
        items.forEach { seen.put(key(it.sourceId, it.id), it) }
        CoverHosts.learn(items)
        return items
    }

    fun knownGenres(item: MediaItem): List<String> =
        item.genres.ifEmpty { details.get(key(item.sourceId, item.id))?.genres ?: seen.get(key(item.sourceId, item.id))?.genres.orEmpty() }

    fun filterFor(prefs: UserPrefs) = UserFilter(prefs, ::knownGenres)

    fun enabled(prefs: UserPrefs) = sources.enabled(prefs.enabledSources)

    /** Whether the chapter list is already in memory, so reading it costs the site nothing. */
    fun hasCachedChapters(sourceId: String, mediaId: String) = chapters.get("chapters|" + key(sourceId, mediaId)) != null

    /** [query] in one source only; what it finds is remembered like any other result. */
    suspend fun searchOne(sourceId: String, query: String): Result<List<MediaItem>> = runCatching {
        val source = require(sourceId)
        require(source.id !in sources.hidden) { "Fuente quitada por el administrador" }
        source.searchMedia(query).getOrThrow().also { remember(it) }
    }

    private fun require(id: String): CatalogueSource = sources.get(id) ?: throw NoSuchElementException("Fuente desconocida: $id")

    suspend fun latest(sourceId: String, page: Int, prefs: UserPrefs): Result<List<MediaDto>> {
        val source = require(sourceId)
        val k = key("latest", sourceId, page.toString())
        val items = swr(latest, k, LIST_STALE_MS) { source.fetchLatestUpdates(page).onSuccess { remember(it) } }
        return items.map { list -> probe(list, PROBE_PER_LIST); health.arrange(filterFor(prefs).known(source, list)).map(dtos::media) }
    }

    /**
     * Reads, in the background and a few at a time, the chapter lists of titles never checked: the next
     * time they show, the empty ones are gone and the well-kept ones go first. Opening them is then
     * instant too, the list being in memory already.
     */
    fun probe(items: List<MediaItem>, max: Int) {
        health.unknown(items).asSequence()
            .filter { sources.get(it.sourceId)?.cheapChapterList == true }
            .take(max)
            .forEach { item ->
                val k = key(item.sourceId, item.id)
                if (chapters.getAged("chapters|$k") != null || !probing.add(k)) return@forEach
                probeScope.launch {
                    try { probeLimit.withPermit { withTimeoutOrNull(PROBE_TIMEOUT_MS) { chapterList(item.sourceId, item.id) } } } finally { probing.remove(k) }
                }
            }
    }

    /** The source's chapter list, cached, shared with identical requests in flight, its health noted. */
    suspend fun chapterList(sourceId: String, mediaId: String): Result<List<Chapter>> {
        val source = require(sourceId)
        val k = key(sourceId, mediaId)
        return swr(chapters, "chapters|$k", CHAPTERS_STALE_MS) {
            source.fetchChapterList(mediaId).also { health.record(sourceId, mediaId, it) }
        }
    }

    /** The chapter list if it is in memory (never fetches): for the calendar. */
    fun cachedChapters(sourceId: String, mediaId: String): List<Chapter>? = chapters.getAged("chapters|" + key(sourceId, mediaId))?.first

    /** Work keys already known for items (set by [Works]); used to keep one card per work. */
    @Volatile
    var workKeys: (List<MediaItem>) -> Map<String, String> = { emptyMap() }

    /**
     * "Recently updated" across every enabled source, one card per work: each source's first page (cached), merged in
     * turns so no source floods the row, grouped by work. A card whose work could not be confirmed keeps its own place.
     */
    suspend fun unifiedLatest(prefs: UserPrefs, max: Int = 60): List<Pair<MediaDto, List<MediaDto>>> = coroutineScope {
        val lists = enabled(prefs).map { source ->
            async {
                val k = key("latest", source.id, "1")
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) { swr(latest, k, LIST_STALE_MS) { source.fetchLatestUpdates(1).onSuccess { remember(it) } } }
                    ?.getOrNull()?.let { filterFor(prefs).known(source, it) }.orEmpty()
            }
        }.map { it.await() }
        val interleaved = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        com.nelson.yomukai.data.work.WorkGrouper.group(interleaved, workKeys(interleaved)).take(max)
            .map { card -> dtos.media(card.primary) to card.items.drop(1).map(dtos::media) }
    }

    suspend fun browse(sourceId: String, page: Int, filtersJson: String?, prefs: UserPrefs): Result<List<MediaDto>> {
        val source = require(sourceId)
        val values: FilterValues = filtersJson?.takeIf { it.isNotBlank() }?.let {
            gson.fromJson<Map<String, List<String>>>(it, object : TypeToken<Map<String, List<String>>>() {}.type)
                .mapValues { (_, v) -> v.toSet() }
        }.orEmpty()
        val k = key("browse", sourceId, page.toString(), values.toSortedMap().toString())
        val items = swr(latest, k, LIST_STALE_MS) { source.browse(page, values).onSuccess { remember(it) } }
        return items.map { list -> probe(list, PROBE_PER_LIST); health.arrange(filterFor(prefs).known(source, list)).map(dtos::media) }
    }

    /**
     * Searches every enabled source in parallel and reports the ranked, grouped results each time a
     * source answers, so the fast ones show at once.
     */

    /** Per source, the searches it failed recently (normalised query → when). See [search]. */
    private val searchFailures = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    suspend fun search(
        query: String, types: Set<MediaType>?, language: String?, prefs: UserPrefs,
        onUpdate: suspend (SearchUpdateDto) -> Unit
    ) = coroutineScope {
        val now = System.currentTimeMillis()
        val wanted = enabled(prefs)
            .filter { language == null || it.language == "MULTI" || it.language == language }
            .filter { s -> types == null || s.supportedTypes.any { it in types } }
        // A site that is down (it failed several different searches in the last minutes, so not because of one odd
        // query) is skipped for a while instead of making everyone wait; it is listed as not answering.
        val (targets, skipped) = wanted.partition { s -> searchFailures[s.id].orEmpty().count { it.value > now - SEARCH_SKIP_MS } < SEARCH_SKIP_AFTER }
        val found = ConcurrentHashMap<String, List<MediaItem>>()
        val errors = ConcurrentHashMap<String, String>().apply { skipped.forEach { put(it.id, "${it.name} no responde ahora mismo") } }
        val trusted = ConcurrentHashMap.newKeySet<String>()
        val pending = ConcurrentHashMap.newKeySet<String>().apply { addAll(targets.map { it.name }) }
        val lock = Mutex()
        val aliases = async(start = CoroutineStart.LAZY) {
            withTimeoutOrNull(3_000) { runCatching { aniList.otherNames(query) }.getOrNull() }.orEmpty()
                .filter { name -> name.none { it.code > 0x24F } && !name.equals(query, ignoreCase = true) }
        }
        val filter = filterFor(prefs)
        // Nothing to ask (no source for that kind, or all down): the search ends at once instead of waiting forever.
        if (targets.isEmpty()) onUpdate(SearchUpdateDto(emptyList(), emptyList(), errors.toMap(), true))
        targets.map { source ->
            launch {
                val k = key("search", source.id, SmartSearch.normalize(query))
                val result = searches.get(k) ?: withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    runCatching { SourceSearch.search(source, query) { aliases.await() } }.getOrElse { e ->
                        if (e is CancellationException) throw e
                        com.nelson.yomukai.data.search.SourceSearchResult(emptyList(), e.message ?: "Error", emptySet())
                    }
                }?.also { if (it.error == null) searches.put(k, it) }
                if (result == null) errors[source.id] = "${source.name} tardó demasiado"
                val failed = searchFailures.getOrPut(source.id) { ConcurrentHashMap() }
                if (result == null || (result.error != null && result.items.isEmpty())) {
                    failed[SmartSearch.normalize(query).take(MAX_FAILED_QUERY)] = System.currentTimeMillis()
                    if (failed.size > SEARCH_SKIP_AFTER * 4) failed.entries.minByOrNull { it.value }?.let { failed.remove(it.key) }
                } else {
                    failed.clear()
                }
                result?.let { r ->
                    remember(r.items)
                    trusted += r.trusted
                    r.error?.let { errors[source.id] = it }
                    found[source.id] = filter.known(source, r.items).filter { types == null || it.type in types }
                }
                pending -= source.name
                lock.withLock {
                    val groups = splitDoubtful(health.arrangeGroups(SmartSearch.rankAndGroup(query, found.values.flatten(), trusted = trusted.toSet())))
                    if (pending.isEmpty()) probe(groups.take(PROBE_PER_LIST).flatMap { (g, _) -> listOf(g.primary) + g.others.take(2) }, PROBE_PER_LIST * 2)
                    onUpdate(
                        SearchUpdateDto(
                            groups.take(MAX_GROUPS).map { (g, doubt) -> SearchGroupDto(dtos.media(g.primary), g.others.map(dtos::media), doubt) },
                            pending.toList(), errors.toMap(), pending.isEmpty()
                        )
                    )
                }
            }
        }
    }

    /**
     * Search groups items by name; a group whose members are not confirmed to be the same work (different authors, a
     * short shared name) is split, and the parts are flagged as "may be the same" instead of being merged wrongly.
     */
    private fun splitDoubtful(groups: List<com.nelson.yomukai.data.search.SearchGroup>): List<Pair<com.nelson.yomukai.data.search.SearchGroup, Boolean>> =
        groups.flatMap { g ->
            val members = listOf(g.primary) + g.others
            val cards = com.nelson.yomukai.data.work.WorkGrouper.group(members, workKeys(members))
            if (cards.size == 1) listOf(g to false)
            else cards.map { c -> com.nelson.yomukai.data.search.SearchGroup(c.primary, c.items.drop(1), g.score) to true }
        }

    /** The title's page; [hint] fills in what the list knew when the source has no details. */
    suspend fun media(sourceId: String, mediaId: String): Result<MediaItem> {
        val source = require(sourceId)
        val k = key(sourceId, mediaId)
        details.get(k)?.let { return Result.success(it) }
        val base = seen.get(k) ?: MediaItem(mediaId, "", "", source.supportedTypes.first(), "", "", sourceId)
        return shared("details|$k") {
            source.fetchMediaDetails(base).map { full ->
                // Sources fill in only what their page shows; the list's cover and title stay when the page has none.
                full.copy(title = full.title.ifBlank { base.title }, coverUrl = full.coverUrl.ifBlank { base.coverUrl })
            }.onSuccess { details.put(k, it); seen.put(k, it) }
        }
    }

    suspend fun chapters(sourceId: String, mediaId: String, ongoingHint: PublicationStatus?): Result<ChaptersDto> {
        val source = require(sourceId)
        val k = key(sourceId, mediaId)
        val list = chapterList(sourceId, mediaId)
        val mediaTitle = (details.get(k) ?: seen.get(k))?.title.orEmpty()
        val isAnime = source is AnimeSource
        return list.map { raw ->
            // Same order as the app: by number, keeping the source's order for equal numbers.
            val ordered = raw.withIndex().sortedWith(compareBy({ it.value.chapterNumber }, { it.index })).map { it.value }
            val dtos = ordered.mapIndexed { index, c ->
                val label = ChapterLabels.of(c.title, c.chapterNumber, isAnime, mediaTitle)
                ChapterDto(c.id, c.title, c.chapterNumber, c.url, c.dateUploaded, index, label.short, label.name)
            }
            val issues = ChapterIntegrity.check(ordered.map { it.chapterNumber })
            val dates = ordered.map { it.dateUploaded }
            val status = ongoingHint ?: PublicationStatus.parse((details.get(k) ?: seen.get(k))?.status.orEmpty())
            ChaptersDto(
                chapters = dtos,
                issues = issues.map { issue ->
                    val after = (issue as? ChapterIssue.Gap)?.let { dtos.getOrNull(it.afterIndex)?.id }
                    this.dtos.issue(issue, ChapterIntegrity.describe(issue, isAnime), after)
                },
                abandoned = Abandonment.isLikelyAbandoned(status == PublicationStatus.ONGOING, dates),
                lastUpdate = Abandonment.latest(dates)
            )
        }
    }

    suspend fun pages(sourceId: String, chapterUrl: String): Result<List<String>> {
        val source = require(sourceId) as? MangaSource ?: return Result.failure(IllegalArgumentException("No es una fuente de imágenes"))
        val k = key(sourceId, chapterUrl)
        val list = pages.get(k)?.let { Result.success(it) } ?: shared("pages|$k") { source.fetchPageList(chapterUrl).onSuccess { pages.put(k, it) } }
        // The pages start coming to this server now, ahead of the reader.
        list.onSuccess { proxy.warm(it, source.imageHeaders) }
        return list.map { urls -> urls.map { proxy.image(it, source.imageHeaders) } }
    }

    /** Page addresses as the site gives them, with the headers its images need; for downloads. */
    suspend fun rawPages(sourceId: String, chapterUrl: String): Result<Pair<List<String>, Map<String, String>>> {
        val source = require(sourceId) as? MangaSource ?: return Result.failure(IllegalArgumentException("No es una fuente de imágenes"))
        val k = key(sourceId, chapterUrl)
        val list = pages.get(k)?.let { Result.success(it) } ?: shared("pages|$k") { source.fetchPageList(chapterUrl).onSuccess { pages.put(k, it) } }
        return list.map { it to source.imageHeaders }
    }

    /** Video servers as the site gives them; for downloads. */
    suspend fun rawVideos(sourceId: String, episodeUrl: String): Result<List<com.nelson.yomukai.domain.model.VideoSource>> {
        val source = require(sourceId) as? AnimeSource ?: return Result.failure(IllegalArgumentException("No es una fuente de anime"))
        val k = key(sourceId, episodeUrl)
        return videos.get(k)?.let { Result.success(it) } ?: shared("videos|$k") { source.fetchVideoSources(episodeUrl).onSuccess { videos.put(k, it) } }
    }

    fun languageOf(sourceId: String): String? = sources.get(sourceId)?.language

    fun imageHeadersOf(sourceId: String): Map<String, String> = sources.get(sourceId)?.imageHeaders.orEmpty()

    fun kindOf(sourceId: String): String? = sources.get(sourceId)?.let { s ->
        when (s) { is AnimeSource -> "anime"; is NovelSource -> "novel"; is MangaSource -> "manga"; else -> null }
    }

    suspend fun text(sourceId: String, chapterUrl: String): Result<String> {
        val source = require(sourceId) as? NovelSource ?: return Result.failure(IllegalArgumentException("No es una fuente de novelas"))
        val k = key(sourceId, chapterUrl)
        // Some sites send half of an emoji; such text cannot be written out as UTF-8, so it is dropped.
        return texts.get(k)?.let { Result.success(it) } ?: shared("text|$k") { source.fetchChapterText(chapterUrl).map(::withoutBrokenSurrogates).onSuccess { texts.put(k, it) } }
    }

    suspend fun videos(sourceId: String, episodeUrl: String): Result<List<VideoDto>> {
        val source = require(sourceId) as? AnimeSource ?: return Result.failure(IllegalArgumentException("No es una fuente de anime"))
        val k = key(sourceId, episodeUrl)
        val list = videos.get(k)?.let { Result.success(it) } ?: shared("videos|$k") { source.fetchVideoSources(episodeUrl).onSuccess { videos.put(k, it) } }
        return list.map { servers ->
            servers.map { v ->
                val hls = v.isDirect && (v.videoUrl.contains("m3u8") || v.quality.contains("HLS", ignoreCase = true))
                // The server's own routes (the home Jellyfin) are links already; everything else direct goes through the proxy.
                val url = when { v.videoUrl.startsWith("/api/") -> v.videoUrl; v.isDirect -> proxy.stream(v.videoUrl, v.headers); else -> v.videoUrl }
                VideoDto(v.serverName, v.quality, url, v.isDirect, hls, if (v.isDirect) null else proxy.embed(v.videoUrl))
            }
        }
    }

    /** [anilistId]: when the works index already knows which AniList work this page is. */
    suspend fun metadata(sourceId: String, mediaId: String, anilistId: Int? = null): Result<MetadataDto> = runCatching {
        val item = media(sourceId, mediaId).getOrNull() ?: seen.get(key(sourceId, mediaId))
            ?: throw NoSuchElementException("Título no encontrado")
        dtos.metadata(metadata.load(item, anilistId))
    }

    private suspend fun discovery(user: User, prefs: UserPrefs): DiscoveryRepository = discoveryLock.withLock {
        val current = discoveries.get(user.id)
        current?.takeIf { it.first == prefs }?.second ?: DiscoveryRepository(
            sources, { prefs.enabledSources }, aniList,
            savedTitles = { latest.values().flatten() },
            filter = { filterFor(prefs) },
            // Guests get no picks looked for ahead of time: that work would not count against their limits.
            prefetch = !user.isGuest
        ).also {
            current?.second?.close()
            discoveries.put(user.id, prefs to it)
        }
    }

    suspend fun similar(user: User, prefs: UserPrefs, sourceId: String, mediaId: String): Result<List<RelatedDto>> = runCatching {
        val item = media(sourceId, mediaId).getOrThrow()
        discovery(user, prefs).similar(item).map(dtos::related)
    }

    suspend fun alternatives(user: User, prefs: UserPrefs, sourceId: String, mediaId: String): Result<List<AlternativeDto>> = runCatching {
        // The same title with the same sources on gives the same answer: opening it again is instant.
        val k = key("alternatives", sourceId, mediaId, prefs.cacheKey())
        alternativesCache.get(k)?.let { return@runCatching it }
        val item = media(sourceId, mediaId).getOrThrow()
        val found = java.util.Collections.synchronizedList(mutableListOf<com.nelson.yomukai.data.discovery.Alternative>())
        withTimeoutOrNull(ALTERNATIVES_TIMEOUT_MS) { discovery(user, prefs).alternatives(item) { found += it } }
        synchronized(found) { found.toList() }
            .filter { health.get(it.item) != ChapterHealth.EMPTY && it.chapterCount != 0 }
            .sortedWith(compareBy({ it.confidence.ordinal }, { health.get(it.item).ordinal }, { -(it.chapterCount ?: -1) }))
            .onEach { remember(listOf(it.item)) }
            .map(dtos::alternative)
            .also { if (it.isNotEmpty()) alternativesCache.put(k, it) }
    }

    suspend fun locate(user: User, prefs: UserPrefs, related: RelatedTitle): Result<List<AlternativeDto>> = runCatching {
        discovery(user, prefs).locate(related).onEach { remember(listOf(it.item)) }.map(dtos::alternative)
    }

    /**
     * Opening a recommended title without asking which source: answers as soon as one source is confirmed to have the
     * same work ([LocateFirst.open]), while the search goes on in the background and fills the cache for next time.
     * Only when no source is sure does it wait for everything and return the possible matches to choose from.
     */
    suspend fun locateFirst(user: User, prefs: UserPrefs, related: RelatedTitle): Result<LocateFirst> = runCatching {
        val first = kotlinx.coroutines.CompletableDeferred<AlternativeDto?>()
        val repo = discovery(user, prefs)
        val all = probeScope.async {
            repo.locate(related) { hit ->
                if (hit.confidence == com.nelson.yomukai.data.discovery.MatchConfidence.SAME_WORK && !first.isCompleted) {
                    remember(listOf(hit.item))
                    first.complete(dtos.alternative(hit))
                }
            }.onEach { remember(listOf(it.item)) }.map(dtos::alternative)
        }
        // Whatever happens to the search (done, failed, cancelled), the request does not wait forever.
        all.invokeOnCompletion { first.complete(null) }
        kotlinx.coroutines.withTimeoutOrNull(LOCATE_FIRST_MS) { first.await() }?.let { LocateFirst(open = it, options = listOf(it)) } ?: LocateFirst(
            open = null,
            options = if (all.isCompleted) runCatching { all.await() }.getOrDefault(emptyList()) else emptyList()
        )
    }

    data class LocateFirst(val open: AlternativeDto?, val options: List<AlternativeDto>)

    suspend fun random(user: User, prefs: UserPrefs, kind: RandomKind, genre: String?): Result<Pair<RelatedDto, List<AlternativeDto>>?> = runCatching {
        discovery(user, prefs).randomAvailable(kind, genre)?.let { (pick, places) ->
            places.forEach { remember(listOf(it.item)) }
            dtos.related(pick) to places.filter { health.get(it.item) != ChapterHealth.EMPTY }.sortedBy { health.get(it.item).ordinal }.map(dtos::alternative)
        }
    }

    suspend fun forYou(user: User, prefs: UserPrefs, seenTitles: List<MediaItem>): Result<List<RelatedDto>> = runCatching {
        discovery(user, prefs).forYou(seenTitles).map(dtos::related)
    }

    /** A recommendation sent back by the browser, turned into what discovery understands. */
    fun relatedFrom(dto: RelatedDto): RelatedTitle = RelatedTitle(
        dto.title, dto.names, "", runCatching { MediaType.valueOf(dto.type) }.getOrDefault(MediaType.MANGA), dto.genres,
        item = dto.item?.let { m -> seen.get(key(m.sourceId, m.id)) },
        relation = dto.relation, format = dto.format, tags = dto.tags, anilistId = dto.anilistId
    )

    fun seenItem(sourceId: String, mediaId: String): MediaItem? = seen.get(key(sourceId, mediaId)) ?: details.get(key(sourceId, mediaId))

    private companion object {
        const val MINUTE = 60_000L
        const val LOCATE_FIRST_MS = 45_000L
        const val HOUR = 60 * MINUTE

        /** A source slower than this is left out of the search (marked as such): the rest are not held up. */
        const val SOURCE_TIMEOUT_MS = 10_000L
        private const val SEARCH_SKIP_MS = 10 * 60_000L
        private const val SEARCH_SKIP_AFTER = 3
        private const val MAX_FAILED_QUERY = 100
        const val ALTERNATIVES_TIMEOUT_MS = 18_000L
        const val MAX_GROUPS = 80

        /** How old a list or a chapter list may be and still be shown at once while a fresh one comes. */
        const val LIST_STALE_MS = 6 * HOUR
        const val CHAPTERS_STALE_MS = 2 * HOUR
        const val PROBE_PER_LIST = 24
        const val PROBE_PARALLEL = 4
        const val PROBE_TIMEOUT_MS = 20_000L
    }
}

/** [text] without lone UTF-16 surrogates (half characters), which make UTF-8 encoding fail. */
fun withoutBrokenSurrogates(text: String): String {
    if (text.none { it.isSurrogate() }) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { out.append(c).append(text[i + 1]); i++ }
            c.isSurrogate() -> Unit
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}
