package com.nelson.yomukai.data.social

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.sync.ServerSync
import com.nelson.yomukai.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A watch-party room from the phone: the same WebSocket and rules as the web (web/src/lib/party.svelte.js), so phones
 * and computers share one room. The server's clock is the reference; [serverNow] follows it through the pings.
 */
class PartyClient(private val sync: ServerSync, val code: String) {
    data class ChatLine(val id: Long, val user: String, val text: String, val spoiler: Boolean)
    data class Member(val id: Long, val name: String)

    data class Room(
        val connected: Boolean = false,
        val name: String = "",
        val you: Long? = null,
        val host: Long? = null,
        val everyoneControls: Boolean = false,
        val members: List<Member> = emptyList(),
        val state: PartyState? = null,
        val waiting: List<String> = emptyList(),
        val messages: List<ChatLine> = emptyList(),
        val reactions: List<String> = emptyList(),
        val unread: Int = 0,
        /** Why the server refused the room; nothing more is tried then. */
        val closed: String? = null
    ) {
        val isHost get() = you != null && you == host
        val canControl get() = everyoneControls || isHost
    }

    /** A change of what plays; [byServer] when the server itself paused or resumed it (someone loading). */
    data class StateEvent(val state: PartyState, val byServer: Boolean)

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _room = MutableStateFlow(Room())
    val room: StateFlow<Room> = _room.asStateFlow()
    private val _states = MutableSharedFlow<StateEvent>(extraBufferCapacity = 8)
    val states: SharedFlow<StateEvent> = _states.asSharedFlow()
    private val _reactions = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val reactions: SharedFlow<String> = _reactions.asSharedFlow()

    @Volatile private var socket: WebSocket? = null

    @Volatile private var offset = 0L

    @Volatile private var pingSent = 0L

    @Volatile private var stopped = false
    private var retry = 0
    private var pinger: Job? = null

    fun serverNow(): Long = System.currentTimeMillis() + offset

    fun open() {
        if (stopped) return
        socket = sync.webSocket("/api/rooms/$code/ws", listener) ?: run {
            _room.update { it.copy(closed = tr("Conecta la app con tu servidor para usar las salas")) }
            null
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            retry = 0
            _room.update { it.copy(connected = true) }
            pinger?.cancel()
            pinger = scope.launch { while (isActive) { ping(); delay(PING_MS) } }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handle(JsonParser.parseString(text).asJsonObject) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(0, "")
    }

    private fun lost(code: Int, reason: String) {
        pinger?.cancel()
        _room.update { it.copy(connected = false) }
        if (stopped) return
        // The server refused the room (unknown, rooms off): do not keep knocking.
        if (code == POLICY) { _room.update { it.copy(closed = tr(reason.ifBlank { "No se pudo entrar en la sala" })) }; return }
        retry = (retry + 1).coerceAtMost(MAX_RETRY)
        scope.launch { delay(minOf(MAX_WAIT_MS, BASE_WAIT_MS shl retry)); open() }
    }

    private fun ping() { pingSent = System.currentTimeMillis(); send(mapOf("t" to "ping")) }

    private fun clockFrom(serverTime: Long, sentAt: Long) {
        val now = System.currentTimeMillis()
        val rtt = if (sentAt > 0) now - sentAt else 0
        offset = serverTime + rtt / 2 - now
    }

    private fun handle(m: JsonObject) {
        when (m.get("t")?.asString) {
            "welcome" -> {
                clockFrom(m.get("now").asLong, 0)
                val state = m.state()
                _room.update {
                    it.copy(
                        name = m.getAsJsonObject("room")?.get("name")?.asString.orEmpty(), you = m.get("you")?.asLong,
                        state = state, waiting = m.waiting(), messages = m.getAsJsonArray("history")?.mapNotNull { e -> chat(e.asJsonObject) }.orEmpty(),
                        reactions = m.getAsJsonArray("reactions")?.map { e -> e.asString }.orEmpty()
                    ).withMembers(m)
                }
                state?.let { _states.tryEmit(StateEvent(it, byServer = false)) }
            }
            "pong" -> clockFrom(m.get("now").asLong, pingSent)
            "members" -> _room.update { it.withMembers(m) }
            "state" -> {
                val state = m.state()
                _room.update { it.copy(state = state, waiting = m.waiting()) }
                state?.let { _states.tryEmit(StateEvent(it, byServer = m.get("by")?.isJsonNull != false)) }
            }
            "chat" -> chat(m.getAsJsonObject("msg"))?.let { line ->
                _room.update { it.copy(messages = (it.messages + line).takeLast(MAX_MESSAGES), unread = it.unread + 1) }
            }
            "react" -> m.get("emoji")?.asString?.let { _reactions.tryEmit(it) }
            "notice" -> m.get("text")?.asString?.let { text ->
                _room.update { it.copy(messages = (it.messages + ChatLine(-1, "", text, false)).takeLast(MAX_MESSAGES)) }
            }
        }
    }

    private fun JsonObject.state(): PartyState? =
        get("state")?.takeIf { it.isJsonObject }?.let { runCatching { gson.fromJson(it, PartyState::class.java) }.getOrNull() }

    private fun JsonObject.waiting(): List<String> = getAsJsonArray("waiting")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()

    private fun Room.withMembers(m: JsonObject) = copy(
        members = m.getAsJsonArray("members")?.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            Member(o.get("id")?.asLong ?: return@mapNotNull null, o.get("name")?.asString.orEmpty())
        } ?: members,
        host = m.get("host")?.takeIf { !it.isJsonNull }?.asLong ?: host,
        everyoneControls = m.get("everyoneControls")?.asBoolean ?: everyoneControls
    )

    private fun chat(o: JsonObject?): ChatLine? = o?.let {
        ChatLine(it.get("id")?.asLong ?: 0, it.get("from")?.asString.orEmpty(), it.get("text")?.asString.orEmpty(), it.get("spoiler")?.asBoolean == true)
    }

    private fun send(payload: Any) { socket?.send(gson.toJson(payload)) }

    fun sendState(state: PartyState) = send(mapOf("t" to "state", "state" to state))
    fun loading() = send(mapOf("t" to "loading"))
    fun ready() = send(mapOf("t" to "ready"))
    fun chat(text: String, spoiler: Boolean = false) = send(mapOf("t" to "chat", "text" to text.take(MAX_CHAT), "spoiler" to spoiler))
    fun react(emoji: String) = send(mapOf("t" to "react", "emoji" to emoji))
    fun seen() = _room.update { it.copy(unread = 0) }

    fun close() {
        stopped = true
        pinger?.cancel()
        socket?.close(NORMAL, null)
        scope.cancel()
    }

    private companion object {
        const val PING_MS = 15_000L
        const val POLICY = 1008
        const val NORMAL = 1000
        const val MAX_RETRY = 6
        const val BASE_WAIT_MS = 500L
        const val MAX_WAIT_MS = 30_000L
        const val MAX_MESSAGES = 200
        const val MAX_CHAT = 500
    }
}
