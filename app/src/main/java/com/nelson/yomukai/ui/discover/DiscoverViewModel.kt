package com.nelson.yomukai.ui.discover

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.discovery.DiscoverEntry
import com.nelson.yomukai.data.discovery.DiscoverKind
import com.nelson.yomukai.data.discovery.RandomKind
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.i18n.tr
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
    "Action" to tr("Acción"), "Adventure" to "Aventura", "Comedy" to "Comedia", "Drama" to "Drama", "Ecchi" to "Ecchi",
    "Fantasy" to tr("Fantasía"), "Horror" to "Terror", "Mahou Shoujo" to "Mahou shoujo", "Mecha" to "Mecha", "Music" to tr("Música"),
    "Mystery" to "Misterio", "Psychological" to tr("Psicológico"), "Romance" to "Romance", "Sci-Fi" to tr("Ciencia ficción"),
    "Slice of Life" to tr("Recuentos de la vida"), "Sports" to "Deportes", "Supernatural" to "Sobrenatural", "Thriller" to "Suspenso"
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
private const val MAX_PRESENT = 200

/** A row of the home page from AniList ("Anime en tendencia"...). */
@Immutable
data class DiscoverShelf(val id: String, val title: String, val items: List<DiscoverEntry>)

class DiscoverViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val _locate = MutableStateFlow<LocateState?>(null)
    val locate: StateFlow<LocateState?> = _locate.asStateFlow()

    private var locateJob: Job? = null

    private val _shelves = MutableStateFlow<List<DiscoverShelf>>(emptyList())
    val shelves: StateFlow<List<DiscoverShelf>> = _shelves.asStateFlow()
    private var shelvesLoading = false

    /** The home's AniList rows, the same ones the web shows: two requests, kept for the life of the screen. */
    fun loadShelves() {
        if (shelvesLoading || _shelves.value.isNotEmpty()) return
        shelvesLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            val anime = runCatching { container.aniListDiscover.rows(DiscoverKind.ANIME) }.getOrDefault(emptyMap())
            val manga = runCatching { container.aniListDiscover.rows(DiscoverKind.MANGA) }.getOrDefault(emptyMap())
            val shelves = listOf(
                DiscoverShelf("anime_trending", tr("Anime en tendencia"), anime["trending"].orEmpty()),
                DiscoverShelf("anime_season", tr("En emisión esta temporada"), anime["season"].orEmpty()),
                DiscoverShelf("manga_trending", tr("Manga y manhwa en tendencia"), manga["trending"].orEmpty()),
                DiscoverShelf("manga_releasing", tr("Publicándose ahora"), manga["releasing"].orEmpty())
            )
            // Pedido 3 / O1, like the web: with a server, only what is in the sources, each with its work.
            val present = presentWorks(shelves.flatMap { s -> s.items.map { it.anilistId } })
            workKeys = present.orEmpty()
            _shelves.value = shelves.map { s -> if (present == null) s else s.copy(items = s.items.filter { it.anilistId in present }) }
                .filter { it.items.isNotEmpty() }
            shelvesLoading = false
        }
    }

    /** AniList id → work key of the server's index; null without a server (then the rows stay as AniList gives them). */
    @Volatile private var workKeys: Map<Int, String> = emptyMap()

    private suspend fun presentWorks(ids: List<Int>): Map<Int, String>? {
        if (container.serverSync.serverUrl == null || ids.isEmpty()) return null
        val body = com.google.gson.JsonObject().apply {
            add("ids", com.google.gson.JsonArray().apply { ids.distinct().take(MAX_PRESENT).forEach { add(it) } })
        }
        val answer = runCatching { container.serverSync.api("POST", "/api/obras/presentes", body) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject?.get("present")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return answer.entrySet().mapNotNull { (id, key) -> id.toIntOrNull()?.let { it to key.asString } }.toMap()
    }

    /** An AniList card opens its work at once when the server knows it; otherwise it is looked for, like any related title. */
    fun openEntry(e: DiscoverEntry) {
        val key = workKeys[e.anilistId]
        if (key == null) {
            open(RelatedTitle(e.title, e.names, e.cover, e.type, e.genres, format = e.format, anilistId = e.anilistId))
            return
        }
        locateJob?.cancel()
        _locate.value = LocateState(e.title, searching = true)
        locateJob = viewModelScope.launch(Dispatchers.IO) {
            val item = runCatching { com.nelson.yomukai.ui.details.placeOfWork(container, key, e.type) }.getOrNull()
            if (item != null) {
                container.libraryRepository.cacheMedia(item)
                _locate.value = LocateState(e.title, searching = true, openNow = item)
            } else {
                com.nelson.yomukai.ui.details.locateAndOpen(
                    container, RelatedTitle(e.title, e.names, e.cover, e.type, e.genres, format = e.format, anilistId = e.anilistId), _locate
                )
            }
        }
    }

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
                if (found == null) it.copy(rolling = false, randomError = tr("Sin conexión ahora mismo. Prueba en un momento."))
                else it.copy(rolling = false, random = found.first, randomPlaces = found.second)
            }
        }
    }

    fun open(related: RelatedTitle) {
        locateJob?.cancel()
        _locate.value = LocateState(related.title, searching = true)
        locateJob = viewModelScope.launch(Dispatchers.IO) { com.nelson.yomukai.ui.details.locateAndOpen(container, related, _locate) }
    }

    fun closeLocate() {
        locateJob?.cancel()
        _locate.value = null
    }
}
