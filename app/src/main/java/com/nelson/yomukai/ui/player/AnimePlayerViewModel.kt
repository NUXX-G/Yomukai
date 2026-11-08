package com.nelson.yomukai.ui.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.repository.toDomain
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.domain.model.VideoSource
import com.nelson.yomukai.i18n.tr
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
    val startPositionMs: Long = 0L,
    /** "" = stream, "hls" = downloaded HLS (Media3 cache), "file" = downloaded MP4 file. */
    val local: String = ""
)

@Immutable
data class PlayerUiState(
    val playing: Playing,
    val isResolving: Boolean = false,
    val error: String? = null,
    /** Web embed the user can still try in a browser when no stream played here. */
    val browserUrl: String? = null
)

/** Picks the server to autoplay: same server as before when possible, otherwise the first direct stream. */
fun pickServer(servers: List<VideoSource>, preferredName: String): VideoSource? {
    val direct = servers.filter { it.isDirect }
    return direct.firstOrNull { it.serverName.equals(preferredName, ignoreCase = true) } ?: direct.firstOrNull()
}

/** Scraped links may only leave the app as plain web links, never as intents or custom schemes. */
fun isWebUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/** Player pages the hidden WebView may try, at most two (each can take seconds). */
fun embedsToSniff(servers: List<VideoSource>, failed: Set<String>): List<VideoSource> =
    servers.filter { !it.isDirect && isWebUrl(it.videoUrl) && it.serverName !in failed }.take(2)

