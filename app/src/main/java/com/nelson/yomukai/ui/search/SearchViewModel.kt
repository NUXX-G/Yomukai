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
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.home.LanguageFilter
import com.nelson.yomukai.ui.home.TypeFilter
import com.nelson.yomukai.ui.home.matches
import com.nelson.yomukai.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    private val health get() = container.libraryRepository.chapterHealth

    private val preferences = container.preferences
    private val _state = MutableStateFlow(
        SearchUiState(query = handle.get<String>(Routes.ARG_QUERY).orEmpty(), history = preferences.searchHistory.value)
    )
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    /** Raw results per source for the running search; regrouped whenever one source reports. */
    private val found = LinkedHashMap<String, List<MediaItem>>()

    /** Results a site gave for the query or one of its other names. */
    private val trusted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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

    fun onQueryChange(query: String) {
        if (query.isBlank()) searchJob?.cancel()
        _state.update {
            val cleared = query.isBlank()
            it.copy(
                query = query,
                suggestions = suggestionsFor(query, it.history),
                hasSearched = it.hasSearched && !cleared,
                groups = if (cleared) emptyList() else it.groups,
                sources = if (cleared) emptyList() else it.sources
            )
        }
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
        trusted.clear()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            // Lazy: it only runs for a source that came back nearly empty, and AniList requests are scarce.
            val aliases = async(start = CoroutineStart.LAZY) { container.discovery.aliases(query) }
            sources.forEach { source ->
                launch {
                    // A source slower than this is left out (marked), so the search does not stay "loading" for it.
                    val result = kotlinx.coroutines.withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                        com.nelson.yomukai.data.search.SourceSearch.search(source, query) { aliases.await() }
                    } ?: com.nelson.yomukai.data.search.SourceSearchResult(emptyList(), tr("Tardó demasiado"), emptySet())
                    trusted += result.trusted
                    val outcome = result.items to result.error
                    val allowed = container.contentFilter.screen(source, outcome.first)
                    synchronized(found) { found[source.id] = allowed.filter { it.type in current.typeFilter.types } }
                    val groups = synchronized(found) { health.arrangeGroups(SmartSearch.rankAndGroup(query, found.values.flatten(), trusted = trusted.toSet())) }
                    if (synchronized(found) { found.size } == sources.size) container.probeChapters(groups.take(PROBE_GROUPS).map { it.primary })
                    _state.update { st ->
                        st.copy(
                            groups = groups,
                            sources = st.sources.map {
                                if (it.sourceId == source.id) it.copy(isLoading = false, error = outcome.second) else it
                            }
                        )
                    }
                    val hidden = container.contentFilter.check(source, allowed)
                    if (hidden.isNotEmpty()) {
                        synchronized(found) { found[source.id] = found[source.id].orEmpty().filterNot { it.id in hidden } }
                        val updated = synchronized(found) { health.arrangeGroups(SmartSearch.rankAndGroup(query, found.values.flatten(), trusted = trusted.toSet())) }
                        _state.update { it.copy(groups = updated) }
                    }
                }
            }
        }
    }

    private companion object {
        const val SOURCE_TIMEOUT_MS = 10_000L

        /** Results whose chapter lists are checked in the background once the search ends. */
        const val PROBE_GROUPS = 10
    }
}
