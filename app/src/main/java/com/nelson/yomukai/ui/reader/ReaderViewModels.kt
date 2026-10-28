package com.nelson.yomukai.ui.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.local.BookmarkEntity
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.repository.PageData
import com.nelson.yomukai.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One loaded chapter of the continuous reading list. */
@Immutable
data class Segment<T>(val chapter: ChapterEntity, val content: T, val initialPosition: Int)

@Immutable
data class ReaderUiState<T>(
    val mediaTitle: String = "",
    /** Chapters currently loaded, in reading order. The first one is where the session (re)started. */
    val segments: List<Segment<T>> = emptyList(),
    /** Chapter currently on screen; drives the toolbar, progress and history. */
    val chapter: ChapterEntity? = null,
    val previous: ChapterEntity? = null,
    val next: ChapterEntity? = null,
    /** Loading the first chapter after a (re)start. */
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Appending the following chapter in the background. */
    val isLoadingNext: Boolean = false,
    val nextError: String? = null,
    /** Bumped on every restart so scroll state is recreated. */
    val generation: Int = 0
)

/**
 * Shared chapter loading / navigation / progress logic for both readers. Chapters are appended
 * as the user nears the end of the last one (continuous reading); jumping from the index or the
 * previous/next buttons restarts the list at the chosen chapter.
 */
abstract class BaseReaderViewModel<T>(handle: SavedStateHandle, protected val container: AppContainer) : ViewModel() {
    protected val sourceId: String = checkNotNull(handle[Routes.ARG_SOURCE])
    protected val mediaId: String = checkNotNull(handle[Routes.ARG_MEDIA])
    private val startChapterId: String = checkNotNull(handle[Routes.ARG_CHAPTER])

    protected val repo = container.libraryRepository
    private var chapters: List<ChapterEntity> = emptyList()
    private var loadJob: Job? = null
    private var nextJob: Job? = null

    protected val _state = MutableStateFlow(ReaderUiState<T>())
    val state: StateFlow<ReaderUiState<T>> = _state.asStateFlow()

    /** Live chapter list for the index (read marks update as the user advances). */
    val chapterList: StateFlow<List<ChapterEntity>> = repo.observeChapters(sourceId, mediaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> = repo.observeBookmarks(sourceId, mediaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Toggles a bookmark at [position] (page or paragraph) of a loaded chapter. */
    fun toggleBookmark(chapterId: String, position: Int, label: String = "") {
        val chapter = _state.value.segments.firstOrNull { it.chapter.chapterId == chapterId }?.chapter ?: return
        viewModelScope.launch(Dispatchers.IO) { repo.toggleBookmark(chapter, position, label) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteBookmark(id) }
    }

    protected abstract suspend fun loadContent(chapter: ChapterEntity): Result<T>
    protected abstract fun resumePosition(chapter: ChapterEntity, content: T): Int

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val title = repo.getMedia(sourceId, mediaId)?.title.orEmpty()
            chapters = repo.getChapters(sourceId, mediaId)
            if (chapters.none { it.chapterId == startChapterId }) {
                repo.refreshChapters(sourceId, mediaId)
                chapters = repo.getChapters(sourceId, mediaId)
            }
            _state.update { it.copy(mediaTitle = title) }
            openChapter(startChapterId)
        }
    }

    fun retry() = state.value.chapter?.let { openChapter(it.chapterId) }
    fun goToNext() = state.value.next?.let { openChapter(it.chapterId) }
    fun goToPrevious() = state.value.previous?.let { openChapter(it.chapterId) }
    fun jumpToChapter(chapterId: String, position: Int? = null) = openChapter(chapterId, position)

