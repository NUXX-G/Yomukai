package com.nelson.yomukai.ui.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.discovery.Alternative
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.data.local.CategoryEntity
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.DownloadEntity
import com.nelson.yomukai.data.local.DownloadStatus
import com.nelson.yomukai.data.local.MediaEntity
import com.nelson.yomukai.data.local.ReadingStatus
import com.nelson.yomukai.data.metadata.Abandonment
import com.nelson.yomukai.data.metadata.ChapterIntegrity
import com.nelson.yomukai.data.metadata.ChapterIssue
import com.nelson.yomukai.data.metadata.TitleMetadata
import com.nelson.yomukai.data.repository.toDomain
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.PublicationStatus
import com.nelson.yomukai.domain.model.VideoSource
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class DetailsUiState(
    val media: MediaEntity? = null,
    val sourceName: String = "",
    val isNovel: Boolean = false,
    val isAnime: Boolean = false,
    val chapters: List<ChapterEntity> = emptyList(),
    val downloads: Map<String, DownloadEntity> = emptyMap(),
    val sortDescending: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val selectedCategoryIds: Set<Long> = emptySet()
) {
    val readingStatus: ReadingStatus get() = ReadingStatus.parse(media?.readingStatus)

    val displayedChapters: List<ChapterEntity> get() = if (sortDescending) chapters.asReversed() else chapters

    /** Chapter the primary button should open, plus whether the user has started reading. */
    val continueTarget: Pair<ChapterEntity, Boolean>? get() {
        if (chapters.isEmpty()) return null
        // Read only in another source or language of the work (no date here): after the furthest one (F1.5).
        val last = chapters.filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt }
            ?: chapters.filter { it.read }.maxByOrNull { it.sortIndex }
            ?: return chapters.first() to false
        if (!last.read) return last to true
        val next = chapters.firstOrNull { it.sortIndex > last.sortIndex && !it.read }
        return (next ?: last) to true
    }
}

/** What the chapter list reveals: numbering problems and whether an ongoing title went quiet. */
@Immutable
data class ChapterInsights(
    val issues: List<ChapterIssue> = emptyList(),
    /** Gaps by the id of the chapter right before them. */
    val gapAfter: Map<String, ChapterIssue.Gap> = emptyMap(),
    val abandoned: Boolean = false,
    /** Date of the newest chapter, when a date could be read. */
    val lastUpdate: Long? = null
)

@Immutable
data class SimilarState(val requested: Boolean = false, val loading: Boolean = false, val items: List<RelatedTitle> = emptyList())

@Immutable
data class AlternativesState(
    val open: Boolean = false,
    val searching: Boolean = false,
    val done: Boolean = false,
    val items: List<Alternative> = emptyList()
)

@Immutable
data class LocateState(
    val title: String,
    val searching: Boolean,
    val options: List<Alternative> = emptyList(),
    /** The first place confirmed to be the same work: it opens at once, nothing to choose. */
    val openNow: com.nelson.yomukai.domain.model.MediaItem? = null
)

/**
 * Finds where [related] can be read and opens the first confirmed place as soon as it appears (the title's page
 * then gathers the other sources by itself). Only when nothing is confirmed are the possible matches offered.
 */
