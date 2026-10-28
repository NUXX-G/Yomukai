package com.nelson.yomukai.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.search.SearchGroup
import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.ui.home.LanguageFilter
import com.nelson.yomukai.ui.home.TypeFilter
import com.nelson.yomukai.ui.home.matches
import com.nelson.yomukai.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Progress of one source during a search, shown as a chip under the search box. */
@Immutable
data class SourceProgress(val sourceId: String, val name: String, val isLoading: Boolean = true, val error: String? = null)

@Immutable
data class SearchUiState(
    val query: String = "",
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val languageFilter: LanguageFilter = LanguageFilter.ALL,
    val groups: List<SearchGroup> = emptyList(),
    val sources: List<SourceProgress> = emptyList(),
    val hasSearched: Boolean = false,
    val history: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
) {
    val isSearching get() = sources.any { it.isLoading }
}

class SearchViewModel(handle: SavedStateHandle, private val container: AppContainer) : ViewModel() {

    private val preferences = container.preferences
    private val _state = MutableStateFlow(
        SearchUiState(query = handle.get<String>(Routes.ARG_QUERY).orEmpty(), history = preferences.searchHistory.value)
    )
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    /** Raw results per source for the running search; regrouped whenever one source reports. */
    private val found = LinkedHashMap<String, List<MediaItem>>()
    private var libraryTitles: List<String> = emptyList()

    init {
        if (_state.value.query.isNotBlank()) search()
        viewModelScope.launch {
            preferences.searchHistory.collect { history ->
                _state.update { it.copy(history = history, suggestions = suggestionsFor(it.query, history)) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            libraryTitles = container.libraryRepository.observeLibrary().first().map { it.title }
        }
        // Live search after the user pauses typing; each new query cancels the previous search.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _state.map { it.query.trim() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(400)
                .filter { it.length >= 2 }
                .collect { search(saveToHistory = false) }
        }
    }

    fun onQueryChange(query: String) = _state.update {
        it.copy(query = query, suggestions = suggestionsFor(query, it.history))
    }

    fun pickSuggestion(text: String) {
        onQueryChange(text)
        search()
    }

    fun sourceName(sourceId: String): String = container.sourceManager.get(sourceId)?.name ?: sourceId

    fun removeFromHistory(query: String) = preferences.removeSearchHistory(query)
    fun clearHistory() = preferences.clearSearchHistory()

    fun setTypeFilter(filter: TypeFilter) {
        _state.update { it.copy(typeFilter = filter) }
        if (_state.value.hasSearched) search(saveToHistory = false)
    }

    fun setLanguageFilter(filter: LanguageFilter) {
        _state.update { it.copy(languageFilter = filter) }
        if (_state.value.hasSearched) search(saveToHistory = false)
    }

    private fun suggestionsFor(query: String, history: List<String>): List<String> {
        val q = SmartSearch.normalize(query)
        if (q.length < 2) return emptyList()
        return (history + libraryTitles)
            .filter { SmartSearch.normalize(it).let { n -> n.startsWith(q) || n.contains(" $q") } && !it.equals(query, true) }
            .distinctBy { SmartSearch.normalize(it) }
            .take(5)
    }

    fun search(saveToHistory: Boolean = true) {
        val current = _state.value
        val query = current.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        if (saveToHistory) preferences.addSearchHistory(query)

        val sources = container.sourceManager.enabled(container.preferences.app.value.enabledSources)
            .filter { it.matches(current.languageFilter) }
            .filter { s -> s.supportedTypes.any { it in current.typeFilter.types } }

        found.clear()
        _state.update {
            it.copy(hasSearched = true, groups = emptyList(), suggestions = emptyList(), sources = sources.map { s -> SourceProgress(s.id, s.name) })
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            sources.forEach { source ->
                launch {
                    val outcome = searchSource(source, query)
                    synchronized(found) { found[source.id] = outcome.first.filter { it.type in current.typeFilter.types } }
                    val groups = synchronized(found) { SmartSearch.rankAndGroup(query, found.values.flatten()) }
                    _state.update { st ->
                        st.copy(
                            groups = groups,
                            sources = st.sources.map {
                                if (it.sourceId == source.id) it.copy(isLoading = false, error = outcome.second) else it
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Sources match literally, so when the exact query yields little we also try relaxed variants
     * (longest word, shortened word); [SmartSearch] then ranks everything fuzzily.
     */
    private suspend fun searchSource(source: CatalogueSource, query: String): Pair<List<MediaItem>, String?> {
        val items = LinkedHashMap<String, MediaItem>()
        var error: String? = null
        for ((index, variant) in SmartSearch.queryVariants(query).withIndex()) {
            if (index > 0 && items.size >= RELAX_THRESHOLD) break
            source.searchMedia(variant)
                .onSuccess { list -> list.forEach { items.putIfAbsent(it.id, it) } }
                .onFailure { e -> if (e is CancellationException) throw e else error = e.message ?: "Error" }
            // A hard failure (blocked/offline) will not improve with another variant.
            if (error != null && items.isEmpty()) break
        }
        return items.values.toList() to if (items.isEmpty()) error else null
    }

    private companion object {
        const val RELAX_THRESHOLD = 5
    }
}
