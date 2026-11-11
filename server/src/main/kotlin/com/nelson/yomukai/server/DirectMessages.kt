package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.nelson.yomukai.data.social.ChatGuard
import com.nelson.yomukai.data.social.DmRules
import com.nelson.yomukai.data.social.ShareCard
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.concurrent.ConcurrentHashMap

/** A card in a request body ({ kind, sourceId, ... }), checked by [ShareCard.sanitize]; null when absent or invalid. */
internal fun cardFrom(element: JsonElement?): ShareCard? {
    val o = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun s(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    return ShareCard.sanitize(
        ShareCard(
            kind = s("kind"), sourceId = s("sourceId"), mediaId = s("mediaId"), chapterId = s("chapterId"), title = s("title"),
            cover = s("cover"), type = s("type"), lang = s("lang"),
            number = o.get("number")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble,
            label = s("label"), spoiler = o.get("spoiler")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            workKey = s("workKey")
        )
    )
}

internal fun cardFromJson(text: String?): ShareCard? =
    text?.takeIf { it.isNotBlank() }?.let { runCatching { Gson().fromJson(it, ShareCard::class.java) }.getOrNull() }?.let(ShareCard::sanitize)

/**
 * Direct messages, one to one (F4.2). Only text and shared cards (never images or HTML), only between members (never
 * guests), and every request checks that the caller is one of the two people. Live delivery goes through each user's
 * notification stream (SSE): new message, typing, seen, deleted. The bell and ntfy hear only of the first unread
 * message of a conversation, never of each one, and nothing from a muted conversation.
 */
class DirectMessages(
    private val db: Database,
    private val notifications: Notifications,
    private val events: Events,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()
    private val guard = ChatGuard(maxMessages = 8, windowMs = 10_000)
    private val typing = ConcurrentHashMap<Pair<Long, Long>, Long>()

    data class Peer(val id: Long, val name: String, val avatar: String?)

    data class Message(val id: Long, val from: String, val mine: Boolean, val text: String, val card: ShareCard?, val at: Long)

    data class Conversation(
        val peer: Peer, val last: Message?, val unread: Int, val muted: Boolean, val blocked: Boolean, val lastAt: Long
    )

    private fun requireMember(user: User) = require(!user.isGuest) { "Los invitados no tienen mensajes directos" }

    private fun avatarOf(name: String, at: Long) =
        at.takeIf { it > 0 }?.let { "/api/users/${java.net.URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")}/avatar?v=$it" }

    /** Someone to talk to: a member who is not a guest, not suspended and not you. Unknown or not allowed look the same. */
    suspend fun peer(me: User, name: String): Peer {
        val p = db.tx {
            queryOne("SELECT id, name, avatar_at FROM users WHERE name = ? COLLATE NOCASE AND is_guest = 0 AND suspended_at IS NULL", name.take(60)) {
                Peer(getLong(1), getString(2), avatarOf(getString(2), getLong(3)))
            }
        }
        if (p == null || p.id == me.id) throw NoSuchElementException("No existe esa persona")
        return p
    }

    private fun java.sql.ResultSet.message(me: Long) = Message(
        getLong("id"), getString("from_name"), getLong("from_id") == me, getString("text"), cardFromJson(getString("card")), getLong("created_at")
    )

    // ---------- Conversations ----------

    suspend fun conversations(me: User): List<Conversation> {
        requireMember(me)
        return db.tx {
            // The last message of each conversation (either side), newest first.
            val lasts = query(
                """SELECT m.*, u.name AS from_name, CASE WHEN m.from_id = ? THEN m.to_id ELSE m.from_id END AS peer
                   FROM dm_messages m JOIN users u ON u.id = m.from_id
                   WHERE m.id IN (SELECT MAX(id) FROM dm_messages WHERE (from_id = ? OR to_id = ?) AND deleted = 0
                                  GROUP BY CASE WHEN from_id = ? THEN to_id ELSE from_id END)
                   ORDER BY m.id DESC LIMIT 200""",
                me.id, me.id, me.id, me.id
            ) { getLong("peer") to message(me.id) }
            lasts.mapNotNull { (peerId, last) ->
                val peer = queryOne("SELECT name, avatar_at FROM users WHERE id = ?", peerId) { Peer(peerId, getString(1), avatarOf(getString(1), getLong(2))) }
                    ?: return@mapNotNull null
                val state = queryOne("SELECT last_read_id, muted, blocked FROM dm_peers WHERE user_id = ? AND peer_id = ?", me.id, peerId) {
                    Triple(getLong(1), getInt(2) == 1, getInt(3) == 1)
                } ?: Triple(0L, false, false)
                val unread = queryOne(
                    "SELECT COUNT(*) FROM dm_messages WHERE from_id = ? AND to_id = ? AND deleted = 0 AND id > ?", peerId, me.id, state.first
                ) { getInt(1) } ?: 0
                Conversation(peer, last, unread, state.second, state.third, last.at)
            }
        }
    }

    /** Unread messages in every conversation that is not muted (the badge on the icon). */
    suspend fun unread(me: User): Int {
        if (me.isGuest) return 0
        return db.tx {
            queryOne(
                """SELECT COUNT(*) FROM dm_messages m LEFT JOIN dm_peers p ON p.user_id = m.to_id AND p.peer_id = m.from_id
                   WHERE m.to_id = ? AND m.deleted = 0 AND m.id > COALESCE(p.last_read_id, 0) AND COALESCE(p.muted, 0) = 0 AND COALESCE(p.blocked, 0) = 0""",
                me.id
            ) { getInt(1) }
        } ?: 0
    }

    /** A page of the conversation with [peerName], newest last; [before] pages back. Also where the other has read up to. */
    suspend fun history(me: User, peerName: String, before: Long?): Map<String, Any?> {
        requireMember(me)
        val peer = peer(me, peerName)
        val (messages, seenUpTo, state) = db.tx {
            val list = query(
                """SELECT m.*, u.name AS from_name FROM dm_messages m JOIN users u ON u.id = m.from_id
                   WHERE ((m.from_id = ? AND m.to_id = ?) OR (m.from_id = ? AND m.to_id = ?)) AND m.deleted = 0 AND m.id < ?
                   ORDER BY m.id DESC LIMIT 50""",
                me.id, peer.id, peer.id, me.id, before ?: Long.MAX_VALUE
            ) { message(me.id) }.reversed()
            val seen = queryOne("SELECT last_read_id FROM dm_peers WHERE user_id = ? AND peer_id = ?", peer.id, me.id) { getLong(1) } ?: 0
            val mine = queryOne("SELECT muted, blocked FROM dm_peers WHERE user_id = ? AND peer_id = ?", me.id, peer.id) { (getInt(1) == 1) to (getInt(2) == 1) }
            Triple(list, seen, mine ?: (false to false))
        }
        return mapOf(
            "peer" to peer, "messages" to messages, "seenUpTo" to seenUpTo, "muted" to state.first, "blocked" to state.second,
            "more" to (messages.size == 50)
        )
    }

    /** Text found in [me]'s conversations (all, or with one person), newest first. */
    suspend fun search(me: User, q: String, peerName: String?): List<Map<String, Any?>> {
        requireMember(me)
        val needle = q.trim().take(100)
        require(needle.length >= 2) { "Escribe al menos 2 letras" }
        val pattern = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val peer = peerName?.takeIf { it.isNotBlank() }?.let { peer(me, it) }
        return db.tx {
            query(
                """SELECT m.*, u.name AS from_name, p.name AS peer_name FROM dm_messages m JOIN users u ON u.id = m.from_id
                   JOIN users p ON p.id = CASE WHEN m.from_id = ? THEN m.to_id ELSE m.from_id END
                   WHERE (m.from_id = ? OR m.to_id = ?) AND m.deleted = 0 AND (m.text LIKE ? ESCAPE '\' OR json_extract(m.card, '$.title') LIKE ? ESCAPE '\')
                   ${if (peer != null) "AND (m.from_id = ? OR m.to_id = ?)" else ""}
                   ORDER BY m.id DESC LIMIT 50""",
                *listOfNotNull(me.id, me.id, me.id, pattern, pattern, peer?.id, peer?.id).toTypedArray()
            ) { mapOf("peer" to getString("peer_name"), "message" to message(me.id)) }
        }
    }

    // ---------- Sending ----------

    suspend fun send(me: User, peerName: String, text: String, card: ShareCard?): Message {
        requireMember(me)
        val peer = peer(me, peerName)
        val body = DmRules.clean(text)
        require(body.isNotEmpty() || card != null) { "El mensaje está vacío" }
        val now = clock()
        if (!guard.allowMessage(me.id, now)) throw IllegalArgumentException("Más despacio: espera unos segundos")
        val (message, firstUnread, peerMuted) = db.tx {
            val mutedUntil = queryOne("SELECT muted_until FROM users WHERE id = ?", me.id) { getLong(1) } ?: 0
            val mine = queryOne("SELECT blocked FROM dm_peers WHERE user_id = ? AND peer_id = ?", me.id, peer.id) { getInt(1) == 1 } ?: false
            val theirs = queryOne("SELECT last_read_id, muted, blocked FROM dm_peers WHERE user_id = ? AND peer_id = ?", peer.id, me.id) {
                Triple(getLong(1), getInt(2) == 1, getInt(3) == 1)
            } ?: Triple(0L, false, false)
            val sentByMe = queryOne("SELECT COUNT(*) FROM dm_messages WHERE from_id = ? AND to_id = ?", me.id, peer.id) { getInt(1) } ?: 0
            val sentByPeer = queryOne("SELECT COUNT(*) FROM dm_messages WHERE from_id = ? AND to_id = ?", peer.id, me.id) { getInt(1) } ?: 0
            val startedToday = queryOne("SELECT COUNT(*) FROM dm_peers WHERE user_id = ? AND started_at >= ?", me.id, now - DAY_MS) { getInt(1) } ?: 0
            DmRules.refuse(DmRules.Situation(theirs.third, mine, sentByMe, sentByPeer, startedToday, mutedUntil, now))
                ?.let { throw SecurityException(it) }
            if (sentByMe == 0 && sentByPeer == 0) {
                update(
                    "INSERT INTO dm_peers(user_id, peer_id, started_at) VALUES(?,?,?) ON CONFLICT(user_id, peer_id) DO UPDATE SET started_at = excluded.started_at",
                    me.id, peer.id, now
                )
            }
            val hadUnread = (queryOne(
                "SELECT COUNT(*) FROM dm_messages WHERE from_id = ? AND to_id = ? AND deleted = 0 AND id > ?", me.id, peer.id, theirs.first
            ) { getInt(1) } ?: 0) > 0
            update(
                "INSERT INTO dm_messages(from_id, to_id, text, card, created_at) VALUES(?,?,?,?,?)",
                me.id, peer.id, body, card?.let(gson::toJson), now
            )
            val id = lastId()
            // Writing in a conversation reads it up to here.
            markReadTx(me.id, peer.id, id)
            Triple(Message(id, me.name, true, body, card, now), !hadUnread, theirs.second)
        }
        typing.remove(me.id to peer.id)
        notifications.emit(peer.id, "dm", mapOf("peer" to me.name, "message" to message.copy(mine = false), "muted" to peerMuted))
        notifications.emit(me.id, "dm", mapOf("peer" to peer.name, "message" to message))
        if (firstUnread && !peerMuted) {
            val what = body.ifBlank { card?.summary().orEmpty() }.let { if (it.length > 80) it.take(79) + "…" else it }
            notifications.send(peer.id, "dm", "${me.name}: $what", "/mensajes/" + enc(me.name))
        }
        events.record(me.id, "dm_sent", mapOf("to" to peer.id))
        if (card != null) events.record(me.id, "shared", mapOf("to" to "dm", "kind" to card.kind, "spoiler" to card.spoiler))
        return message
    }

    private fun java.sql.Connection.markReadTx(me: Long, peer: Long, upTo: Long) {
        update(
            """INSERT INTO dm_peers(user_id, peer_id, last_read_id) VALUES(?,?,?)
               ON CONFLICT(user_id, peer_id) DO UPDATE SET last_read_id = MAX(last_read_id, excluded.last_read_id)""",
            me, peer, upTo
        )
    }

    /** Read up to [upTo] (never past the newest message from them); the other side sees «visto». */
    suspend fun markRead(me: User, peerName: String, upTo: Long) {
        requireMember(me)
        val peer = peer(me, peerName)
        val newest = db.tx {
            val newest = queryOne("SELECT MAX(id) FROM dm_messages WHERE from_id = ? AND to_id = ?", peer.id, me.id) { getLong(1) } ?: 0
            markReadTx(me.id, peer.id, minOf(upTo, newest))
            newest
        }
        notifications.emit(peer.id, "dm_seen", mapOf("peer" to me.name, "upTo" to minOf(upTo, newest)))
    }

    /** «Escribiendo…» for the other side, at most once every 3 s (the client repeats it while typing). */
    fun typing(me: User, peer: Peer) {
        if (me.isGuest) return
        val key = me.id to peer.id
        val now = clock()
        val last = typing[key]
        if (last != null && now - last < TYPING_EVERY_MS) return
        typing[key] = now
        if (typing.size > 5_000) typing.entries.removeIf { now - it.value > 60_000 }
        notifications.emit(peer.id, "dm_typing", mapOf("peer" to me.name))
    }

    /** Deletes one of [me]'s own messages, for both sides (someone else's can never be deleted). */
    suspend fun delete(me: User, id: Long) {
        requireMember(me)
        val to = db.tx {
            val to = queryOne("SELECT to_id FROM dm_messages WHERE id = ? AND from_id = ? AND deleted = 0", id, me.id) { getLong(1) }
                ?: throw NoSuchElementException("No existe ese mensaje")
            update("UPDATE dm_messages SET deleted = 1, text = '', card = NULL WHERE id = ?", id)
            to
        }
        val peerName = db.tx { queryOne("SELECT name FROM users WHERE id = ?", to) { getString(1) } }.orEmpty()
        notifications.emit(to, "dm_deleted", mapOf("peer" to me.name, "id" to id))
        notifications.emit(me.id, "dm_deleted", mapOf("peer" to peerName, "id" to id))
    }

    suspend fun setMuted(me: User, peerName: String, on: Boolean) = setFlag(me, peerName, "muted", on)
    suspend fun setBlocked(me: User, peerName: String, on: Boolean) = setFlag(me, peerName, "blocked", on)

    private suspend fun setFlag(me: User, peerName: String, column: String, on: Boolean) {
        requireMember(me)
        require(column == "muted" || column == "blocked")
        val peer = peer(me, peerName)
        db.tx {
            update(
                "INSERT INTO dm_peers(user_id, peer_id, $column) VALUES(?,?,?) ON CONFLICT(user_id, peer_id) DO UPDATE SET $column = excluded.$column",
                me.id, peer.id, on
            )
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    companion object {
        const val DAY_MS = 86_400_000L
        const val TYPING_EVERY_MS = 3_000L
    }
}

fun Route.directMessageRoutes(s: Services) {
    suspend fun ApplicationCall.dmsOn(): Boolean {
        if (s.features.isOn("dms")) return true
        respond(HttpStatusCode.NotFound, mapOf("error" to "Los mensajes directos están desactivados"))
        return false
    }
    suspend fun ApplicationCall.refused(e: SecurityException) = respond(HttpStatusCode.Forbidden, mapOf("error" to (e.message ?: "No permitido")))
    fun ApplicationCall.peerName() = parameters["name"].orEmpty().take(60)

    get("/dm") { if (call.dmsOn()) call.respond(s.dms.conversations(call.user)) }
    get("/dm/unread") { call.respond(mapOf("unread" to if (s.features.isOn("dms")) s.dms.unread(call.user) else 0)) }
    get("/dm/search") {
        if (call.dmsOn()) call.respond(s.dms.search(call.user, call.request.queryParameters["q"].orEmpty(), call.request.queryParameters["with"]))
    }
    get("/dm/{name}") {
        if (call.dmsOn()) call.respond(s.dms.history(call.user, call.peerName(), call.request.queryParameters["before"]?.toLongOrNull()))
    }
    post("/dm/{name}") {
        if (!call.dmsOn()) return@post
        val body = call.jsonBody(8_192)
        val text = body.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val card = body.get("card")?.let { cardFrom(it) ?: throw IllegalArgumentException("Esa obra no se puede compartir") }
        try {
            call.respond(s.dms.send(call.user, call.peerName(), text, card))
        } catch (e: SecurityException) {
            call.refused(e)
        }
    }
    post("/dm/{name}/read") {
        if (!call.dmsOn()) return@post
        val upTo = call.jsonBody(256).get("upTo")?.takeIf { it.isJsonPrimitive }?.asLong ?: Long.MAX_VALUE
        s.dms.markRead(call.user, call.peerName(), upTo)
        call.respond(mapOf("ok" to true))
    }
    post("/dm/{name}/typing") {
        if (!call.dmsOn()) return@post
        s.dms.typing(call.user, s.dms.peer(call.user, call.peerName()))
        call.respond(mapOf("ok" to true))
    }
    post("/dm/{name}/mute") {
        if (!call.dmsOn()) return@post
        s.dms.setMuted(call.user, call.peerName(), call.jsonBody(256).get("on")?.asBoolean == true)
        call.respond(mapOf("ok" to true))
    }
    post("/dm/{name}/block") {
        if (!call.dmsOn()) return@post
        s.dms.setBlocked(call.user, call.peerName(), call.jsonBody(256).get("on")?.asBoolean == true)
        call.respond(mapOf("ok" to true))
    }
    delete("/dm/messages/{id}") {
        if (!call.dmsOn()) return@delete
        s.dms.delete(call.user, call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Mensaje no válido"))
        call.respond(mapOf("ok" to true))
    }
}