    /** Restarts the continuous list at [chapterId]. */
    private fun openChapter(chapterId: String, positionOverride: Int? = null) {
        loadJob?.cancel()
        nextJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // Re-read so resume positions reflect progress saved moments ago.
            val chapter = repo.getChapter(sourceId, chapterId) ?: chapters.firstOrNull { it.chapterId == chapterId }
            if (chapter == null) {
                _state.update { it.copy(isLoading = false, error = "Capítulo no encontrado") }
                return@launch
            }
            _state.update {
                it.copy(
                    segments = emptyList(),
                    chapter = chapter,
                    previous = chapters.getOrNull(chapter.sortIndex - 1),
                    next = chapters.getOrNull(chapter.sortIndex + 1),
                    isLoading = true,
                    error = null,
                    isLoadingNext = false,
                    nextError = null
                )
            }
            loadContent(chapter)
                .onSuccess { content ->
                    _state.update {
                        it.copy(
                            segments = listOf(Segment(chapter, content, positionOverride ?: resumePosition(chapter, content))),
                            isLoading = false,
                            generation = it.generation + 1
                        )
                    }
                    repo.saveProgress(chapter, chapter.lastPageRead, chapter.totalPages, chapter.progress, markRead = false)
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message ?: "No se pudo cargar el capítulo") }
                }
        }
    }

    /** Appends the chapter after the last loaded one; called by the UI when the end is near. */
    fun loadNext() {
        val current = _state.value
        val last = current.segments.lastOrNull()?.chapter ?: return
        if (current.isLoadingNext) return
        val target = chapters.getOrNull(last.sortIndex + 1) ?: return
        if (current.segments.any { it.chapter.chapterId == target.chapterId }) return
        _state.update { it.copy(isLoadingNext = true, nextError = null) }
        nextJob = viewModelScope.launch(Dispatchers.IO) {
            val fresh = repo.getChapter(sourceId, target.chapterId) ?: target
            loadContent(fresh)
                .onSuccess { content ->
                    _state.update {
                        it.copy(
                            segments = it.segments + Segment(fresh, content, resumePosition(fresh, content)),
                            isLoadingNext = false
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoadingNext = false, nextError = e.message ?: "No se pudo cargar el siguiente capítulo") }
                }
        }
    }

    /**
     * The visible chapter changed (user scrolled into the next one, or back). Updates the toolbar
     * and refreshes the history entry; reading progress itself is saved by the readers.
     */
    fun onChapterVisible(chapterId: String) {
        val current = _state.value
        if (current.chapter?.chapterId == chapterId) return
        val segment = current.segments.firstOrNull { it.chapter.chapterId == chapterId } ?: return
        val chapter = segment.chapter
        _state.update {
            it.copy(
                chapter = chapter,
                previous = chapters.getOrNull(chapter.sortIndex - 1),
                next = chapters.getOrNull(chapter.sortIndex + 1)
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val stored = repo.getChapter(sourceId, chapterId) ?: chapter
            repo.saveProgress(stored, stored.lastPageRead, stored.totalPages, stored.progress, markRead = stored.read)
        }
    }

    protected fun persist(chapterId: String, page: Int, total: Int, progress: Float, read: Boolean) {
        val chapter = _state.value.segments.firstOrNull { it.chapter.chapterId == chapterId }?.chapter ?: return
        viewModelScope.launch(Dispatchers.IO) { repo.saveProgress(chapter, page, total, progress, read) }
    }
}

class MangaReaderViewModel(handle: SavedStateHandle, container: AppContainer) :
    BaseReaderViewModel<List<PageData>>(handle, container) {

    val settings = container.preferences.manga
    fun updateSettings(transform: (com.nelson.yomukai.data.prefs.MangaReaderSettings) -> com.nelson.yomukai.data.prefs.MangaReaderSettings) =
        container.preferences.updateManga(transform)

    override suspend fun loadContent(chapter: ChapterEntity) = container.contentRepository.mangaPages(chapter)

    override fun resumePosition(chapter: ChapterEntity, content: List<PageData>) =
        if (!chapter.read && chapter.lastPageRead in content.indices) chapter.lastPageRead else 0

    fun onPageChanged(chapterId: String, page: Int, total: Int) {
        if (total <= 0) return
        persist(chapterId, page, total, (page + 1f) / total, read = page >= total - 1)
    }
}

class NovelReaderViewModel(handle: SavedStateHandle, container: AppContainer) :
    BaseReaderViewModel<List<String>>(handle, container) {

    val settings = container.preferences.novel

    /** Source language ("ES", "EN"...) so text-to-speech can pick a matching voice. */
    val language: String = container.sourceManager.get(sourceId)?.language ?: "MULTI"

    fun updateSettings(transform: (com.nelson.yomukai.data.prefs.NovelReaderSettings) -> com.nelson.yomukai.data.prefs.NovelReaderSettings) =
        container.preferences.updateNovel(transform)

    override suspend fun loadContent(chapter: ChapterEntity) = container.contentRepository.novelText(chapter).map { text ->
        text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    override fun resumePosition(chapter: ChapterEntity, content: List<String>) =
        if (chapter.read) 0 else (chapter.progress * content.size).toInt().coerceIn(0, (content.size - 1).coerceAtLeast(0))

    /** [fraction] 0..1 of the chapter [chapterId] scrolled; [atEnd] when its last paragraph is visible. */
    fun onProgress(chapterId: String, fraction: Float, atEnd: Boolean) {
        persist(chapterId, 0, 0, fraction.coerceIn(0f, 1f), read = atEnd)
    }
}
