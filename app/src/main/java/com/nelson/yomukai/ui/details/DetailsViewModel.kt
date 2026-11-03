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
import com.nelson.yomukai.data.repository.toDomain
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.domain.model.VideoSource
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
import com.nelson.yomukai.data.metadata.Abandonment
import com.nelson.yomukai.data.metadata.ChapterIntegrity
import com.nelson.yomukai.data.metadata.ChapterIssue
import com.nelson.yomukai.data.metadata.TitleMetadata
import com.nelson.yomukai.domain.model.PublicationStatus
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        val last = chapters.filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt } ?: return chapters.first() to false
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
data class LocateState(val title: String, val searching: Boolean, val options: List<Alternative> = emptyList())

private const val METADATA_ATTEMPTS = 3
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState(sourceName = source?.name.orEmpty()))

    private val _metadata = MutableStateFlow<TitleMetadata?>(null)

    /** Score, official synopsis, normalized genres and related works; null while loading. */
    val metadata: StateFlow<TitleMetadata?> = _metadata

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
    }

    private fun loadMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            val media = mediaItem() ?: run { _metadataLoading.value = false; return@launch }
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
                    val reason = e.message ?: "Error de conexión"
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
        locateJob = viewModelScope.launch(Dispatchers.IO) {
            val options = runCatching { container.discovery.locate(related) }.getOrElse { e ->
                if (e is CancellationException) throw e
                emptyList()
            }
            _locate.value = LocateState(related.title, searching = false, options = options)
        }
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