/** Next direct server of an episode that has not failed yet. */
fun nextServer(servers: List<VideoSource>, failed: Set<String>): VideoSource? =
    servers.firstOrNull { it.isDirect && it.serverName !in failed }

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

    /** Servers that already failed for the current episode, so a failure never loops. */
    private val failedServers = mutableSetOf<String>()

    private val _state = MutableStateFlow(
        PlayerUiState(
            Playing(
                videoUrl = handle.get<String>("url").orEmpty(),
                title = handle.get<String>("title") ?: tr("Anime"),
                referer = handle.get<String>("referer").orEmpty(),
                serverName = handle.get<String>("server").orEmpty(),
                chapterId = handle.get<String>(Routes.ARG_CHAPTER)?.takeIf { it.isNotBlank() },
                local = handle.get<String>("local").orEmpty()
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
    fun setAutoSkip(enabled: Boolean) = container.preferences.updateApp { it.copy(playerAutoSkip = enabled) }

    private val _marks = MutableStateFlow<com.nelson.yomukai.data.work.SkipMarks?>(null)

    /** Where this episode's opening and ending are (null: unknown, the "+85 s" button stays). */
    val skipMarks: StateFlow<com.nelson.yomukai.data.work.SkipMarks?> = _marks

    private var marksFor: String? = null

    /**
     * Opening/ending times for [chapterId]: with a server connected, its marks (the home worker's audio fingerprints,
     * or AniSkip's); without one, AniSkip directly by the title's MyAnimeList id.
     */
    fun loadMarks(chapterId: String?) {
        if (chapterId == null || chapterId == marksFor || !hasEpisodeContext) return
        marksFor = chapterId
        _marks.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val chapter = repo.getChapter(sourceId, chapterId) ?: return@launch
            val episode = chapter.number.toInt().takeIf { it > 0 } ?: return@launch
            val sync = container.serverSync
            val fromServer = if (sync.serverUrl != null) runCatching {
                fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)
                sync.api("GET", "/api/skip?s=${enc(sourceId)}&c=${enc(chapter.url)}&id=${enc(mediaId)}&ep=$episode")?.asJsonObject?.let { o ->
                    fun d(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asDouble
                    com.nelson.yomukai.data.work.SkipMarks(d("openingStart"), d("openingEnd"), d("endingStart"), d("endingEnd")).takeUnless { it.isEmpty }
                }
            }.getOrNull() else null
            val marks = fromServer ?: run {
                val media = repo.getMedia(sourceId, mediaId)?.toDomain() ?: return@run null
                val malId = runCatching { container.metadata.load(media).malId }.getOrNull() ?: return@run null
                com.nelson.yomukai.data.work.AniSkip.fetch(container.network, malId, episode)
            }
            if (marksFor == chapterId) _marks.value = marks
        }
    }

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

    private fun chapterById(id: String?): ChapterEntity? = id?.let { chapters.firstOrNull { c -> c.chapterId == it } }

    private fun currentChapter(): ChapterEntity? = chapterById(_state.value.playing.chapterId)

    val nextEpisode: ChapterEntity? get() = currentChapter()?.let { chapters.getOrNull(it.sortIndex + 1) }

    fun hasNext(): Boolean = nextEpisode != null

    fun playNext() = nextEpisode?.let(::playEpisode)

    /** Resolves the servers of [chapter] and switches to the best direct stream. */
    fun playEpisode(chapter: ChapterEntity) {
        val source = container.sourceManager.get(sourceId) as? AnimeSource ?: return
        failedServers.clear()
        _state.update { it.copy(isResolving = true, error = null, browserUrl = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = repo.getChapter(sourceId, chapter.chapterId) ?: chapter
            container.downloadManager.localVideo(sourceId, fresh.chapterId)?.let { local ->
                val mediaTitle = repo.getMedia(sourceId, fresh.mediaId)?.title
                val start = if (!fresh.read && fresh.lastPageRead > 10) fresh.lastPageRead * 1000L else 0L
                lastSavedMs = -1L
                repo.saveProgress(fresh, fresh.lastPageRead, fresh.totalPages, fresh.progress, markRead = false)
                _state.update {
                    PlayerUiState(
                        Playing(
                            videoUrl = local.url,
                            title = listOfNotNull(mediaTitle, fresh.title).joinToString(" - "),
                            referer = local.referer,
                            serverName = it.playing.serverName,
                            chapterId = fresh.chapterId,
                            startPositionMs = start,
                            local = if (local.isHls) "hls" else "file"
                        )
                    )
                }
                return@launch
            }
            source.fetchVideoSources(fresh.url)
                .onSuccess { found ->
                    // No direct stream: let the hidden WebView catch one from a player page before giving up.
                    val servers = if (found.none { it.isDirect }) found + sniffEmbeds(found) else found
                    val server = pickServer(servers, _state.value.playing.serverName)
                    if (server == null) {
                        _state.update { it.copy(isResolving = false, error = tr("Este episodio solo tiene servidores que se abren en el navegador")) }
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
                .onFailure { e -> _state.update { it.copy(isResolving = false, error = e.message ?: tr("No se pudo cargar el episodio")) } }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Streams caught by the hidden WebView from the episode's player pages (none if it finds nothing). */
    private suspend fun sniffEmbeds(servers: List<VideoSource>): List<VideoSource> =
        embedsToSniff(servers, failedServers).mapNotNull { embed -> runCatching { container.embedSniffer.sniff(embed) }.getOrNull() }

    /**
     * The stream could not be played (blocked, expired, unsupported). Moves to the next direct server of
     * the episode, keeping the position; the user only sees a message when none of them works.
     */
    fun onPlaybackError(detail: String?) {
        val playing = _state.value.playing
        val source = container.sourceManager.get(sourceId) as? AnimeSource
        val chapter = currentChapter()
        if (playing.local.isNotEmpty() || source == null || chapter == null) {
            _state.update { it.copy(isResolving = false, error = playbackMessage(detail)) }
            return
        }
        failedServers += playing.serverName
        val position = lastSavedMs.coerceAtLeast(0L)
        _state.update { it.copy(isResolving = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            source.fetchVideoSources(chapter.url)
                .onSuccess { found ->
                    val next = nextServer(found, failedServers) ?: nextServer(sniffEmbeds(found), failedServers)
                    val servers = found
                    if (next == null) {
                        _state.update {
                            it.copy(
                                isResolving = false,
                                error = playbackMessage(detail),
                                browserUrl = servers.firstOrNull { s -> !s.isDirect && isWebUrl(s.videoUrl) }?.videoUrl
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                playing = it.playing.copy(
                                    videoUrl = next.videoUrl,
                                    referer = next.headers["Referer"].orEmpty(),
                                    serverName = next.serverName,
                                    startPositionMs = position
                                ),
                                isResolving = false
                            )
                        }
                    }
                }
                .onFailure { e -> _state.update { it.copy(isResolving = false, error = e.message ?: playbackMessage(detail)) } }
        }
    }

    private fun playbackMessage(detail: String?) =
        tr("No se pudo reproducir este vídeo en la app") + (detail?.let { " ($it)" } ?: "") +
            ". Puede estar borrado en este servidor: prueba con «Buscar en otras fuentes» en la ficha del título."

    /**
     * Called about twice a second; saves at most every ~5 s of playback. [chapterId] is the episode of the
     * player reporting: when switching episodes the old player reports after the state has moved on.
     */
    fun onPosition(chapterId: String?, positionMs: Long, durationMs: Long, force: Boolean = false) {
        val chapter = chapterById(chapterId) ?: return
        if (durationMs <= 0 || positionMs < 0) return
        val isCurrent = chapterId == _state.value.playing.chapterId
        if (!force && lastSavedMs >= 0 && abs(positionMs - lastSavedMs) < SAVE_INTERVAL_MS) return
        if (isCurrent) lastSavedMs = positionMs
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
