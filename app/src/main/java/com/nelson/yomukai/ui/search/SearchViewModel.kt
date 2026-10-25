package com.nelson.yomukai.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.ui.home.LanguageFilter
import com.nelson.yomukai.ui.home.SourceSection
import com.nelson.yomukai.ui.home.TypeFilter
import com.nelson.yomukai.ui.home.matches
import com.nelson.yomukai.ui.navigation.Routes
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val languageFilter: LanguageFilter = LanguageFilter.ALL,
    val results: List<SourceSection> = emptyList(),
    val hasSearched: Boolean = false
) {
    val isSearching get() = results.any { it.isLoading }
}

class SearchViewModel(handle: SavedStateHandle, private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState(query = handle.get<String>(Routes.ARG_QUERY).orEmpty()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        if (_state.value.query.isNotBlank()) search()
        // Live search after the user pauses typing.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _state.map { it.query.trim() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(700)
                .filter { it.length >= 3 }
                .collect { search() }
        }
    }

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    fun setTypeFilter(filter: TypeFilter) {
        _state.update { it.copy(typeFilter = filter) }
        if (_state.value.hasSearched) search()
    }

    fun setLanguageFilter(filter: LanguageFilter) {
        _state.update { it.copy(languageFilter = filter) }
        if (_state.value.hasSearched) search()
    }

    fun search() {
        val current = _state.value
        val query = current.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        val sources = container.sourceManager.enabled(container.preferences.app.value.enabledSources)
            .filter { it.matches(current.languageFilter) }
            .filter { s -> s.supportedTypes.any { it in current.typeFilter.types } }

        _state.update {
            it.copy(
                hasSearched = true,
                results = sources.map { s -> SourceSection(s.id, s.name, s.language, s.supportedTypes) }
            )
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            sources.forEach { source ->
                launch {
                    val result = source.searchMedia(query)
                    _state.update { st ->
                        st.copy(results = st.results.map { section ->
                            if (section.sourceId != source.id) section
                            else section.copy(
                                isLoading = false,
                                items = result.getOrDefault(emptyList()).filter { it.type in current.typeFilter.types },
                                error = result.exceptionOrNull()?.let { e -> e.message ?: "Error" }
                            )
                        })
                    }
                }
            }
        }
    }
}
