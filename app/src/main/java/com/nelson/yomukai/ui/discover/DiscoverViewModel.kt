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
    /** Sources holding [random]; the pick only shows once at least one is known. */
    val randomPlaces: List<com.nelson.yomukai.data.discovery.Alternative> = emptyList(),
    val randomError: String? = null,
    val loading: Boolean = false,
    val forYou: List<RelatedTitle> = emptyList()
)

private const val RECENT_HISTORY = 24

class DiscoverViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val _locate = MutableStateFlow<LocateState?>(null)
    val locate: StateFlow<LocateState?> = _locate.asStateFlow()

    private var locateJob: Job? = null

    /** Changing what to pick rolls again at once: the shown pick always fits the chips. */
    fun setKind(kind: RandomKind) {
        if (_state.value.kind == kind) return
        _state.update { it.copy(kind = kind) }
        rollRandom()
    }
    fun setGenre(genre: String?) {
        if (_state.value.genre == genre) return
        _state.update { it.copy(genre = genre) }
        rollRandom()
    }

    /** Recent history and library in turns: what was just read says as much as what was kept. */
    fun loadForYou() {
        _state.update { it.copy(loading = it.forYou.isEmpty()) }
        viewModelScope.launch(Dispatchers.IO) {
            val repo = container.libraryRepository
            val library = repo.observeLibrary().first()
            // Only the recent past counts, and each row costs a database read.
            val history = repo.observeHistory().first().take(RECENT_HISTORY).mapNotNull { repo.getMedia(it.sourceId, it.mediaId) }.map { media ->
                library.firstOrNull { it.sourceId == media.sourceId && it.id == media.mediaId }
                    ?: com.nelson.yomukai.domain.model.MediaItem(media.mediaId, media.title, "", com.nelson.yomukai.domain.model.MediaType.fromName(media.type), "", "", media.sourceId)
            }
            val seen = history.zip(library).flatMap { listOf(it.first, it.second) } +
                history.drop(library.size) + library.drop(history.size)
            val found = runCatching { container.discovery.forYou(seen) }.getOrElse { e ->
                if (e is CancellationException) throw e
                emptyList()
            }
            _state.update { it.copy(loading = false, forYou = found.ifEmpty { it.forYou }) }
        }
    }

    private var rollJob: Job? = null

    /** A random title that some source has, with those sources; the previous one stays until it arrives. */
    fun rollRandom() {
        val current = _state.value
        rollJob?.cancel()
        _state.update { it.copy(rolling = true, randomError = null) }
        rollJob = viewModelScope.launch(Dispatchers.IO) {
            val found = runCatching { container.discovery.randomAvailable(current.kind, current.genre) }
                .onFailure { if (it is CancellationException) throw it }.getOrNull()
            _state.update {
                if (found == null) it.copy(rolling = false, randomError = "Sin conexión ahora mismo. Prueba en un momento.")
                else it.copy(rolling = false, random = found.first, randomPlaces = found.second)
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
