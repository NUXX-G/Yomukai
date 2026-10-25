package com.nelson.yomukai.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val sourceName: String = "",
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val endReached: Boolean = false
)

class BrowseViewModel(handle: SavedStateHandle, private val container: AppContainer) : ViewModel() {
    private val source = container.sourceManager.require(checkNotNull(handle[Routes.ARG_SOURCE]))

    private val _state = MutableStateFlow(BrowseUiState(sourceName = source.name))
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        loadNextPage()
    }

    fun loadNextPage() {
        val current = _state.value
        if (current.isLoading || current.endReached) return
        val page = current.page + 1
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            source.fetchLatestUpdates(page)
                .onSuccess { items ->
                    _state.update { st ->
                        val merged = (st.items + items).distinctBy { it.id }
                        st.copy(
                            items = merged,
                            page = page,
                            isLoading = false,
                            // Stop when the source has no paging or returned nothing new.
                            endReached = !source.supportsLatestPaging || merged.size == st.items.size
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message ?: "Error de conexión") } }
        }
    }
}
