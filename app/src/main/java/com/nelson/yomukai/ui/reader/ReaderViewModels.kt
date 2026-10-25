package com.nelson.yomukai.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.repository.PageData
import com.nelson.yomukai.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState<T>(
    val mediaTitle: String = "",
    val chapter: ChapterEntity? = null,
    val previous: ChapterEntity? = null,
    val next: ChapterEntity? = null,
    val content: T? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Where to resume: page index (manga) or paragraph index (novel). */
    val initialPosition: Int = 0
)

/** Shared chapter loading / navigation / progress logic for both readers. */
abstract class BaseReaderViewModel<T>(handle: SavedStateHandle, protected val container: AppContainer) : ViewModel() {
    protected val sourceId: String = checkNotNull(handle[Routes.ARG_SOURCE])
    protected val mediaId: String = checkNotNull(handle[Routes.ARG_MEDIA])
    private val startChapterId: String = checkNotNull(handle[Routes.ARG_CHAPTER])

    protected val repo = container.libraryRepository
    private var chapters: List<ChapterEntity> = emptyList()
    private var loadJob: Job? = null

    protected val _state = MutableStateFlow(ReaderUiState<T>())
    val state: StateFlow<ReaderUiState<T>> = _state.asStateFlow()

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

    private fun openChapter(chapterId: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // Re-read so resume positions reflect progress saved moments ago.
            val chapter = repo.getChapter(sourceId, chapterId) ?: chapters.firstOrNull { it.chapterId == chapterId }
            if (chapter == null) {
                _state.update { it.copy(isLoading = false, error = "Capítulo no encontrado") }
                return@launch
            }
            _state.update {
                it.copy(
                    chapter = chapter,
                    previous = chapters.getOrNull(chapter.sortIndex - 1),
                    next = chapters.getOrNull(chapter.sortIndex + 1),
                    content = null,
                    isLoading = true,
                    error = null
                )
            }
            loadContent(chapter)
                .onSuccess { content ->
                    _state.update {
                        it.copy(content = content, isLoading = false, initialPosition = resumePosition(chapter, content))
                    }
                    repo.saveProgress(chapter, chapter.lastPageRead, chapter.totalPages, chapter.progress, markRead = false)
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message ?: "No se pudo cargar el capítulo") }
                }
        }
    }

    protected fun persist(page: Int, total: Int, progress: Float, read: Boolean) {
        val chapter = state.value.chapter ?: return
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

    fun onPageChanged(page: Int) {
        val total = state.value.content?.size ?: return
        persist(page, total, (page + 1f) / total, read = page >= total - 1)
    }
}

class NovelReaderViewModel(handle: SavedStateHandle, container: AppContainer) :
    BaseReaderViewModel<List<String>>(handle, container) {

    val settings = container.preferences.novel
    fun updateSettings(transform: (com.nelson.yomukai.data.prefs.NovelReaderSettings) -> com.nelson.yomukai.data.prefs.NovelReaderSettings) =
        container.preferences.updateNovel(transform)

    override suspend fun loadContent(chapter: ChapterEntity) = container.contentRepository.novelText(chapter).map { text ->
        text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    override fun resumePosition(chapter: ChapterEntity, content: List<String>) =
        if (chapter.read) 0 else (chapter.progress * content.size).toInt().coerceIn(0, (content.size - 1).coerceAtLeast(0))

    /** [fraction] 0..1 of the chapter scrolled; [atEnd] when the last paragraph is visible. */
    fun onProgress(fraction: Float, atEnd: Boolean) {
        persist(0, 0, fraction.coerceIn(0f, 1f), read = atEnd)
    }
}
