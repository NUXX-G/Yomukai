package com.nelson.yomukai.ui.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class BrowseUiState(
    val sourceName: String = "",
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val endReached: Boolean = false,
    val filters: List<SourceFilter> = emptyList(),
    val values: FilterValues = emptyMap(),
    /** Text searched in this source; blank shows its catalogue. */
    val query: String = ""
)

class BrowseViewModel(handle: SavedStateHandle, private val container: AppContainer) : ViewModel() {
    private val source = container.sourceManager.require(checkNotNull(handle[Routes.ARG_SOURCE]))

    private val _state = MutableStateFlow(BrowseUiState(sourceName = source.name, filters = source.filters))
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Reopening a source shows its last first page at once, and it refreshes behind.
        container.browseMemory.get(source.id)?.let { saved -> _state.update { it.copy(items = saved) } }
        loadNextPage()
    }

    private fun st0Query(values: FilterValues) = values.isEmpty()

    /** Searches this source only; a blank [text] goes back to the catalogue. */
    fun search(text: String) {
        val query = text.trim()
        if (query.isEmpty()) {
            applyFilters(_state.value.values)
            return
        }
        loadJob?.cancel()
        _state.update { it.copy(items = emptyList(), page = 0, endReached = true, isLoading = true, error = null, query = query) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            source.searchMedia(query)
                .mapCatching { container.contentFilter.known(source, it) }
                .onSuccess { items ->
                    _state.update { it.copy(items = items.distinctBy { i -> i.id }, isLoading = false) }
                    val hidden = container.contentFilter.check(source, items)
                    if (hidden.isNotEmpty()) _state.update { st -> st.copy(items = st.items.filterNot { it.id in hidden }) }
                }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message ?: "Error de conexión") } }
        }
    }

    /** Replaces the active filters and restarts the catalogue from the first page. */
    fun applyFilters(values: FilterValues) {
        loadJob?.cancel()
        _state.update {
            it.copy(items = emptyList(), page = 0, endReached = false, isLoading = false, error = null, values = values, query = "")
        }
        loadNextPage()
    }

    fun loadNextPage() {
        val current = _state.value
        if (current.isLoading || current.endReached) return
        val page = current.page + 1
        val values = current.values
        _state.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            source.browse(page, values)
                .mapCatching { container.contentFilter.known(source, it) }
                .onSuccess { items ->
                    if (page == 1 && st0Query(values)) container.browseMemory.put(source.id, items)
                    launch {
                        val hidden = container.contentFilter.check(source, items)
                        if (hidden.isNotEmpty()) _state.update { st -> st.copy(items = st.items.filterNot { it.id in hidden }) }
                    }
                    _state.update { st ->
                        // The first page replaces what was shown from memory.
                        val merged = (if (page == 1) items else st.items + items).distinctBy { it.id }
                        st.copy(
                            items = merged,
                            page = page,
                            isLoading = false,
                            // Stop when the source has no paging or returned nothing new.
                            endReached = !source.supportsLatestPaging || items.isEmpty() || (page > 1 && merged.size == st.items.size)
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message ?: "Error de conexión") } }
        }
    }
}
