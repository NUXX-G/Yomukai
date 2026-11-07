package com.nelson.yomukai.server

import com.google.gson.JsonObject
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Watched state and position, both ways, between a Yomukai user and the Jellyfin user the admin linked to them:
 * - what is watched in Yomukai from the NAS goes to Jellyfin right away (position, and "played" at the end);
 * - what is watched in Jellyfin's own apps comes back every 15 min as progress only (never as new history rows, so
 *   nothing appears twice), and only when it is newer than what Yomukai already has.
 */
class JellyfinSync(private val db: Database, private val link: JellyfinLink) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = "application/json".toMediaType()

    suspend fun linkedUser(userId: Long): String? = db.tx { queryOne("SELECT jellyfin_user FROM users WHERE id = ?", userId) { getString(1) } }?.takeIf { it.isNotBlank() }

    suspend fun setLink(userId: Long, jellyfinUser: String?) {
        val value = jellyfinUser?.trim()?.takeIf { it.isNotEmpty() }?.let { JellyfinSource.safeId(it) }
        db.tx { update("UPDATE users SET jellyfin_user = ? WHERE id = ?", value, userId) }
    }

    /** Jellyfin's users, for the admin to pair them with Yomukai's (empty when it does not answer). */
    suspend fun jellyfinUsers(): List<Map<String, String>> {
        val api = link.api ?: return emptyList()
        return runCatching {
            val arr = withContext(Dispatchers.IO) {
                api.client.newCall(api.request("/Users")).execute().use { r ->
                    if (!r.isSuccessful) null else com.google.gson.JsonParser.parseString(r.body?.string().orEmpty()).asJsonArray
                }
            } ?: return emptyList()
            arr.mapNotNull { e -> e.asJsonObject.let { o -> o.get("Id")?.asString?.let { mapOf("id" to it.replace("-", ""), "name" to (o.get("Name")?.asString ?: "")) } } }
        }.getOrDefault(emptyList())
    }

    /** After the user saved progress: the NAS rows go to Jellyfin in the background (never slows the save down). */
    fun pushLater(userId: Long, rows: List<ProgressRow>) {
        val mine = rows.filter { it.sourceId == JellyfinSource.ID }.takeLast(MAX_PUSH)
        if (mine.isEmpty()) return
        scope.launch { runCatching { push(userId, mine) }.onFailure { log.debug("Jellyfin progreso: {}", it.message) } }
    }

    suspend fun push(userId: Long, rows: List<ProgressRow>) {
        val api = link.api ?: return
        val juser = linkedUser(userId) ?: return
        for (row in rows) {
            val item = runCatching { JellyfinSource.safeId(row.chapterId) }.getOrNull() ?: continue
            val body = JsonObject().apply {
                addProperty("PlaybackPositionTicks", if (row.read) 0L else row.page.toLong() * TICKS_PER_SECOND)
                addProperty("Played", row.read)
            }.toString().toRequestBody(json)
            withContext(Dispatchers.IO) {
                // Jellyfin 10.9+ first; the older address if that one does not exist.
                val ok = api.client.newCall(api.request("/UserItems/$item/UserData?userId=$juser").newBuilder().post(body).build()).execute().use { it.isSuccessful }
                if (!ok) api.client.newCall(api.request("/Users/$juser/Items/$item/UserData").newBuilder().post(body).build()).execute().close()
            }
        }
    }

    /** Brings back what every linked user watched in Jellyfin's own apps lately. */
    suspend fun pullAll() {
        val api = link.api ?: return
        val linked = db.tx { query("SELECT id, jellyfin_user FROM users WHERE jellyfin_user IS NOT NULL AND jellyfin_user != ''") { getLong(1) to getString(2) } }
        for ((userId, juser) in linked) {
            runCatching {
                val rows = listOf("Filters=IsResumable", "IsPlayed=true").flatMap { filter ->
                    api.json(
                        "/Items?userId=$juser&Recursive=true&IncludeItemTypes=Episode,Movie&$filter&SortBy=DatePlayed&SortOrder=Descending&Limit=$PULL_LIMIT&Fields=UserData"
                    ).getAsJsonArray("Items")?.mapNotNull { rowFrom(it.asJsonObject) }.orEmpty()
                }
                if (rows.isNotEmpty()) db.putProgress(userId, rows)
            }.onFailure { log.debug("Jellyfin progreso de {}: {}", userId, it.message) }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JellyfinSync::class.java)
        const val TICKS_PER_SECOND = 10_000_000L
        private const val MAX_PUSH = 50
        private const val PULL_LIMIT = 100

        /** One item of Jellyfin's "watched lately" as a Yomukai progress row (null when it lacks what is needed). */
        fun rowFrom(o: JsonObject): ProgressRow? {
            fun JsonObject.s(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val id = o.s("Id").replace("-", "").takeIf { it.length == 32 } ?: return null
            val data = o.getAsJsonObject("UserData") ?: return null
            val at = runCatching { Instant.parse(data.s("LastPlayedDate")).toEpochMilli() }.getOrNull() ?: return null
            val media = if (o.s("Type") == "Movie") id else o.s("SeriesId").replace("-", "").takeIf { it.length == 32 } ?: return null
            val played = data.get("Played")?.asBoolean == true
            val position = ((data.get("PlaybackPositionTicks")?.asLong ?: 0L) / TICKS_PER_SECOND).toInt()
            val total = ((o.get("RunTimeTicks")?.asLong ?: 0L) / TICKS_PER_SECOND).toInt()
            return ProgressRow(JellyfinSource.ID, id, media, played, if (played) 0 else position, total, 0.0, at)
        }
    }
}

/** Admin: pair Yomukai users with Jellyfin users (for watched state and positions). */
fun Route.jellyfinLinkAdminRoutes(s: Services) {
    get("/jellyfin/users") {
        val links = s.db.tx {
            query("SELECT id, name, jellyfin_user FROM users WHERE is_guest = 0 ORDER BY name") {
                mapOf("id" to getLong(1), "name" to getString(2), "jellyfinUser" to getString(3))
            }
        }
        call.respond(mapOf("jellyfin" to s.jellyfinSync.jellyfinUsers(), "users" to links, "online" to (s.jellyfin.api != null)))
    }
    put("/jellyfin/link") {
        val b = call.jsonBody(1_024)
        val userId = b.get("userId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: throw IllegalArgumentException("Falta el usuario")
        s.jellyfinSync.setLink(userId, b.get("jellyfinUser")?.takeIf { it.isJsonPrimitive }?.asString)
        call.respond(mapOf("ok" to true))
    }
}
