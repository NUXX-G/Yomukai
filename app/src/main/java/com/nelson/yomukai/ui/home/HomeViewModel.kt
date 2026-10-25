package com.nelson.yomukai.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.local.HistoryWithMedia
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TypeFilter(val label: String, val types: Set<MediaType>) {
    ALL("Todo", MediaType.entries.toSet()),
    MANGA("Manga", setOf(MediaType.MANGA, MediaType.COMIC)),
    MANHWA("Manhwa", setOf(MediaType.MANHWA, MediaType.MANHUA)),
    NOVEL("Novelas", setOf(MediaType.NOVEL)),
    ANIME("Anime", setOf(MediaType.ANIME))
}

enum class LanguageFilter(val label: String) { ALL("Todos"), ES("Español"), EN("Inglés") }

fun CatalogueSource.matches(language: LanguageFilter) =
    language == LanguageFilter.ALL || this.language == "MULTI" || this.language == language.name

data class SourceSection(
    val sourceId: String,
    val sourceName: String,
    val language: String,
    val types: Set<MediaType>,
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

data class HomeUiState(
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val languageFilter: LanguageFilter = LanguageFilter.ALL,
    val sections: List<SourceSection> = emptyList(),
    val continueReading: List<HistoryWithMedia> = emptyList(),
    val isRefreshing: Boolean = false
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val filters = MutableStateFlow(TypeFilter.ALL to LanguageFilter.ALL)
    private val rawSections = MutableStateFlow<List<SourceSection>>(emptyList())
    private val refreshing = MutableStateFlow(false)
    private var loadJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        filters, rawSections, refreshing, container.libraryRepository.observeHistory()
    ) { (type, language), sections, isRefreshing, history ->
        HomeUiState(
            typeFilter = type,
            languageFilter = language,
            sections = sections
                .filter { s -> (language == LanguageFilter.ALL || s.language == "MULTI" || s.language == language.name) }
                .filter { s -> s.types.any { it in type.types } }
                .map { s -> s.copy(items = s.items.filter { it.type in type.types }) }
                .filter { it.isLoading || it.error != null || it.items.isNotEmpty() },
            continueReading = history.take(12),
            isRefreshing = isRefreshing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        // Reload whenever the set of enabled sources changes.
        viewModelScope.launch {
            container.preferences.app
                .map { it.enabledSources }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    fun setTypeFilter(filter: TypeFilter) = filters.update { it.copy(first = filter) }
    fun setLanguageFilter(filter: LanguageFilter) = filters.update { it.copy(second = filter) }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            refreshing.value = true
            val settings = container.preferences.app.value
            val sources = container.sourceManager.enabled(settings.enabledSources)
            rawSections.value = sources.map { SourceSection(it.id, it.name, it.language, it.supportedTypes) }

            val jobs = sources.map { source ->
                async {
                    val result = source.fetchLatestUpdates(1)
                    updateSection(source.id) {
                        it.copy(
                            items = result.getOrDefault(emptyList()),
                            isLoading = false,
                            error = result.exceptionOrNull()?.let { e ->
                                Log.w("YomukaiHome", "Fuente ${source.id} falló", e)
                                e.message ?: "Error de conexión"
                            }
                        )
                    }
                }
            }
            jobs.forEach { it.await() }
            refreshing.value = false
        }
    }

    private fun updateSection(id: String, transform: (SourceSection) -> SourceSection) {
        rawSections.update { list -> list.map { if (it.sourceId == id) transform(it) else it } }
    }
}
