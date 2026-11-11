package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.social.ChatGuard
import com.nelson.yomukai.data.social.PartyState
import com.nelson.yomukai.data.social.PartySync
import com.nelson.yomukai.data.social.RoomCodes
import com.nelson.yomukai.data.social.RoomQueue
import com.nelson.yomukai.data.social.ShareCard
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Watch parties. Each user has a persistent room (short code, link /sala/CODE) and can open temporary ones. A room
 * lives in memory while someone is in it; the database keeps the room, its chat (7 days) and nothing about the
 * video itself. The host has authority over what plays; everyone works out the position from the last state and the
 * server's clock (PartySync). Video is never relayed here: each client plays its own stream (through /api/stream
 * only when the stream is tied to an address), so a room costs the VPS a few small messages a second at most.
 */
class Rooms(
    private val db: Database,
    private val events: Events,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Told when a room starts playing something (for the group's Discord). */
    private val onOpen: (who: String, what: String) -> Unit = { _, _ -> },
    /** Whether a source plays anime (only episodes go in a queue). */
    private val isAnime: (String) -> Boolean = { true },
    /** Whether shared cards are on (F4.3); off, a card in the chat is dropped and the text goes alone. */
    private val sharingOn: suspend () -> Boolean = { true }
) {
    private val log = LoggerFactory.getLogger("Rooms")
    private val gson = Gson()
    private val live = ConcurrentHashMap<String, LiveRoom>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guard = ChatGuard()
    private val ids = AtomicLong()

    /** How often a live socket re-checks its session (shorter in tests). */
    @Volatile
    var sessionCheckMs = SESSION_CHECK_MS

    /** [open]: listed for everyone as a common room (F4.1). [lasting]: an admin's common room that never expires. */
    data class RoomRow(
        val code: String, val ownerId: Long, val ownerName: String, val name: String, val persistent: Boolean,
        val open: Boolean = false, val lasting: Boolean = false
    )

    private class Member(val id: Long, val user: User, val session: DefaultWebSocketServerSession, val joinedAt: Long)

    private class LiveRoom(val row: RoomRow) {
        val lock = Mutex()
        val members = LinkedHashMap<Long, Member>()
        var host: Long? = null
        var everyoneControls = false
        var state: PartyState? = null
        val loading = HashSet<Long>()
        var waitingSince: Long? = null
        var resumeAfterWait = false
        var waitJob: Job? = null
        val hostedBy = HashSet<Long>()
        val pauses = HashMap<Long, Int>()
        var open = row.open
        var queue: List<RoomQueue.Item> = emptyList()
        val skipVotes = HashSet<Long>()
    }

    // ---------- Rooms in the database ----------

    suspend fun mine(user: User): RoomRow {
        db.tx { queryOne("SELECT r.*, u.name AS owner_name FROM rooms r JOIN users u ON u.id = r.owner_id WHERE owner_id = ? AND persistent = 1 ORDER BY created_at LIMIT 1", user.id) { row() } }
            ?.let { return it }
        return create(user, persistent = true, name = "Sala de ${user.name}")
    }

    /**
     * A new room. [open] lists it for everyone (a common room); [lasting] (admins only) keeps a common room for good
     * instead of letting it go after a day without use.
     */
    suspend fun create(user: User, persistent: Boolean, name: String, open: Boolean = false, lasting: Boolean = false): RoomRow {
        require(!user.isGuest) { "Los invitados no pueden crear salas" }
        require(!lasting || user.isAdmin) { "Solo un administrador crea salas comunes fijas" }
        val temp = db.tx { queryOne("SELECT COUNT(*) FROM rooms WHERE owner_id = ? AND persistent = 0", user.id) { getInt(1) } } ?: 0
        require(persistent || lasting || temp < MAX_TEMP_ROOMS) { "Ya tienes $MAX_TEMP_ROOMS salas temporales abiertas" }
        val clean = ChatGuard.clean(name).take(60).ifBlank { if (open) "Sala común de ${user.name}" else "Sala de ${user.name}" }
        // persistent: 0 temporary, 1 the user's own room, 2 an admin's lasting common room.
        val kind = when { lasting -> 2; persistent -> 1; else -> 0 }
        repeat(10) {
            val code = RoomCodes.generate()
            val inserted = runCatching {
                db.tx {
                    update(
                        "INSERT INTO rooms(code, owner_id, name, persistent, created_at, last_active, open) VALUES(?,?,?,?,?,?,?)",
                        code, user.id, clean, kind, clock(), clock(), open || lasting
                    )
                }
            }.isSuccess
            if (inserted) return RoomRow(code, user.id, user.name, clean, kind != 0, open || lasting, lasting)
        }
        error("No se pudo crear la sala, prueba otra vez")
    }

    suspend fun get(code: String): RoomRow? =
        db.tx { queryOne("SELECT r.*, u.name AS owner_name FROM rooms r JOIN users u ON u.id = r.owner_id WHERE code = ?", code) { row() } }

    private fun java.sql.ResultSet.row() = RoomRow(
        getString("code"), getLong("owner_id"), getString("owner_name"), getString("name"), getInt("persistent") != 0,
        getInt("open") == 1, getInt("persistent") == 2
    )

    /** The owner (or an admin) opens a room to everyone or closes it again. */
    suspend fun setOpen(user: User, code: String, open: Boolean): RoomRow {
        val row = get(code) ?: throw NoSuchElementException("Esa sala no existe")
        require(row.ownerId == user.id || user.isAdmin) { "Solo quien creó la sala puede abrirla o cerrarla" }
        db.tx { update("UPDATE rooms SET open = ? WHERE code = ?", open, code) }
        live[code]?.let { room -> room.lock.withLock { room.open = open; broadcast(room, roomInfo(room)) } }
        return row.copy(open = open)
    }

    /** Whoever made a room that is not their own personal one (or an admin) removes it; its queue and chat go with it. */
    suspend fun delete(user: User, code: String) {
        val row = get(code) ?: throw NoSuchElementException("Esa sala no existe")
        require(row.ownerId == user.id || user.isAdmin) { "Solo quien creó la sala puede borrarla" }
        require(!row.persistent || row.lasting) { "Tu sala personal no se borra" }
        db.tx { update("DELETE FROM rooms WHERE code = ?", code) }
        live.remove(code)?.let { room ->
            room.members.values.forEach { runCatching { it.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "La sala se ha cerrado")) } }
        }
    }

    /** Common rooms (open to everyone), with whoever is in them, what plays and what comes next. */
    suspend fun openRooms(): List<Map<String, Any?>> {
        val rows = db.tx {
            query(
                """SELECT r.*, u.name AS owner_name FROM rooms r JOIN users u ON u.id = r.owner_id WHERE r.open = 1
                   ORDER BY r.persistent = 2 DESC, r.last_active DESC LIMIT 50"""
            ) { row() }
        }
        val queued = db.tx { query("SELECT room_code, COUNT(*) FROM room_queue GROUP BY room_code") { getString(1) to getInt(2) } }.toMap()
        return rows.map { row ->
            val r = live[row.code]
            val nextUp = r?.queue?.firstOrNull() ?: if (r == null && (queued[row.code] ?: 0) > 0) loadQueue(row.code).firstOrNull() else null
            mapOf(
                "code" to row.code, "name" to row.name, "owner" to row.ownerName, "lasting" to row.lasting,
                "members" to r?.members?.values?.map { it.user.name }?.distinct().orEmpty(),
                "title" to r?.state?.title, "episode" to r?.state?.episode, "playing" to r?.state?.playing,
                "queued" to (r?.queue?.size ?: queued[row.code] ?: 0),
                "next" to nextUp?.let { mapOf("title" to it.title, "episode" to it.episode, "cover" to it.cover) }
            )
        }
    }

    private suspend fun loadQueue(code: String): List<RoomQueue.Item> = db.tx {
        query("SELECT * FROM room_queue WHERE room_code = ? ORDER BY position, id", code) {
            RoomQueue.Item(
                getLong("id"), getString("source_id"), getString("media_id"), getString("chapter_id"), getString("title"),
                getString("episode"), getString("cover"), getLong("added_by"), getString("added_by_name")
            )
        }
    }

    /** Rooms with people in them right now: who is there and what plays (for the home row). */
    fun active(): List<Map<String, Any?>> = live.values.filter { it.members.isNotEmpty() }.map { r ->
        mapOf(
            "code" to r.row.code, "name" to r.row.name, "owner" to r.row.ownerName, "open" to r.open,
            "members" to r.members.values.map { it.user.name }.distinct(),
            "host" to r.members.values.firstOrNull { it.user.id == r.host }?.user?.name,
            "title" to r.state?.title, "episode" to r.state?.episode, "playing" to r.state?.playing
        )
    }

    /** Old chat (7 days) and temporary rooms nobody used for a day go away. */
    suspend fun cleanup() {
        val now = clock()
        db.tx {
            update("DELETE FROM room_messages WHERE created_at < ?", now - CHAT_KEEP_MS)
            update("DELETE FROM rooms WHERE persistent = 0 AND last_active < ?", now - TEMP_ROOM_KEEP_MS)
        }
    }

    // ---------- Live connection ----------

    /**
     * One person in a room until the socket closes. [stillValid] is asked every [SESSION_CHECK_MS]: a session closed
     * from the sessions list, a logout or a suspended account ends the socket too, not only new requests.
     */
    suspend fun connect(session: DefaultWebSocketServerSession, user: User, code: String, stillValid: suspend () -> Boolean = { true }) {
        val row = get(code) ?: return session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Sala desconocida"))
        val room = live.computeIfAbsent(code) { LiveRoom(row) }
        val me = Member(ids.incrementAndGet(), user, session, clock())
        room.lock.withLock {
            if (room.members.isEmpty()) room.queue = loadQueue(code)
            val firstOfUser = room.members.values.none { it.user.id == user.id }
            room.members[me.id] = me
            if (room.host == null || room.members.values.none { it.user.id == room.host }) room.host = PartySync.nextHost(null, userIds(room), null)
            send(me, welcome(room, me))
            if (firstOfUser) {
                broadcast(room, members(room))
                broadcast(room, queueMessage(room))
            }
            lateJoiner(room, user)
        }
        db.tx { update("UPDATE rooms SET last_active = ? WHERE code = ?", clock(), code) }
        val limiter = FrameLimiter()
        val watchdog = session.launch {
            while (true) {
                delay(sessionCheckMs)
                if (!runCatching { stillValid() }.getOrDefault(false)) {
                    session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "La sesión ya no es válida"))
                    break
                }
            }
        }
        try {
            for (frame in session.incoming) {
                val allowed = limiter.allow(clock())
                if (limiter.abusive) return session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Demasiados mensajes"))
                val msg = (frame as? Frame.Text)?.takeIf { allowed }?.readText()?.takeIf { it.length <= MAX_FRAME }
                    ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                if (msg != null) runCatching { handle(room, me, msg) }.onFailure { log.debug("Mensaje de sala no válido: {}", it.message) }
            }
        } finally {
            watchdog.cancel()
            leave(room, me)
        }
    }

    private suspend fun handle(room: LiveRoom, me: Member, msg: JsonObject) {
        when (msg.get("t")?.asString) {
            "ping" -> send(me, mapOf("t" to "pong", "now" to clock()))
            "state" -> room.lock.withLock { setState(room, me, msg) }
            "loading" -> room.lock.withLock { loading(room, me) }
            "ready" -> room.lock.withLock { ready(room, me) }
            "chat" -> chat(room, me, msg)
            "react" -> react(room, me, msg.get("emoji")?.asString.orEmpty())
            "host" -> room.lock.withLock { giveHost(room, me, msg.get("userId")?.asLong) }
            "queue_add" -> room.lock.withLock { queueAdd(room, me, msg.get("item")?.takeIf { it.isJsonObject }?.asJsonObject) }
            "queue_remove" -> room.lock.withLock { queueRemove(room, me, msg.get("id")?.asLong) }
            "queue_move" -> room.lock.withLock { queueMove(room, me, msg.get("id")?.asLong, msg.get("to")?.asInt) }
            "queue_play" -> room.lock.withLock {
                if (canControl(room, me)) room.queue.firstOrNull { it.id == msg.get("id")?.asLong }?.let { advance(room, it, by = me.user.name) }
            }
            "skip" -> room.lock.withLock { skipVote(room, me) }
            "ended" -> room.lock.withLock { ended(room, me, msg.get("chapterId")?.asString) }
            "allow" -> room.lock.withLock {
                if (me.user.id == room.host) {
                    room.everyoneControls = msg.get("all")?.asBoolean == true
                    broadcast(room, members(room))
                }
            }
        }
    }

    private suspend fun setState(room: LiveRoom, me: Member, msg: JsonObject) {
        if (!PartySync.canControl(me.user.id, room.host, room.everyoneControls)) return
        val s = msg.getAsJsonObject("state") ?: return
        fun str(name: String, max: Int) = s.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().take(max)
        val next = PartyState(
            sourceId = str("sourceId", 60).ifBlank { return }, mediaId = str("mediaId", 2_000).ifBlank { return },
            chapterId = str("chapterId", 2_000).ifBlank { return }, title = str("title", 200), episode = str("episode", 100),
            server = str("server", 60),
            position = (s.get("position")?.asDouble ?: 0.0).coerceIn(0.0, MAX_POSITION),
            playing = s.get("playing")?.asBoolean == true,
            speed = (s.get("speed")?.asDouble ?: 1.0).coerceIn(0.25, 3.0),
            at = clock()
        )
        val previous = room.state
        val pausedHere = previous?.playing == true && previous.chapterId == next.chapterId
        if (pausedHere && !next.playing) {
            room.pauses.merge(me.user.id, 1, Int::plus)
        }
        // A new episode: everyone loads it; the party starts when they are ready (or after the wait).
        if (previous == null || previous.chapterId != next.chapterId) {
            room.loading.clear()
            room.waitingSince = null
            if (room.skipVotes.isNotEmpty()) {
                room.skipVotes.clear()
                broadcast(room, queueMessage(room))
            }
            if (room.hostedBy.add(me.user.id)) events.record(me.user.id, "room_hosted", mapOf("room" to room.row.code))
            if (previous == null) onOpen(me.user.name, ChatGuard.clean(listOf(next.title, next.episode).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { room.row.name }).take(200))
        }
        room.state = if (room.loading.isNotEmpty() && next.playing) {
            room.resumeAfterWait = true
            next.copy(playing = false)
        } else next
        broadcast(room, mapOf("t" to "state", "state" to room.state, "by" to me.user.name, "waiting" to waitingNames(room)))
    }

    private suspend fun loading(room: LiveRoom, me: Member) {
        val state = room.state ?: return
        val first = room.loading.isEmpty()
        room.loading.add(me.id)
        if (state.playing) {
            room.state = PartySync.pausedAt(state, clock())
            room.resumeAfterWait = true
            events.record(me.user.id, "room_buffering", emptyMap())
        }
        if (first) {
            room.waitingSince = clock()
            room.waitJob?.cancel()
            room.waitJob = scope.launch {
                delay(PartySync.READY_TIMEOUT_MS)
                room.lock.withLock { maybeResume(room) }
            }
        }
        broadcast(room, mapOf("t" to "state", "state" to room.state, "by" to null, "waiting" to waitingNames(room)))
    }

    private suspend fun ready(room: LiveRoom, me: Member) {
        if (room.loading.remove(me.id)) maybeResume(room)
    }

    private suspend fun maybeResume(room: LiveRoom) {
        val state = room.state ?: return
        if (!PartySync.canResume(room.loading, room.waitingSince, clock())) return
        room.loading.clear()
        room.waitingSince = null
        room.waitJob?.cancel()
        if (room.resumeAfterWait) {
            room.resumeAfterWait = false
            room.state = PartySync.resumedAt(state, clock())
        }
        broadcast(room, mapOf("t" to "state", "state" to room.state, "by" to null, "waiting" to emptyList<String>()))
    }

    private suspend fun giveHost(room: LiveRoom, me: Member, to: Long?) {
        if (me.user.id != room.host || to == null || room.members.values.none { it.user.id == to }) return
        room.host = to
        broadcast(room, members(room))
    }

    private suspend fun chat(room: LiveRoom, me: Member, msg: JsonObject) {
        val text = ChatGuard.clean(msg.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
        val card = msg.get("card")?.let(::cardFrom)?.takeIf { sharingOn() }
        if (text.isBlank() && card == null) return
        val muted = db.tx { queryOne("SELECT muted_until FROM users WHERE id = ?", me.user.id) { getLong(1) } } ?: 0
        if (muted > clock()) return send(me, mapOf("t" to "notice", "text" to "Estás silenciado por un administrador"))
        if (!guard.allowMessage(me.user.id, clock())) return send(me, mapOf("t" to "notice", "text" to "Más despacio: espera unos segundos"))
        val spoiler = msg.get("spoiler")?.asBoolean == true
        val now = clock()
        val id = db.tx {
            update(
                "INSERT INTO room_messages(room_code, user_id, user_name, kind, text, spoiler, created_at, card) VALUES(?,?,?,?,?,?,?,?)",
                room.row.code, me.user.id, me.user.name, "msg", text, spoiler, now, card?.let(gson::toJson)
            )
            lastId()
        }
        events.record(me.user.id, "room_message", emptyMap())
        if (card != null) events.record(me.user.id, "shared", mapOf("to" to "room", "kind" to card.kind, "spoiler" to card.spoiler))
        val mentions = ChatGuard.mentions(text)
        broadcast(room, mapOf("t" to "chat", "msg" to ChatMessage(id, me.user.name, text, spoiler, now, mentions, card)))
    }

    private suspend fun react(room: LiveRoom, me: Member, emoji: String) {
        if (emoji !in ChatGuard.REACTIONS || !guard.allowReaction(me.user.id, clock())) return
        events.record(me.user.id, "reaction", mapOf("chapter" to room.state?.chapterId))
        broadcast(room, mapOf("t" to "react", "emoji" to emoji, "from" to me.user.name))
    }

    private suspend fun leave(room: LiveRoom, me: Member) {
        room.lock.withLock {
            room.members.remove(me.id)
            room.loading.remove(me.id)
            val stillHere = room.members.values.any { it.user.id == me.user.id }
            if (!stillHere) {
                if (room.members.size + 1 >= 3) mostPauses(room)
                room.host = PartySync.nextHost(room.host, userIds(room) + me.user.id, me.user.id)
            }
            if (room.members.isEmpty()) {
                room.waitJob?.cancel()
                live.remove(room.row.code, room)
            } else {
                maybeResume(room)
                if (!stillHere) {
                    room.skipVotes.remove(me.user.id)
                    broadcast(room, members(room))
                    broadcast(room, queueMessage(room))
                }
            }
        }
    }

    /** "Pausa para mear": whoever paused the most in a room of three or more, when they leave. */
    private suspend fun mostPauses(room: LiveRoom) {
        val (user, count) = room.pauses.maxByOrNull { it.value } ?: return
        if (count >= 3) events.record(user, "room_most_pauses", mapOf("room" to room.row.code))
    }

    private suspend fun lateJoiner(room: LiveRoom, user: User) {
        val s = room.state ?: return
        // Joined past half an episode (taken as ~24 minutes: the server does not know each one's length).
        if (PartySync.expectedPosition(s, clock()) > EPISODE_GUESS_S / 2) events.record(user.id, "room_join_late", emptyMap())
    }

    private fun userIds(room: LiveRoom): List<Long> = room.members.values.map { it.user.id }.distinct()
    private fun waitingNames(room: LiveRoom): List<String> = room.loading.mapNotNull { room.members[it]?.user?.name }.distinct()

    data class ChatMessage(
        val id: Long, val from: String, val text: String, val spoiler: Boolean, val at: Long, val mentions: List<String>, val card: ShareCard? = null
    )

    // ---------- Queue (F4.1) ----------

    private fun canControl(room: LiveRoom, me: Member) = PartySync.canControl(me.user.id, room.host, room.everyoneControls)

    private fun queueMessage(room: LiveRoom) = mapOf(
        "t" to "queue", "items" to room.queue,
        "skip" to mapOf("votes" to room.skipVotes.toList(), "needed" to RoomQueue.votesToSkip(userIds(room).size))
    )

    private fun roomInfo(room: LiveRoom) = mapOf(
        "t" to "room",
        "room" to mapOf("code" to room.row.code, "name" to room.row.name, "owner" to room.row.ownerName, "persistent" to room.row.persistent, "open" to room.open)
    )

    private suspend fun queueAdd(room: LiveRoom, me: Member, raw: JsonObject?) {
        if (me.user.isGuest) return send(me, mapOf("t" to "notice", "text" to "Los invitados no añaden a la cola"))
        fun str(name: String) = raw?.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val card = ShareCard.sanitize(
            ShareCard("episode", str("sourceId"), str("mediaId"), str("chapterId"), str("title"), cover = str("cover"), label = str("episode"))
        ) ?: return send(me, mapOf("t" to "notice", "text" to "Ese episodio no se puede poner en la cola"))
        if (!isAnime(card.sourceId)) return send(me, mapOf("t" to "notice", "text" to "En la cola solo van episodios de anime"))
        val draft = RoomQueue.Item(0, card.sourceId, card.mediaId, card.chapterId, card.title, card.label, card.cover, me.user.id, me.user.name)
        RoomQueue.refuseAdd(room.queue, draft)?.let { return send(me, mapOf("t" to "notice", "text" to it)) }
        val id = db.tx {
            update(
                """INSERT INTO room_queue(room_code, position, source_id, media_id, chapter_id, title, episode, cover, added_by, added_by_name, created_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                room.row.code, room.queue.size, draft.sourceId, draft.mediaId, draft.chapterId, draft.title, draft.episode, draft.cover,
                draft.addedBy, draft.addedByName, clock()
            )
            lastId()
        }
        room.queue = room.queue + draft.copy(id = id)
        db.tx { update("UPDATE rooms SET last_active = ? WHERE code = ?", clock(), room.row.code) }
        events.record(me.user.id, "room_queued", emptyMap())
        broadcast(room, queueMessage(room))
    }

    private suspend fun queueRemove(room: LiveRoom, me: Member, id: Long?) {
        val item = room.queue.firstOrNull { it.id == id } ?: return
        if (!RoomQueue.canEdit(me.user.id, item, canControl(room, me))) return
        room.queue = room.queue - item
        db.tx { update("DELETE FROM room_queue WHERE id = ? AND room_code = ?", item.id, room.row.code) }
        broadcast(room, queueMessage(room))
    }

    private suspend fun queueMove(room: LiveRoom, me: Member, id: Long?, to: Int?) {
        if (id == null || to == null || !canControl(room, me)) return
        val moved = RoomQueue.move(room.queue, id, to)
        if (moved == room.queue) return
        room.queue = moved
        savePositions(room)
        broadcast(room, queueMessage(room))
    }

    private suspend fun savePositions(room: LiveRoom) = db.tx {
        room.queue.forEachIndexed { i, item -> update("UPDATE room_queue SET position = ? WHERE id = ?", i, item.id) }
    }

    /** A vote (or taking it back) to skip what plays; enough votes move on. */
    private suspend fun skipVote(room: LiveRoom, me: Member) {
        val state = room.state ?: return
        if (!room.skipVotes.add(me.user.id)) room.skipVotes.remove(me.user.id)
        if (RoomQueue.skipPasses(room.skipVotes, userIds(room).toSet())) {
            // Everyone who voted for it counts it (for «La democracia ha hablado»).
            val voters = room.skipVotes.filter { it in userIds(room) }
            room.skipVotes.clear()
            voters.forEach { events.record(it, "room_skip", emptyMap()) }
            val next = room.queue.firstOrNull()
            if (next != null) advance(room, next, by = null)
            // Nothing queued: whoever drives goes on to the next episode of the same anime.
            else broadcast(room, mapOf("t" to "skip", "chapterId" to state.chapterId))
        }
        broadcast(room, queueMessage(room))
    }

    /** The episode finished: with something queued the room moves on by itself (once, whoever says it first). */
    private suspend fun ended(room: LiveRoom, me: Member, chapterId: String?) {
        val state = room.state ?: return
        if (chapterId != state.chapterId || !canControl(room, me)) return
        room.queue.firstOrNull()?.let { advance(room, it, by = null) }
    }

    /** Plays [item] for everyone from the start and takes it off the queue. */
    private suspend fun advance(room: LiveRoom, item: RoomQueue.Item, by: String?) {
        room.queue = room.queue.filter { it.id != item.id }
        db.tx { update("DELETE FROM room_queue WHERE id = ?", item.id) }
        savePositions(room)
        room.loading.clear()
        room.waitingSince = null
        room.waitJob?.cancel()
        room.resumeAfterWait = false
        room.skipVotes.clear()
        room.state = PartyState(item.sourceId, item.mediaId, item.chapterId, item.title, item.episode, position = 0.0, playing = true, at = clock())
        broadcast(room, mapOf("t" to "state", "state" to room.state, "by" to by, "waiting" to emptyList<String>(), "advance" to true))
        broadcast(room, queueMessage(room))
    }

    private fun members(room: LiveRoom) = mapOf(
        "t" to "members",
        "members" to room.members.values.distinctBy { it.user.id }.map { mapOf("id" to it.user.id, "name" to it.user.name) },
        "host" to room.host, "everyoneControls" to room.everyoneControls
    )

    private suspend fun welcome(room: LiveRoom, me: Member): Map<String, Any?> {
        val history = db.tx {
            query(
                "SELECT * FROM room_messages WHERE room_code = ? AND created_at > ? ORDER BY id DESC LIMIT 50",
                room.row.code, clock() - CHAT_KEEP_MS
            ) { ChatMessage(getLong("id"), getString("user_name"), getString("text"), getInt("spoiler") == 1, getLong("created_at"), emptyList(), cardFromJson(getString("card"))) }
        }.reversed()
        return members(room) + mapOf(
            "t" to "welcome", "you" to me.user.id, "now" to clock(),
            "room" to mapOf("code" to room.row.code, "name" to room.row.name, "owner" to room.row.ownerName, "persistent" to room.row.persistent, "open" to room.open),
            "queue" to room.queue, "skip" to mapOf("votes" to room.skipVotes.toList(), "needed" to RoomQueue.votesToSkip(userIds(room).size)),
            "state" to room.state, "waiting" to waitingNames(room), "history" to history, "reactions" to ChatGuard.REACTIONS
        )
    }

    private fun send(m: Member, payload: Any) {
        m.session.outgoing.trySend(Frame.Text(gson.toJson(payload)))
    }

    private fun broadcast(room: LiveRoom, payload: Any) {
        val text = gson.toJson(payload)
        room.members.values.forEach { it.session.outgoing.trySend(Frame.Text(text)) }
    }

    /** Per connection: a burst is fine, a flood is dropped, and a client that keeps flooding is disconnected. */
    private class FrameLimiter {
        private var windowStart = 0L
        private var count = 0
        private var strikes = 0
        val abusive get() = strikes > 5

        fun allow(now: Long): Boolean {
            if (now - windowStart > 5_000) {
                windowStart = now
                count = 0
            }
            count++
            if (count == MAX_FRAMES_PER_WINDOW + 1) strikes++
            return count <= MAX_FRAMES_PER_WINDOW
        }
    }

    companion object {
        const val MAX_TEMP_ROOMS = 3
        const val CHAT_KEEP_MS = 7 * 24 * 3_600_000L
        const val TEMP_ROOM_KEEP_MS = 24 * 3_600_000L
        const val MAX_FRAME = 8_192
        const val MAX_FRAMES_PER_WINDOW = 60
        const val MAX_POSITION = 24 * 3_600.0
        const val EPISODE_GUESS_S = 24 * 60.0
        const val SESSION_CHECK_MS = 30_000L
    }
}

/** A small log of what users do, for the feed and the achievements (kept a year). */
class Events(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    private val gson = Gson()

    /** Called after each event is stored (the achievements engine listens here). */
    @Volatile
    var onEvent: suspend (userId: Long, kind: String) -> Unit = { _, _ -> }

    suspend fun record(userId: Long, kind: String, data: Map<String, Any?>) {
        db.tx { update("INSERT INTO events(user_id, kind, data, created_at) VALUES(?,?,?,?)", userId, kind.take(40), gson.toJson(data).take(2_000), clock()) }
        runCatching { onEvent(userId, kind) }
    }

    suspend fun count(userId: Long, kind: String, since: Long = 0): Int =
        db.tx { queryOne("SELECT COUNT(*) FROM events WHERE user_id = ? AND kind = ? AND created_at >= ?", userId, kind, since) { getInt(1) } } ?: 0

    suspend fun cleanup() = db.tx { update("DELETE FROM events WHERE created_at < ?", clock() - 365L * 24 * 3_600_000) }
}

/** The browser always says where a page came from; a page on another site must not open a socket with our cookie. */
fun sameOrigin(call: ApplicationCall): Boolean {
    val origin = call.request.headers["Origin"] ?: return true // apps, not browsers
    val host = call.request.headers["X-Forwarded-Host"] ?: call.request.headers["Host"] ?: return false
    return runCatching { URI(origin).authority.equals(host, ignoreCase = true) }.getOrDefault(false)
}

fun Route.roomRoutes(s: Services, addressOf: (ApplicationCall) -> String) {
    suspend fun ApplicationCall.roomsOn(): Boolean {
        if (s.features.isOn("rooms")) return true
        respond(HttpStatusCode.NotFound, mapOf("error" to "Las salas están desactivadas"))
        return false
    }
    get("/rooms") { if (call.roomsOn()) call.respond(s.rooms.active()) }
    get("/rooms/mine") { if (call.roomsOn()) call.respond(s.rooms.mine(call.user)) }
    get("/rooms/open") { if (call.roomsOn()) call.respond(if (s.features.isOn("open_rooms")) s.rooms.openRooms() else emptyList()) }
    post("/rooms") {
        if (!call.roomsOn()) return@post
        val body = call.jsonBody(1_024)
        val open = body.get("open")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        require(!open || s.features.isOn("open_rooms")) { "Las salas comunes están desactivadas" }
        val lasting = body.get("lasting")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        call.respond(s.rooms.create(call.user, persistent = false, name = body.text("name", 60), open = open || lasting, lasting = lasting))
    }
    post("/rooms/{code}/open") {
        if (!call.roomsOn()) return@post
        val code = RoomCodes.normalize(call.parameters["code"].orEmpty()) ?: throw NoSuchElementException("Código no válido")
        val open = call.jsonBody(256).get("open")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        require(!open || s.features.isOn("open_rooms")) { "Las salas comunes están desactivadas" }
        call.respond(s.rooms.setOpen(call.user, code, open))
    }
    delete("/rooms/{code}") {
        if (!call.roomsOn()) return@delete
        val code = RoomCodes.normalize(call.parameters["code"].orEmpty()) ?: throw NoSuchElementException("Código no válido")
        s.rooms.delete(call.user, code)
        call.respond(mapOf("ok" to true))
    }
    get("/rooms/{code}") {
        if (!call.roomsOn()) return@get
        val code = RoomCodes.normalize(call.parameters["code"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Código no válido"))
        call.respond(s.rooms.get(code) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Esa sala no existe")))
    }
    webSocket("/rooms/{code}/ws") {
        val code = RoomCodes.normalize(call.parameters["code"].orEmpty())
        if (code == null || !sameOrigin(call) || !s.features.isOn("rooms")) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No permitido"))
        }
        val token = call.request.cookies[Auth.COOKIE]
        val userId = call.user.id
        val address = addressOf(call)
        s.rooms.connect(this, call.user, code) {
            s.auth.userFor(token, address)?.let { it.id == userId && it.suspendedAt == null } == true
        }
    }
}
