package com.nelson.yomukai.server

import com.google.gson.Gson
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * What a user should hear about (mentions, replies, recommendations, achievements): stored for the bell, pushed live
 * to open web pages over SSE, and, for whoever turned it on, to their phone through ntfy (a private random topic on
 * NTFY_PUSH_URL, ntfy.sh by default). Discord gets only rooms opening and new forum threads (DISCORD_WEBHOOK_URL).
 */
class Notifications(
    private val db: Database,
    private val notifier: Notifier,
    private val pushBase: String,
    private val publicUrl: String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
) {
    private val log = LoggerFactory.getLogger("Notifications")
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    data class Item(val id: Long, val userId: Long, val kind: String, val text: String, val link: String, val at: Long, val read: Boolean)

    /** Every new notification, for the SSE streams of open pages (each keeps only its user's). */
    val live = MutableSharedFlow<Item>(extraBufferCapacity = 256)

    suspend fun send(userId: Long, kind: String, text: String, link: String) {
        val now = clock()
        val item = db.tx {
            update(
                "INSERT INTO notifications(user_id, kind, text, link, created_at) VALUES(?,?,?,?,?)",
                userId, kind.take(30), text.take(300), link.take(500), now
            )
            Item(lastId(), userId, kind, text, link, now, false)
        }
        live.tryEmit(item)
        val topic = db.tx { queryOne("SELECT ntfy_topic FROM users WHERE id = ?", userId) { getString(1) } }
        if (!topic.isNullOrBlank()) push(topic, text, link)
    }

    private fun push(topic: String, text: String, link: String) = scope.launch {
        val request = Request.Builder().url("$pushBase/$topic")
            .header("Title", "Yomukai")
            .apply { publicUrl?.let { header("Click", it + link) } }
            .post(text.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().close() }.onFailure { log.debug("ntfy: {}", it.message) }
    }

    /** Something live for one user's open pages other than a notification (direct messages: new, typing, seen...). */
    data class LiveEvent(val userId: Long, val type: String, val json: String)

    val events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 512)

    fun emit(userId: Long, type: String, payload: Any) {
        events.tryEmit(LiveEvent(userId, type, gson.toJson(payload)))
    }

    suspend fun list(userId: Long): Map<String, Any> {
        val items = db.tx {
            query("SELECT * FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 50", userId) {
                Item(getLong("id"), userId, getString("kind"), getString("text"), getString("link"), getLong("created_at"), getInt("read") == 1)
            }
        }
        return mapOf("items" to items, "unread" to items.count { !it.read })
    }

    suspend fun markRead(userId: Long) = db.tx { update("UPDATE notifications SET read = 1 WHERE user_id = ? AND read = 0", userId) }

    /** A private ntfy topic for the user's phone (random, so nobody can guess it and read their notifications). */
    suspend fun enablePush(userId: Long): String {
        val existing = db.tx { queryOne("SELECT ntfy_topic FROM users WHERE id = ?", userId) { getString(1) } }
        if (!existing.isNullOrBlank()) return existing
        val topic = "yomukai-" + ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        db.tx { update("UPDATE users SET ntfy_topic = ? WHERE id = ?", topic, userId) }
        return topic
    }

    suspend fun disablePush(userId: Long) = db.tx { update("UPDATE users SET ntfy_topic = NULL WHERE id = ?", userId) }

    suspend fun pushTopic(userId: Long): String? = db.tx { queryOne("SELECT ntfy_topic FROM users WHERE id = ?", userId) { getString(1) } }

    fun pushUrl(topic: String) = "$pushBase/$topic"

    /** Rooms opening and new threads, to the group's Discord channel (if configured). */
    fun discord(text: String) = notifier.public("discord:" + text.take(60), text)

    suspend fun cleanup() = db.tx { update("DELETE FROM notifications WHERE created_at < ?", clock() - 60L * 86_400_000) }
}

fun Route.notificationRoutes(s: Services) {
    get("/notifications") { call.respond(s.notifications.list(call.user.id)) }
    post("/notifications/read") {
        s.notifications.markRead(call.user.id)
        call.respond(mapOf("ok" to true))
    }
    // Live: each open page gets its user's new notifications as they happen (a ping every 25 s keeps proxies quiet).
    sse("/notifications/stream") {
        val me = call.user.id
        val gson = Gson()
        val job = launch { s.notifications.live.filter { it.userId == me }.collect { send(ServerSentEvent(gson.toJson(it), event = "notification")) } }
        val other = launch { s.notifications.events.filter { it.userId == me }.collect { send(ServerSentEvent(it.json, event = it.type)) } }
        try {
            while (true) {
                withTimeoutOrNull(25_000) { kotlinx.coroutines.awaitCancellation() }
                send(ServerSentEvent("{}", event = "ping"))
            }
        } finally {
            job.cancel()
            other.cancel()
        }
    }
    get("/notifications/push") {
        val topic = s.notifications.pushTopic(call.user.id)
        call.respond(mapOf("enabled" to (topic != null), "url" to topic?.let(s.notifications::pushUrl)))
    }
    post("/notifications/push") {
        require(!call.user.isGuest) { "Los invitados no reciben avisos en el móvil" }
        call.respond(mapOf("enabled" to true, "url" to s.notifications.pushUrl(s.notifications.enablePush(call.user.id))))
    }
    delete("/notifications/push") {
        s.notifications.disablePush(call.user.id)
        call.respond(mapOf("enabled" to false))
    }
}
