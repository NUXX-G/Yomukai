package com.nelson.yomukai.ui.discover

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.discovery.RandomKind
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.ui.details.LocateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** AniList genre names, which its API needs in English, and how they are shown. */
val RANDOM_GENRES = listOf(
    "Action" to "Acción", "Adventure" to "Aventura", "Comedy" to "Comedia", "Drama" to "Drama", "Ecchi" to "Ecchi",
    "Fantasy" to "Fantasía", "Horror" to "Terror", "Mahou Shoujo" to "Mahou shoujo", "Mecha" to "Mecha", "Music" to "Música",
    "Mystery" to "Misterio", "Psychological" to "Psicológico", "Romance" to "Romance", "Sci-Fi" to "Ciencia ficción",
    "Slice of Life" to "Recuentos de la vida", "Sports" to "Deportes", "Supernatural" to "Sobrenatural", "Thriller" to "Suspenso"
)

@Immutable
data class DiscoverUiState(
    val kind: RandomKind = RandomKind.ANY,
    val genre: String? = null,
    val rolling: Boolean = false,
    val random: RelatedTitle? = null,
    val randomError: String? = null,
    val loading: Boolean = false,
    val forYou: List<RelatedTitle> = emptyList()
)

class DiscoverViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val _locate = MutableStateFlow<LocateState?>(null)
    val locate: StateFlow<LocateState?> = _locate.asStateFlow()

    private var locateJob: Job? = null
    private var loaded = false

    fun setKind(kind: RandomKind) = _state.update { it.copy(kind = kind) }
    fun setGenre(genre: String?) = _state.update { it.copy(genre = genre) }

    /** Built from the library first, then the history: what the user chose to keep says the most. */
    fun loadForYou() {
        if (loaded) return
        loaded = true
        _state.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val repo = container.libraryRepository
            val library = repo.observeLibrary().first()
            val history = repo.observeHistory().first().mapNotNull { repo.getMedia(it.sourceId, it.mediaId) }.map { media ->
                library.firstOrNull { it.sourceId == media.sourceId && it.id == media.mediaId }
                    ?: com.nelson.yomukai.domain.model.MediaItem(media.mediaId, media.title, "", com.nelson.yomukai.domain.model.MediaType.fromName(media.type), "", "", media.sourceId)
            }
            val found = runCatching { container.discovery.forYou(library + history) }.getOrElse { e ->
                if (e is CancellationException) throw e
                emptyList()
            }
            _state.update { it.copy(loading = false, forYou = found) }
        }
    }

    fun rollRandom() {
        val current = _state.value
        _state.update { it.copy(rolling = true, randomError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val pick = runCatching { container.discovery.random(current.kind, current.genre) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            _state.update {
                it.copy(rolling = false, random = pick, randomError = if (pick == null) "No se pudo obtener una recomendación. Prueba otra vez." else null)
            }
        }
    }

    fun open(related: RelatedTitle) {
        locateJob?.cancel()
        _locate.value = LocateState(related.title, searching = true)
        locateJob = viewModelScope.launch(Dispatchers.IO) {
            val options = runCatching { container.discovery.locate(related) }.getOrElse { e ->
                if (e is CancellationException) throw e
                emptyList()
            }
            _locate.value = LocateState(related.title, searching = false, options = options)
        }
    }

    fun closeLocate() {
        locateJob?.cancel()
        _locate.value = null
    }
}
