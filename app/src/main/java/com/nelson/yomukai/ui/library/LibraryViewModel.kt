package com.nelson.yomukai.ui.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.local.CategoryEntity
import com.nelson.yomukai.data.local.DownloadEntity
import com.nelson.yomukai.data.local.HistoryWithMedia
import com.nelson.yomukai.data.local.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class LibraryUiState(
    val library: List<MediaEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    /** "sourceId|mediaId" → category ids. */
    val assignments: Map<String, Set<Long>> = emptyMap(),
    val history: List<HistoryWithMedia> = emptyList(),
    val downloads: List<DownloadEntity> = emptyList(),
    val isLoaded: Boolean = false
) {
    fun categoriesOf(media: MediaEntity): Set<Long> = assignments[key(media.sourceId, media.mediaId)].orEmpty()

    companion object {
        fun key(sourceId: String, mediaId: String) = "$sourceId|$mediaId"
    }
}

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.libraryRepository
    private val downloads = container.downloadManager

    val state: StateFlow<LibraryUiState> = combine(
        repo.observeLibraryEntries(),
        repo.observeCategories(),
        repo.observeCategoryAssignments(),
        repo.observeHistory(),
        downloads.observeAll()
    ) { library, categories, links, history, dl ->
        LibraryUiState(
            library = library,
            categories = categories,
            assignments = links.groupBy({ LibraryUiState.key(it.sourceId, it.mediaId) }, { it.categoryId })
                .mapValues { it.value.toSet() },
            history = history,
            downloads = dl,
            isLoaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun createCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.createCategory(name) }
    }

    fun renameCategory(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.renameCategory(id, name) }
    }

    fun deleteCategory(id: Long) {
        viewModelScope.launch { repo.deleteCategory(id) }
    }

    fun removeHistory(entry: HistoryWithMedia) {
        viewModelScope.launch { repo.deleteHistory(entry.sourceId, entry.mediaId) }
    }

    fun clearHistory() {
        viewModelScope.launch { repo.clearHistory() }
    }

    fun deleteDownload(download: DownloadEntity) {
        viewModelScope.launch { downloads.delete(download.sourceId, download.chapterId) }
    }

    fun retryDownload(download: DownloadEntity) {
        viewModelScope.launch { downloads.retry(download.sourceId, download.chapterId) }
    }

    fun deleteAllDownloads() {
        viewModelScope.launch(Dispatchers.IO) { downloads.deleteAll() }
    }
}
