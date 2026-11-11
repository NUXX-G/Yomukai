package com.nelson.yomukai.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.discovery.CalendarSlot
import com.nelson.yomukai.data.discovery.DiscoverEntry
import com.nelson.yomukai.data.discovery.WeekCalendar
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The data behind [CalendarScreen]. The week and the community come from AniList directly (the phone asks; it is
 * public and light); with the server connected, its /api/calendar adds the library's anime and the rhythm of manga
 * and novels, and /api/calendar/picks the group's recommendations (switch season_social).
 */
class CalendarViewModel(private val container: AppContainer) : ViewModel() {
    data class Comment(val name: String, val recommend: Boolean, val comment: String, val at: Long, val mine: Boolean)
    data class Pick(val anilistId: Int, val title: String, val cover: String, val recommends: Int, val comments: List<Comment>, val mine: Comment?)

    data class State(
        val loading: Boolean = true,
        val mine: List<CalendarSlot> = emptyList(),
        val airing: List<CalendarSlot> = emptyList(),
        val top: List<CalendarSlot> = emptyList(),
        val trending: List<CalendarSlot> = emptyList(),
        val picksOn: Boolean = false,
        val picks: List<Pick> = emptyList(),
        val error: String? = null
    ) {
        val recommends: Map<Int?, Int> get() = picks.associate { it.anilistId to it.recommends }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch(Dispatchers.IO) {
            val sync = container.serverSync
            val now = System.currentTimeMillis() / 1000
            val airingJob = async { safe { container.aniListDiscover.airing(now - DAY_S, now + 8 * DAY_S) }.orEmpty() }
            val communityJob = async { safe { container.aniListDiscover.seasonCommunity() }.orEmpty() }
            val featuresJob = async { if (sync.serverUrl != null) sync.features() else null }
            val mineJob = async { if (sync.serverUrl != null) safe { sync.api("GET", "/api/calendar?days=8&raw=1") }?.let(::parseMine) else null }
            val library = container.libraryRepository.observeLibrary().first().filter { it.type == MediaType.ANIME }.map { it.title }
            val airing = WeekCalendar.markLibrary(airingJob.await().mapNotNull { WeekCalendar.fromAiring(it) }, library)
            val community = communityJob.await()
            val picksOn = featuresJob.await()?.on("season_social") == true
            _state.value = State(
                loading = false, mine = mineJob.await().orEmpty(), airing = airing,
                top = community["top"].orEmpty().map(::communitySlot), trending = community["trending"].orEmpty().map(::communitySlot),
                picksOn = picksOn, error = if (airing.isEmpty()) tr("Sin conexión ahora mismo. Prueba en un momento.") else null
            )
            if (picksOn) refreshPicks()
        }
    }

    fun week(today: LocalDate, mineOnly: Boolean) = state.value.let { WeekCalendar.week(it.mine, it.airing, today, 7, mineOnly) }

    /** A pick as an entry the sheet can show: the calendar's or the community's when known, else its title and cover. */
    fun slotFor(p: Pick): CalendarSlot {
        val s = state.value
        return (s.airing + s.top + s.trending).firstOrNull { it.anilistId == p.anilistId }
            ?: CalendarSlot("p:${p.anilistId}", p.title, cover = p.cover, date = LocalDate.now(), anilistId = p.anilistId)
    }

    suspend fun savePick(anilistId: Int, body: JsonObject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { setPicks(container.serverSync.api("PUT", "/api/calendar/picks/$anilistId", body)) }
    }

    suspend fun removePick(anilistId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { setPicks(container.serverSync.api("DELETE", "/api/calendar/picks/$anilistId")) }
    }

    private suspend fun refreshPicks() {
        safe { container.serverSync.api("GET", "/api/calendar/picks") }?.let(::setPicks)
    }

    private fun setPicks(json: JsonElement?) {
        val items = json?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("items") ?: return
        val picks = items.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            Pick(
                o.int("anilistId") ?: return@mapNotNull null, o.text("title"), absolute(o.text("cover")), o.int("recommends") ?: 0,
                o.getAsJsonArray("comments")?.mapNotNull { c -> c.takeIf { it.isJsonObject }?.asJsonObject?.let(::comment) }.orEmpty(),
                o.get("mine")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::comment)
            )
        }
        _state.update { it.copy(picks = picks) }
    }

    /** The server's covers go through its image proxy, which needs the session: the app keeps AniList's own when it can. */
    private fun absolute(cover: String): String = when {
        cover.startsWith("/api/img") -> (Regex("[?&]o=([^&]+)").find(cover) ?: Regex("[?&]u=([^&]+)").find(cover))
            ?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
        else -> cover
    }

    private fun comment(o: JsonObject) = Comment(o.text("name"), o.get("recommend")?.asBoolean == true, o.text("comment"), o.get("at")?.asLong ?: 0, o.get("mine")?.asBoolean == true)

    private fun parseMine(json: JsonElement): List<CalendarSlot> = json.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val date = runCatching { LocalDate.parse(o.text("date")) }.getOrNull() ?: return@mapNotNull null
        val at = o.get("airingAt")?.takeIf { it.isJsonPrimitive }?.asLong
        CalendarSlot(
            key = "m:" + o.text("workKey") + o.text("label"), title = o.text("title"), cover = o.text("cover"),
            date = at?.let { WeekCalendar.dayOf(it) } ?: date, airingAt = at, episode = o.int("episode"),
            label = if (at != null) "" else o.text("label"), estimated = o.get("estimated")?.asBoolean == true, mine = true,
            anilistId = o.int("anilistId"), synopsis = o.text("synopsis"),
            genres = o.getAsJsonArray("genres")?.mapNotNull { g -> g.takeIf { it.isJsonPrimitive }?.asString }.orEmpty(),
            score = o.int("score"), sourceId = o.text("sourceId").ifBlank { null }, mediaId = o.text("mediaId").ifBlank { null }
        )
    }.orEmpty()

    private fun communitySlot(e: DiscoverEntry) = CalendarSlot(
        key = "c:${e.anilistId}", title = e.title, names = e.names, cover = e.cover, date = LocalDate.now(), anilistId = e.anilistId,
        synopsis = e.synopsis, genres = e.genres, score = e.score, entry = e
    )

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.text(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()

    companion object {
        private const val DAY_S = 86_400L
    }
}
