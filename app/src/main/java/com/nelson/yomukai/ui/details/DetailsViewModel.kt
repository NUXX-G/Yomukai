package com.nelson.yomukai.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.local.CategoryEntity
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.DownloadEntity
import com.nelson.yomukai.data.local.MediaEntity
import com.nelson.yomukai.data.local.ReadingStatus
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    init {
        refresh()
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

    fun download(chapters: List<ChapterEntity>) {
        val media = state.value.media ?: return
        if (state.value.isAnime) return // streams are not downloadable
        viewModelScope.launch { container.downloadManager.enqueue(media, chapters) }
    }

    fun downloadUnread() = download(state.value.chapters.filter { !it.read && it.chapterId !in state.value.downloads })

    fun deleteDownload(chapter: ChapterEntity) {
        viewModelScope.launch { container.downloadManager.delete(sourceId, chapter.chapterId) }
    }
}
