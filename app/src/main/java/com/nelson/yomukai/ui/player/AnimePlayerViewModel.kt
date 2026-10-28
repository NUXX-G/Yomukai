package com.nelson.yomukai.ui.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.domain.model.VideoSource
import com.nelson.yomukai.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the player is showing right now. */
@Immutable
data class Playing(
    val videoUrl: String,
    val title: String,
    val referer: String,
    val serverName: String = "",
    val chapterId: String? = null,
    val startPositionMs: Long = 0L
)

@Immutable
data class PlayerUiState(
    val playing: Playing,
    val isResolving: Boolean = false,
    val error: String? = null
)

/** Picks the server to autoplay: same server as before when possible, otherwise the first direct stream. */
fun pickServer(servers: List<VideoSource>, preferredName: String): VideoSource? {
    val direct = servers.filter { it.isDirect }
    return direct.firstOrNull { it.serverName.equals(preferredName, ignoreCase = true) } ?: direct.firstOrNull()
}

/** Episodes finished past this fraction are marked as watched. */
const val WATCHED_FRACTION = 0.9f

/**
 * Drives the anime player. When opened from a library title it knows the episode list, so it can
 * offer an episode index, autoplay the next episode and remember the position of every episode
 * (stored as seconds in the chapter's progress columns). Streams opened from the web-capture
 * browser have no episode context and simply play.
 */
class AnimePlayerViewModel(handle: SavedStateHandle, private val container: AppContainer) : ViewModel() {
    private val sourceId: String = handle.get<String>(Routes.ARG_SOURCE).orEmpty()
    private val mediaId: String = handle.get<String>(Routes.ARG_MEDIA).orEmpty()
    private val repo = container.libraryRepository

    private var chapters: List<ChapterEntity> = emptyList()
    private var lastSavedMs = -1L

    private val _state = MutableStateFlow(
        PlayerUiState(
            Playing(
                videoUrl = handle.get<String>("url").orEmpty(),
                title = handle.get<String>("title") ?: "Anime",
                referer = handle.get<String>("referer").orEmpty(),
                serverName = handle.get<String>("server").orEmpty(),
                chapterId = handle.get<String>(Routes.ARG_CHAPTER)?.takeIf { it.isNotBlank() }
            )
        )
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val hasEpisodeContext: Boolean = sourceId.isNotBlank() && mediaId.isNotBlank()

    val chapterList: StateFlow<List<ChapterEntity>> =
        (if (hasEpisodeContext) repo.observeChapters(sourceId, mediaId) else kotlinx.coroutines.flow.flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings = container.preferences.app
    fun setAutoNext(enabled: Boolean) = container.preferences.updateApp { it.copy(playerAutoNext = enabled) }
    fun setSpeed(speed: Float) = container.preferences.updateApp { it.copy(playerSpeed = speed) }

    init {
        val chapterId = _state.value.playing.chapterId
        if (hasEpisodeContext) {
            viewModelScope.launch(Dispatchers.IO) {
                chapters = repo.getChapters(sourceId, mediaId)
                val chapter = chapterId?.let { repo.getChapter(sourceId, it) }
                if (chapter != null) {
                    // Resume where this episode was left (unless it was already finished).
                    val start = if (!chapter.read && chapter.lastPageRead > 10) chapter.lastPageRead * 1000L else 0L
                    _state.update { it.copy(playing = it.playing.copy(startPositionMs = start)) }
                    repo.saveProgress(chapter, chapter.lastPageRead, chapter.totalPages, chapter.progress, markRead = false)
                }
            }
        }
    }

    private fun currentChapter(): ChapterEntity? =
        _state.value.playing.chapterId?.let { id -> chapters.firstOrNull { it.chapterId == id } }

    val nextEpisode: ChapterEntity? get() = currentChapter()?.let { chapters.getOrNull(it.sortIndex + 1) }

    fun hasNext(): Boolean = nextEpisode != null

    fun playNext() = nextEpisode?.let(::playEpisode)

    /** Resolves the servers of [chapter] and switches to the best direct stream. */
    fun playEpisode(chapter: ChapterEntity) {
        val source = container.sourceManager.get(sourceId) as? AnimeSource ?: return
        _state.update { it.copy(isResolving = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = repo.getChapter(sourceId, chapter.chapterId) ?: chapter
            source.fetchVideoSources(fresh.url)
                .onSuccess { servers ->
                    val server = pickServer(servers, _state.value.playing.serverName)
                    if (server == null) {
                        _state.update { it.copy(isResolving = false, error = "Este episodio solo tiene servidores que se abren en el navegador") }
                        return@onSuccess
                    }
                    val mediaTitle = repo.getMedia(sourceId, fresh.mediaId)?.title
                    val start = if (!fresh.read && fresh.lastPageRead > 10) fresh.lastPageRead * 1000L else 0L
                    lastSavedMs = -1L
                    repo.saveProgress(fresh, fresh.lastPageRead, fresh.totalPages, fresh.progress, markRead = false)
                    _state.update {
                        PlayerUiState(
                            Playing(
                                videoUrl = server.videoUrl,
                                title = listOfNotNull(mediaTitle, fresh.title).joinToString(" - "),
                                referer = server.headers["Referer"].orEmpty(),
                                serverName = server.serverName,
                                chapterId = fresh.chapterId,
                                startPositionMs = start
                            )
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(isResolving = false, error = e.message ?: "No se pudo cargar el episodio") } }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Called about twice a second; saves at most every ~5 s of playback. */
    fun onPosition(positionMs: Long, durationMs: Long, force: Boolean = false) {
        val chapter = currentChapter() ?: return
        if (durationMs <= 0 || positionMs < 0) return
        if (!force && lastSavedMs >= 0 && abs(positionMs - lastSavedMs) < SAVE_INTERVAL_MS) return
        lastSavedMs = positionMs
        val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        viewModelScope.launch(Dispatchers.IO) {
            repo.saveProgress(
                chapter,
                page = (positionMs / 1000).toInt(),
                totalPages = (durationMs / 1000).toInt(),
                progress = fraction,
                markRead = fraction >= WATCHED_FRACTION
            )
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
