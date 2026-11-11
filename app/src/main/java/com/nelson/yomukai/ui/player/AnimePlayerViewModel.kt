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
                // Opened by a room for a series this phone has not seen: its episodes are fetched first.
                if (chapters.none { it.chapterId == chapterId }) {
                    if (repo.getMedia(sourceId, mediaId) == null) repo.refreshDetails(sourceId, mediaId)
                    repo.refreshChapters(sourceId, mediaId)
                    chapters = repo.getChapters(sourceId, mediaId)
                }
                val chapter = chapterId?.let { repo.getChapter(sourceId, it) }
                updateNext()
                launch { loadSisters() }
                // Without a video yet (a room's episode): resolve it here like any other episode.
                if (chapter != null && _state.value.playing.videoUrl.isBlank()) { playEpisode(chapter); return@launch }
                if (chapter != null) {
                    // Resume where this episode was left (unless it was already finished).
                    val start = if (!chapter.read && chapter.lastPageRead > 10) chapter.lastPageRead * 1000L else 0L
                    _state.update { it.copy(playing = it.playing.copy(startPositionMs = start)) }
                    repo.saveProgress(chapter, chapter.lastPageRead, chapter.totalPages, chapter.progress, markRead = false)
                }
            }
        }
    }

    // --- Watch party ------------------------------------------------------------------------------

    private val _party = MutableStateFlow<com.nelson.yomukai.data.social.PartyClient?>(null)

    /** The room this player is in (watching with friends), or null. */
    val party: StateFlow<com.nelson.yomukai.data.social.PartyClient?> = _party.asStateFlow()

    /** Another series picked by the room: the screen opens a player for it. (sourceId, mediaId, chapterId) */
    private val _openOther = kotlinx.coroutines.flow.MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 1)
    val openOther: kotlinx.coroutines.flow.SharedFlow<Triple<String, String, String>> = _openOther

    init {
        // After the room's fields above exist (initialisers run in order).
        val startRoom = handle.get<String>("sala").orEmpty().ifBlank { container.activeRoom.value.orEmpty() }
        if (startRoom.isNotBlank()) joinRoom(startRoom)
    }

    fun joinRoom(code: String) {
        if (_party.value?.code == code) return
        _party.value?.close()
        _party.value = com.nelson.yomukai.data.social.PartyClient(container.serverSync, code).also { it.open() }
        container.activeRoom.value = code
    }

    /** «Ver con amigos»: into your own room on the server; its code, to pass to friends. */
    suspend fun watchTogether(): Result<String> = runCatching {
        _party.value?.code ?: run {
            val code = container.serverSync.api("GET", "/api/rooms/mine")?.asJsonObject?.get("code")?.asString
                ?: error(tr("Conecta la app con tu servidor para ver con amigos"))
            joinRoom(code)
            code
        }
    }

    /** The address friends open (web) for room [code]; the app joins with the code. */
    fun roomLink(code: String): String = (container.serverSync.serverUrl ?: "").trimEnd('/') + "/sala/" + code

    fun leaveRoom() {
        _party.value?.close()
        _party.value = null
        container.activeRoom.value = null
    }

    /** What plays now, as the room hears it from a host on this phone. */
    fun partyState(positionS: Double, playing: Boolean, speed: Double): com.nelson.yomukai.data.social.PartyState? {
        val chapter = currentChapter() ?: return null
        return com.nelson.yomukai.data.social.PartyState(
            sourceId, mediaId, chapter.chapterId, title = _state.value.playing.title.substringBefore(" - "), episode = chapter.title,
            server = _state.value.playing.serverName, position = positionS, playing = playing, speed = speed
        )
    }

    /** The room moved to another episode: this one, or another series (the screen opens it). */
    fun followRoomEpisode(state: com.nelson.yomukai.data.social.PartyState) {
        if (state.chapterId == _state.value.playing.chapterId || _state.value.isResolving) return
        // Only anime from a source this phone has: the room never makes it fetch from anywhere else.
        if (container.sourceManager.get(state.sourceId) !is AnimeSource) return
        if (state.sourceId != sourceId || state.mediaId != mediaId) { _openOther.tryEmit(Triple(state.sourceId, state.mediaId, state.chapterId)); return }
        viewModelScope.launch(Dispatchers.IO) {
            val chapter = chapterById(state.chapterId) ?: run {
                repo.refreshChapters(sourceId, mediaId)
                chapters = repo.getChapters(sourceId, mediaId)
                chapterById(state.chapterId)
            } ?: return@launch
            playEpisode(chapter)
        }
    }

    override fun onCleared() {
        // Closing the player leaves the room: the next player opened does not join it by itself.
        _party.value?.let { party ->
            party.close()
            if (container.activeRoom.value == party.code) container.activeRoom.value = null
        }
        super.onCleared()
    }

    private fun chapterById(id: String?): ChapterEntity? = id?.let { chapters.firstOrNull { c -> c.chapterId == it } }

    private fun currentChapter(): ChapterEntity? = chapterById(_state.value.playing.chapterId)

    private val _next = MutableStateFlow<com.nelson.yomukai.data.work.EpisodeRef?>(null)

    /**
     * What «Siguiente» opens: the next number of the work, from this source or, when it stops, from a sister source
     * of the same work (F1.1; same rule as the web). Reactive, so the button appears once the lists are loaded.
     */
    val next: StateFlow<com.nelson.yomukai.data.work.EpisodeRef?> = _next.asStateFlow()

    /** Another source's episode to open (the screen opens a player there). (sourceId, mediaId, chapterId) */
    private val _openNext = kotlinx.coroutines.flow.MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 1)
    val openNext: kotlinx.coroutines.flow.SharedFlow<Triple<String, String, String>> = _openNext

    private var sisterEpisodes: List<com.nelson.yomukai.data.work.EpisodeRef> = emptyList()

    private fun ChapterEntity.ref() = com.nelson.yomukai.data.work.EpisodeRef(sourceId, mediaId, chapterId, number, sortIndex)

    private fun updateNext() {
        val current = currentChapter() ?: run { _next.value = null; return }
        _next.value = com.nelson.yomukai.data.work.NextEpisode.pick(current.ref(), chapters.map { it.ref() }, sisterEpisodes)
    }

    /** The sister sources' episodes: known from the work's page, or (connected) from the server. */
    private suspend fun loadSisters() {
        val known = container.workSisters["$sourceId|$mediaId"].orEmpty().ifEmpty { serverSisters() }
        sisterEpisodes = known.flatMap { (s, m) ->
            repo.getChapters(s, m).ifEmpty { runCatching { repo.refreshChapters(s, m) }; repo.getChapters(s, m) }.map { it.ref() }
        }
        updateNext()
    }

    private suspend fun serverSisters(): List<Pair<String, String>> {
        if (container.serverSync.serverUrl == null) return emptyList()
        fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)
        fun com.google.gson.JsonObject.text(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null }
        val whole = runCatching { container.serverSync.api("GET", "/api/works/whole?s=${enc(sourceId)}&id=${enc(mediaId)}") }.getOrNull()
        val list = whole?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("chapters")?.getAsJsonArray("chapters") ?: return emptyList()
        return list.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let { o -> o.text("sourceId")?.let { s -> o.text("mediaId")?.let { m -> s to m } } } }
            .filter { (s, _) -> s != sourceId && container.sourceManager.get(s) is AnimeSource }.distinct().take(MAX_SISTERS)
    }

    fun hasNext(): Boolean = _next.value != null

    /** The episode this player moved to by itself, a moment ago: the room's old state must not take it back. */
    private var moved: Pair<String, Long>? = null

    fun movedHere(chapterId: String?): Boolean = moved?.let { (c, at) -> c == chapterId && System.currentTimeMillis() - at < MOVED_MS } == true

    /** Tells the room first, when this phone may drive it, so everyone goes to the new episode. */
    private fun announce(pick: com.nelson.yomukai.data.work.EpisodeRef) {
        moved = pick.chapterId to System.currentTimeMillis()
        val party = _party.value ?: return
        if (!party.room.value.canControl) return
        party.sendState(
            com.nelson.yomukai.data.social.PartyState(
                pick.sourceId, pick.mediaId, pick.chapterId, title = _state.value.playing.title.substringBefore(" - "),
                server = _state.value.playing.serverName, position = 0.0, playing = true, speed = 1.0
            )
        )
    }

    /** This episode as a card for «Compartir» (F4.3), with its number for the spoiler shield. */
    fun shareCard(): com.nelson.yomukai.data.social.ShareCard? {
        val chapter = chapterById(_state.value.playing.chapterId) ?: return null
        return com.nelson.yomukai.data.social.ShareCard(
            kind = "episode", sourceId = sourceId, mediaId = mediaId, chapterId = chapter.chapterId,
            title = _state.value.playing.title.substringBefore(" - "), type = "ANIME",
            lang = container.sourceManager.get(sourceId)?.language.orEmpty(),
            number = chapter.number.takeIf { it >= 0f }?.toDouble(), label = chapter.title
        )
    }

    /** Puts the next episode in the room's queue (F4.1); false when there is no next one or no room. */
    fun queueNext(): Boolean {
        val pick = _next.value ?: return false
        val party = _party.value ?: return false
        val n = pick.number
        val label = if (n >= 0f) tr("Ep. {0}", if (n % 1f == 0f) n.toInt().toString() else n.toString()) else ""
        party.queueAdd(pick.sourceId, pick.mediaId, pick.chapterId, _state.value.playing.title.substringBefore(" - "), label)
        return true
    }

    /** At the end of an episode in a room with something queued, the server moves everyone on (F4.1). */
    fun endedInRoom(chapterId: String?): Boolean {
        val party = _party.value ?: return false
        val room = party.room.value
        if (room.queue.isEmpty() || chapterId == null) return false
        if (room.canControl) party.ended(chapterId)
        return true
    }

    fun playNext() {
        val pick = _next.value ?: return
        announce(pick)
        if (pick.sourceId == sourceId && pick.mediaId == mediaId) chapterById(pick.chapterId)?.let(::playEpisode)
        else _openNext.tryEmit(Triple(pick.sourceId, pick.mediaId, pick.chapterId))
    }

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
                updateNext()
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
                    updateNext()
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
        const val MAX_SISTERS = 4
        const val MOVED_MS = 15_000L
    }
}