internal suspend fun locateAndOpen(
    container: com.nelson.yomukai.AppContainer, related: RelatedTitle, state: MutableStateFlow<LocateState?>
) = kotlinx.coroutines.coroutineScope {
    val opened = java.util.concurrent.atomic.AtomicBoolean(false)
    // With a server connected, it searches too (its caches are shared by everyone, so it usually knows already):
    // whichever finds the work first opens it.
    val remote = if (container.serverSync.serverUrl != null) launch {
        val item = runCatching { serverLocateFirst(container, related) }.getOrNull() ?: return@launch
        if (opened.compareAndSet(false, true)) {
            container.libraryRepository.cacheMedia(item)
            state.value = LocateState(related.title, searching = true, openNow = item)
        }
    } else null
    val options = runCatching {
        container.discovery.locate(related) { hit ->
            if (hit.confidence == com.nelson.yomukai.data.discovery.MatchConfidence.SAME_WORK && opened.compareAndSet(false, true)) {
                kotlinx.coroutines.runBlocking { container.libraryRepository.cacheMedia(hit.item) }
                state.value = LocateState(related.title, searching = true, options = listOf(hit), openNow = hit.item)
            }
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        emptyList()
    }
    if (opened.get()) remote?.cancel() else remote?.join()
    if (!opened.get()) state.value = LocateState(related.title, searching = false, options = options)
}

/** The first place the connected server finds [related] as the same work, if this app has that source too. */
private suspend fun serverLocateFirst(container: com.nelson.yomukai.AppContainer, related: RelatedTitle): MediaItem? {
    // The server's works index usually has it already (built ahead): no searching at all.
    runCatching { serverIndexFirst(container, related) }.getOrNull()?.let { return it }
    val body = com.google.gson.JsonObject().apply {
        addProperty("title", related.title)
        add("names", com.google.gson.JsonArray().apply { related.names.forEach(::add) })
        addProperty("cover", related.coverUrl)
        addProperty("type", related.type.name)
        add("genres", com.google.gson.JsonArray().apply { related.genres.forEach(::add) })
        addProperty("format", related.format)
        add("tags", com.google.gson.JsonArray())
        related.anilistId?.let { addProperty("anilistId", it) }
    }
    val open = container.serverSync.api("POST", "/api/locate/first", body)?.asJsonObject?.get("open")
        ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val m = open.get("item")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun str(k: String) = m.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    fun list(k: String) = m.get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
    val sourceId = str("sourceId")
    if (container.sourceManager.get(sourceId) == null || str("id").isBlank()) return null
    return MediaItem(
        id = str("id"), title = str("title"), coverUrl = str("cover"),
        type = runCatching { com.nelson.yomukai.domain.model.MediaType.valueOf(str("type")) }.getOrDefault(related.type),
        status = str("status"), description = str("description"), sourceId = sourceId,
        author = str("author"), genres = list("genres"), altTitles = list("altTitles")
    )
}

/** Where the server's works index has AniList work [anilistId] in the app's language, in a source this app has. */
private suspend fun serverIndexFirst(container: com.nelson.yomukai.AppContainer, related: RelatedTitle): MediaItem? {
    val type = related.type
    val lang = com.nelson.yomukai.i18n.I18n.lang
    // By AniList id or by any of its names (normalised as the index groups them).
    val ask = com.google.gson.JsonObject().apply {
        addProperty("title", related.title)
        add("names", com.google.gson.JsonArray().apply { related.names.forEach(::add) })
        addProperty("type", type.name)
        related.anilistId?.let { addProperty("anilistId", it) }
    }
    val key = container.serverSync.api("POST", "/api/obras/encontrar?lang=$lang", ask)?.asJsonObject?.get("key")
        ?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    val ficha = container.serverSync.api("GET", "/api/obras/ficha/${java.net.URLEncoder.encode(key, Charsets.UTF_8).replace("+", "%20")}?lang=$lang&light=1")
        ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val work = ficha.get("work")?.takeIf { it.isJsonObject }?.asJsonObject
    fun str(o: com.google.gson.JsonObject?, k: String) = o?.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val place = ficha.get("sources")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
        ?.firstOrNull { container.sourceManager.get(str(it, "sourceId")) != null && str(it, "mediaId").isNotBlank() } ?: return null
    return MediaItem(
        id = str(place, "mediaId"), title = str(work, "title"), coverUrl = str(work, "cover").takeIf { it.startsWith("http") }.orEmpty(),
        type = runCatching { com.nelson.yomukai.domain.model.MediaType.valueOf(str(work, "type")) }.getOrDefault(type),
        status = "", description = "", sourceId = str(place, "sourceId"), author = "", genres = emptyList(), altTitles = emptyList()
    )
}

private const val METADATA_ATTEMPTS = 2
private const val MAX_SERVER_SISTERS = 6
private const val METADATA_RETRY_MS = 6_000L

class DetailsViewModel(handle: SavedStateHandle, private val container: AppContainer) : ViewModel() {
    val sourceId: String = checkNotNull(handle[Routes.ARG_SOURCE])
    val mediaId: String = checkNotNull(handle[Routes.ARG_MEDIA])

    private val repo = container.libraryRepository
    private val source = container.sourceManager.get(sourceId)
    private val ui = MutableStateFlow(Triple(true, false, null as String?)) // sortDesc, refreshing, error

    private val categoryState = combine(repo.observeCategories(), repo.observeCategoriesFor(sourceId, mediaId)) { all, selected ->
        all to selected.toSet()
    }

    /** Chapter numbers read in another source or language of the same work (shown as read here, F1.5). */
    private val readElsewhere = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<DetailsUiState> = combine(
        repo.observeMedia(sourceId, mediaId),
        repo.observeChapters(sourceId, mediaId),
        container.downloadManager.observeForMedia(sourceId, mediaId),
        ui,
        categoryState
    ) { media, chapters, downloads, (sortDesc, refreshing, error), (categories, selected) ->
        DetailsUiState(
            media = media,
            sourceName = source?.name ?: sourceId,
            isNovel = source is NovelSource,
            isAnime = source is AnimeSource,
            chapters = chapters,
            downloads = downloads.associateBy { it.chapterId },
            sortDescending = sortDesc,
            isRefreshing = refreshing,
            error = error,
            categories = categories,
            selectedCategoryIds = selected
        )
    }.combine(readElsewhere) { st, elsewhere ->
        if (elsewhere.isEmpty()) st
        else st.copy(chapters = st.chapters.map { c -> if (!c.read && com.nelson.yomukai.data.work.numberKey(c.number) in elsewhere) c.copy(read = true) else c })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState(sourceName = source?.name.orEmpty()))

    private val _metadata = MutableStateFlow<TitleMetadata?>(null)

    /** Score, official synopsis, normalized genres and related works; null while loading. */
    val metadata: StateFlow<TitleMetadata?> = _metadata

    private val _fillers = MutableStateFlow<Map<Int, com.nelson.yomukai.data.work.EpisodeKind>>(emptyMap())

    /** Anime: episode number -> filler or mixed (AnimeFillerList); empty when unknown. */
    val fillers: StateFlow<Map<Int, com.nelson.yomukai.data.work.EpisodeKind>> = _fillers

    private fun loadFillers(title: String) {
        val url = com.nelson.yomukai.data.work.FillerGuide.url(title) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val html = runCatching { container.network.getString(url, emptyMap(), cloudflareFallback = false) }.getOrNull() ?: return@launch
            _fillers.value = com.nelson.yomukai.data.work.FillerGuide.parse(html).filterValues { it != com.nelson.yomukai.data.work.EpisodeKind.CANON }
        }
    }

    private val _metadataLoading = MutableStateFlow(true)
    val metadataLoading: StateFlow<Boolean> = _metadataLoading

    /** Worked out off the main thread whenever the chapters, the title or its metadata change. */
    val insights: StateFlow<ChapterInsights> = combine(
        repo.observeChapters(sourceId, mediaId),
        repo.observeMedia(sourceId, mediaId),
        _metadata
    ) { chapters, media, metadata ->
        val issues = ChapterIntegrity.check(chapters.map { it.number })
        val gaps = issues.filterIsInstance<ChapterIssue.Gap>()
            .mapNotNull { gap -> chapters.getOrNull(gap.afterIndex)?.let { it.chapterId to gap } }
            .toMap()
        val statuses = listOfNotNull(PublicationStatus.parse(media?.status.orEmpty()), metadata?.officialStatus)
        // Ongoing for someone and finished for no one: a source or AniList saying it ended settles it.
        val ongoing = PublicationStatus.ONGOING in statuses &&
            statuses.none { it == PublicationStatus.COMPLETED || it == PublicationStatus.CANCELLED }
        val dates = chapters.map { it.dateUploaded }
        ChapterInsights(
            issues = issues,
            gapAfter = gaps,
            abandoned = Abandonment.isLikelyAbandoned(ongoing, dates),
            lastUpdate = Abandonment.latest(dates)
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterInsights())

    init {
        refresh()
        loadMetadata()
        loadWork()
        loadLanguages()
    }

    /** The work in the index and its languages (with a server): chips to switch edition, F1.5. */
    data class WorkLanguages(val key: String, val lang: String, val langs: List<String>)

    private val _languages = MutableStateFlow<WorkLanguages?>(null)
    val languages: StateFlow<WorkLanguages?> = _languages

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private fun loadLanguages() {
        if (container.serverSync.serverUrl == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val o = runCatching { container.serverSync.api("GET", "/api/obras/de?s=${enc(sourceId)}&id=${enc(mediaId)}") }
                .onFailure { if (it is CancellationException) throw it }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return@launch
            val key = o.get("key")?.takeIf { it.isJsonPrimitive }?.asString ?: return@launch
            val langs = o.getAsJsonArray("langs")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            val lang = o.get("lang")?.takeIf { it.isJsonPrimitive }?.asString ?: source?.language?.lowercase().orEmpty()
            if (langs.size > 1) _languages.value = WorkLanguages(key, lang, langs)
        }
    }

    /** The page of this work to open in [lang] (the one you already follow there first), as the server's index says. */
    suspend fun pageIn(lang: String): MediaItem? = withContext(Dispatchers.IO) {
        val key = _languages.value?.key ?: return@withContext null
        val o = runCatching { container.serverSync.api("GET", "/api/obras/ficha/${enc(key)}?lang=${enc(lang)}&light=1") }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return@withContext null
        val first = o.getAsJsonArray("sources")?.firstOrNull { it.isJsonObject && container.sourceManager.get(it.asJsonObject.get("sourceId")?.asString.orEmpty()) != null }
            ?.asJsonObject ?: return@withContext null
        val s = first.get("sourceId").asString
        val m = first.get("mediaId").asString
        val type = container.sourceManager.get(s)?.supportedTypes?.firstOrNull() ?: com.nelson.yomukai.domain.model.MediaType.MANGA
        repo.getMedia(s, m)?.toDomain() ?: MediaItem(m, mediaItem()?.title.orEmpty(), "", type, "", "", s)
    }

    private val _extras = MutableStateFlow<List<ChapterEntity>>(emptyList())

    /**
     * The same work in the user's other sources: chapters or episodes this source lacks, so the page is one list
     * for the work (one One Piece, not several) and nothing is lost if this source fails or stops.
     */
    val extraChapters: StateFlow<List<ChapterEntity>> = _extras

    /** Names of the sources those extra chapters come from, by source id. */
    val sourceNames: Map<String, String> get() = container.sourceManager.all.associate { it.id to it.name }

    private fun loadWork() {
        viewModelScope.launch(Dispatchers.IO) {
            val media = mediaItem() ?: repo.refreshDetails(sourceId, mediaId).let { mediaItem() } ?: return@launch
            val sisters = java.util.concurrent.CopyOnWriteArraySet<Pair<String, String>>()
            if (container.serverSync.serverUrl != null) launch { serverSisters(sisters) }
            runCatching {
                container.discovery.alternativesCached(media) { found ->
                    // The "Otras fuentes" sheet opens with what is already known.
                    _alternatives.update { st -> st.copy(items = (st.items + found).distinctBy { it.item.sourceId }.sortedWith(compareBy({ it.confidence.ordinal }, { -(it.chapterCount ?: -1) }))) }
                    if (found.confidence == com.nelson.yomukai.data.discovery.MatchConfidence.SAME_WORK) {
                        viewModelScope.launch(Dispatchers.IO) {
                            repo.cacheMedia(found.item)
                            if (repo.getChapters(found.item.sourceId, found.item.id).isEmpty()) repo.refreshChapters(found.item.sourceId, found.item.id)
                            sisters += found.item.sourceId to found.item.id
                            mergeSisters(sisters.toList())
                        }
                    }
                }
            }.onFailure { if (it is CancellationException) throw it }
            _alternatives.update { it.copy(done = true, searching = false) }
        }
    }

    /**
     * With a server connected: it already knows which of its sources are this same work (shared by everyone, so the
     * slow search across every source is usually done), and only their chapter lists are loaded here.
     */
    private suspend fun serverSisters(sisters: MutableSet<Pair<String, String>>) {
        fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)
        val whole = runCatching { container.serverSync.api("GET", "/api/works/whole?s=${enc(sourceId)}&id=${enc(mediaId)}") }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()?.asJsonObject ?: return
        val chapters = whole.getAsJsonObject("chapters")?.getAsJsonArray("chapters") ?: return
        // What the server knows was read in any page of the work, in any language.
        readElsewhere.update { it + chapters.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { o -> o.get("read")?.asBoolean == true }?.get("key")?.asString } }
        val places = chapters.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val s = o.get("sourceId")?.asString ?: return@mapNotNull null
            val m = o.get("mediaId")?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null } ?: return@mapNotNull null
            (s to m).takeIf { s != sourceId && container.sourceManager.get(s) != null }
        }.distinct().take(MAX_SERVER_SISTERS)
        for ((s, m) in places) {
            if ((s to m) in sisters) continue
            runCatching {
                if (repo.getMedia(s, m) == null) repo.refreshDetails(s, m)
                if (repo.getChapters(s, m).isEmpty()) repo.refreshChapters(s, m)
            }.onFailure { if (it is CancellationException) throw it }
            if (repo.getChapters(s, m).isNotEmpty()) { sisters += s to m; mergeSisters(sisters.toList()) }
        }
    }

    private suspend fun mergeSisters(sisters: List<Pair<String, String>>) {
        // Every page of the work knows the others (the player's «Siguiente» continues in a sister source).
        val pages = (listOf(sourceId to mediaId) + sisters).distinct()
        pages.forEach { page -> container.workSisters["${page.first}|${page.second}"] = pages - page }
        fun key(n: Float) = com.nelson.yomukai.data.work.numberKey(n)
        val own = repo.getChapters(sourceId, mediaId).filter { it.number > 0f }.map { key(it.number) }.toSet()
        // Sources in the language of this one first: a missing chapter comes in the language the user reads.
        val language = source?.language
        val ordered = sisters.sortedBy { if (container.sourceManager.get(it.first)?.language == language) 0 else 1 }
        readElsewhere.update { it + ordered.flatMap { (s, m) -> repo.getChapters(s, m) }.filter { it.read && it.number > 0f }.map { key(it.number) } }
        _extras.value = ordered.flatMap { (s, m) -> repo.getChapters(s, m) }
            .filter { it.number > 0f && key(it.number) !in own }
            .distinctBy { key(it.number) }
    }

    private fun loadMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            val media = mediaItem() ?: run { _metadataLoading.value = false; return@launch }
            if (source is AnimeSource) loadFillers(media.title)
            // AniList may be busy with the home screen's recommendations right after start: what
            // came back empty is asked again a little later, and the genres shown meanwhile stay.
            repeat(METADATA_ATTEMPTS) { attempt ->
                val found = runCatching { container.metadata.load(media) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    null
                }
                if (found != null) _metadata.value = found
                _metadataLoading.value = false
                if (found?.fromNetwork == true) return@launch
                if (attempt < METADATA_ATTEMPTS - 1) kotlinx.coroutines.delay(METADATA_RETRY_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            ui.value = ui.value.copy(second = true, third = null)
            val details = async { repo.refreshDetails(sourceId, mediaId) }
            val chapters = async { repo.refreshChapters(sourceId, mediaId) }
            val errors = listOfNotNull(details.await().exceptionOrNull(), chapters.await().exceptionOrNull())
            val hasCache = repo.getChapters(sourceId, mediaId).isNotEmpty()
            ui.value = ui.value.copy(
                second = false,
                third = errors.firstOrNull()?.let { e ->
                    val reason = e.message ?: tr("Error de conexión")
                    if (hasCache) "Mostrando datos guardados ($reason)" else reason
                }
            )
        }
    }

    fun toggleSort() {
        ui.value = ui.value.copy(first = !ui.value.first)
    }

    fun toggleLibrary() {
        val media = state.value.media ?: return
        viewModelScope.launch { repo.setInLibrary(sourceId, mediaId, !media.inLibrary) }
    }

    fun setReadingStatus(status: ReadingStatus) {
        viewModelScope.launch { repo.setReadingStatus(sourceId, mediaId, status) }
    }

    fun setNotifyUpdates(notify: Boolean) {
        viewModelScope.launch { repo.setNotifyUpdates(sourceId, mediaId, notify) }
    }

    fun saveCategories(ids: Set<Long>) {
        viewModelScope.launch { repo.setCategories(sourceId, mediaId, ids) }
    }

    fun createCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repo.createCategory(name)
            repo.setCategories(sourceId, mediaId, state.value.selectedCategoryIds + id)
        }
    }

    fun setRead(chapter: ChapterEntity, read: Boolean) {
        viewModelScope.launch { repo.setChaptersRead(sourceId, listOf(chapter.chapterId), read) }
    }

    fun markPreviousRead(chapter: ChapterEntity) {
        val ids = state.value.chapters.filter { it.sortIndex <= chapter.sortIndex }.map { it.chapterId }
        viewModelScope.launch { repo.setChaptersRead(sourceId, ids, true) }
    }

    fun unmarkPrevious(chapter: ChapterEntity) {
        val ids = state.value.chapters.filter { it.sortIndex <= chapter.sortIndex }.map { it.chapterId }
        viewModelScope.launch { repo.setChaptersRead(sourceId, ids, false) }
    }

    fun download(chapters: List<ChapterEntity>) {
        val media = state.value.media ?: return
        viewModelScope.launch { container.downloadManager.enqueue(media, chapters) }
    }

    /** Chapters without a usable copy: never queued, or failed and worth another try. */
    private fun notDownloaded(list: List<ChapterEntity>) = list.filter {
        val status = state.value.downloads[it.chapterId]?.status
        status == null || status == DownloadStatus.FAILED
    }

    fun downloadUnread() = download(notDownloaded(state.value.chapters.filter { !it.read }))

    /** Every chapter/episode of the title that is not already queued or downloaded. */
    fun downloadAll() = download(notDownloaded(state.value.chapters))

    /** Chapters whose number lies in [from]..[to] (inclusive, either order). */
    fun downloadRange(from: Float, to: Float) {
        val low = minOf(from, to)
        val high = maxOf(from, to)
        download(notDownloaded(state.value.chapters.filter { it.number in low..high }))
    }

    fun deleteDownload(chapter: ChapterEntity) {
        viewModelScope.launch { container.downloadManager.delete(sourceId, chapter.chapterId) }
    }

    private val _similar = MutableStateFlow(SimilarState())
    val similar: StateFlow<SimilarState> = _similar

    private val _alternatives = MutableStateFlow(AlternativesState())
    val alternatives: StateFlow<AlternativesState> = _alternatives

    private val _locate = MutableStateFlow<LocateState?>(null)
    val locate: StateFlow<LocateState?> = _locate

    private var locateJob: Job? = null
    private var alternativesJob: Job? = null

    private suspend fun mediaItem() = repo.getMedia(sourceId, mediaId)?.toDomain()

    fun loadSimilar() {
        if (_similar.value.requested) return
        _similar.value = SimilarState(requested = true, loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val items = mediaItem()?.let { media ->
                runCatching { container.discovery.similar(media) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    emptyList()
                }
            }.orEmpty()
            _similar.value = SimilarState(requested = true, items = items)
        }
    }

    /** Looks for this title in the other sources; repeated opens reuse what was already found. */
    fun openAlternatives() {
        val current = _alternatives.value
        _alternatives.value = current.copy(open = true)
        if (current.searching || current.done) return
        _alternatives.value = AlternativesState(open = true, searching = true)
        alternativesJob = viewModelScope.launch(Dispatchers.IO) {
            val media = mediaItem()
            if (media != null) {
                runCatching {
                    container.discovery.alternatives(media) { found ->
                        _alternatives.update { state ->
                            state.copy(items = (state.items + found).sortedWith(compareBy({ it.confidence.ordinal }, { -(it.chapterCount ?: -1) })))
                        }
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
            _alternatives.update { it.copy(searching = false, done = true) }
        }
    }

    fun closeAlternatives() = _alternatives.update { it.copy(open = false) }

    /** Finds where a recommended title can be read. */
    fun locate(related: RelatedTitle) {
        locateJob?.cancel()
        _locate.value = LocateState(related.title, searching = true)
        locateJob = viewModelScope.launch(Dispatchers.IO) { locateAndOpen(container, related, _locate) }
    }

    fun closeLocate() {
        locateJob?.cancel()
        _locate.value = null
    }

    /** Servers of the episode whose failed download is being retried; null while loading. */
    private val _retryServers = MutableStateFlow<Result<List<VideoSource>>?>(null)
    val retryServers: StateFlow<Result<List<VideoSource>>?> = _retryServers

    fun loadRetryServers(chapter: ChapterEntity) {
        val anime = source as? AnimeSource ?: return
        _retryServers.value = null
        viewModelScope.launch(Dispatchers.IO) { _retryServers.value = anime.fetchVideoSources(chapter.url) }
    }

    /** Retries a failed download; for an episode, [server] is tried first (null = every server in order). */
    fun retryDownload(chapter: ChapterEntity, server: String? = null) {
        viewModelScope.launch { container.downloadManager.retry(sourceId, chapter.chapterId, server) }
    }
}
