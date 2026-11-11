package com.nelson.yomukai.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.BuildConfig
import com.nelson.yomukai.data.local.BookmarkEntity
import com.nelson.yomukai.data.local.CategoryEntity
import com.nelson.yomukai.data.local.HistoryEntity
import com.nelson.yomukai.data.local.MediaCategoryEntity
import com.nelson.yomukai.data.local.MediaEntity
import com.nelson.yomukai.data.local.PendingProgressEntity
import com.nelson.yomukai.data.local.ReadingStatus
import com.nelson.yomukai.data.local.YomukaiDatabase
import com.nelson.yomukai.data.prefs.AppPreferences
import com.nelson.yomukai.data.prefs.GenreExclusions
import com.nelson.yomukai.data.repository.TOMBSTONE_BOOKMARK
import com.nelson.yomukai.data.repository.TOMBSTONE_CATEGORY
import com.nelson.yomukai.data.repository.TOMBSTONE_HISTORY
import com.nelson.yomukai.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the settings screen shows about the connection. */
data class SyncState(
    val server: String? = null,
    val user: String? = null,
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val error: String? = null
) {
    val connected get() = server != null
}

/**
 * Optional sync with a Yomukai server (the web version). The app works the same without it. When
 * connected, every change here is stamped and sent; the server keeps the newest of each row and hands
 * back what other devices changed since the last time ("revision"), deletions included.
 */

/** See [ServerSync.features]. */
data class ServerFeatures(
    val flags: Map<String, Boolean> = emptyMap(),
    val workerOnline: Boolean = false,
    val workerGpu: Boolean = false,
    /** Job types a worker can do right now (tts, ocr, whisper...). */
    val available: Set<String> = emptySet()
) {
    fun on(flag: String) = flags[flag] == true
}

class ServerSync(
    context: Context,
    private val db: YomukaiDatabase,
    private val preferences: AppPreferences,
    /** Told which sources the server's canary found broken (id to why), so the app hides them too. */
    private val onSourceHealth: (Map<String, String>) -> Unit = {}
) {
    private val prefs = context.getSharedPreferences("yomukai_sync", Context.MODE_PRIVATE)
    private val dao = db.syncDao()
    private val lock = Mutex()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Its own client: the server is the user's own, never reached through Tor or the sources' proxy. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private fun readState() = SyncState(
        server = prefs.getString("server", null),
        user = prefs.getString("user", null),
        lastSyncAt = prefs.getLong("last_sync", 0L),
        error = prefs.getString("error", null)
    )

    private val token get() = prefs.getString("token", null)

    /**
     * Signs in and, if that works, sends everything this phone has (the first sync merges both sides:
     * nothing is deleted). Returns an error message for the user, or null.
     */

    /** The challenge of a login that still needs its 2FA code, with the server it belongs to. */
    @Volatile private var pendingTwoFactor: Pair<String, String>? = null

    /** The session from a successful login answer, or why there is none (including "2FA code needed"). */
    private fun loginAnswer(server: String, cookies: List<String>, body: String?): Result<String> {
        val session = cookies.firstNotNullOfOrNull { c -> c.substringBefore(';').takeIf { it.startsWith("$COOKIE=") }?.substringAfter('=') }
        val info = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val challenge = info?.takeIf { it.bool("twoFactor") }?.str("challenge")
        val guest = (info?.getAsJsonObject("user") ?: info)?.get("guest")?.asBoolean == true
        return when {
            !challenge.isNullOrBlank() -> {
                pendingTwoFactor = server to challenge
                Result.failure(IOException(TWO_FACTOR_NEEDED))
            }
            session.isNullOrBlank() -> Result.failure(IOException(tr("Eso no parece un servidor de Yomukai")))
            guest -> Result.failure(IOException(tr("Las cuentas de invitado no se pueden sincronizar")))
            else -> Result.success(session)
        }
    }

    suspend fun connect(serverInput: String, user: String, password: String, code: String? = null): String? = withContext(Dispatchers.IO) {
        val server = normalizeServer(serverInput) ?: return@withContext tr("Esa dirección no es válida. Ejemplo: https://yomukai.tudominio.com")
        if (!server.startsWith("https://") && !allowsPlainHttp(server)) {
            return@withContext tr("El servidor tiene que usar HTTPS: la contraseña viajaría sin cifrar.")
        }
        val pending = pendingTwoFactor?.takeIf { it.first == server && !code.isNullOrBlank() }
        val request = if (pending != null) {
            val body = JsonObject().apply { addProperty("challenge", pending.second); addProperty("code", code!!.trim()) }
            Request.Builder().url("$server/auth/2fa").post(body.toString().toRequestBody(json)).build()
        } else {
            val body = JsonObject().apply { addProperty("name", user.trim()); addProperty("password", password) }
            Request.Builder().url("$server/auth/login").post(body.toString().toRequestBody(json)).build()
        }
        val result = runCatching {
            client.newCall(request).execute().use { r ->
                when {
                    r.code == 401 && pending != null -> Result.failure(IOException(tr("Código incorrecto o caducado")))
                    r.code == 401 -> Result.failure(IOException(tr("Usuario o contraseña incorrectos")))
                    r.code == 429 -> Result.failure(IOException(errorOf(r.body?.string()) ?: tr("Demasiados intentos; espera un rato")))
                    !r.isSuccessful -> Result.failure(IOException(errorOf(r.body?.string()) ?: tr("El servidor respondió {0}", r.code)))
                    else -> loginAnswer(server, r.headers("Set-Cookie"), r.body?.string())
                }
            }
        }.getOrElse { Result.failure(IOException(tr("No se pudo conectar con el servidor: {0}", it.message))) }
        val session = result.getOrElse { return@withContext it.message }
        pendingTwoFactor = null
        prefs.edit()
            .putString("server", server).putString("user", user.trim()).putString("token", session)
            .putLong("rev", 0L).remove("error").commit()
        // Everything here goes up once, so the server ends up with both sides.
        db.withTransaction {
            dao.markLibraryForUpload()
            dao.markCategoriesForUpload(System.currentTimeMillis())
            dao.markProgressForUpload()
            dao.markHistoryForUpload()
            dao.markBookmarksForUpload()
        }
        preferences.markSharedForUpload()
        _state.value = readState()
        sync().exceptionOrNull()?.message
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val server = prefs.getString("server", null)
        val session = token
        if (server != null && session != null) {
            runCatching {
                client.newCall(authed(Request.Builder().url("$server/auth/logout").post(ByteArray(0).toRequestBody()), session).build()).execute().close()
            }
        }
        // After any sync in progress, so it cannot write its result back afterwards; and written at
        // once, as the app may be closed right after.
        lock.withLock { prefs.edit().clear().commit() }
        _state.value = SyncState()
    }

    /** Sends what changed here and takes what changed elsewhere. Safe to call often. */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        val server = prefs.getString("server", null) ?: return@withContext Result.success(Unit)
        val session = token ?: return@withContext Result.success(Unit)
        lock.withLock {
            _state.value = _state.value.copy(running = true)
            val result = runCatching { run(server, session) }
            runCatching { fetchSourceHealth(server, session) }
            runCatching { fetchTheme(server, session) }
            val error = result.exceptionOrNull()?.let { it.message ?: "Error al sincronizar" }
            prefs.edit().apply {
                if (error == null) putLong("last_sync", System.currentTimeMillis()).remove("error") else putString("error", error)
            }.apply()
            _state.value = readState().copy(running = false)
            result
        }
    }

    private suspend fun run(server: String, session: String) {
        // Deletions and small tables go with the first request; reading progress, which can be tens of
        // thousands of rows the first time, goes in batches.
        val tombstones = dao.tombstones()
        val categories = dao.dirtyCategories()
        val library = dao.dirtyLibrary()
        val history = dao.dirtyHistory()
        val bookmarks = dao.dirtyBookmarks()
        val sharedAt = preferences.sharedUpdatedAt
        val shared = if (preferences.sharedDirty) sharedSettings(sharedAt) else null

        val first = JsonObject().apply {
            add("categories", JsonArray().apply {
                categories.forEach { add(obj("name" to it.name, "sortOrder" to it.sortOrder, "updatedAt" to it.updatedAt, "deleted" to false)) }
                tombstones.filter { it.kind == TOMBSTONE_CATEGORY }.forEach { add(obj("name" to it.key, "sortOrder" to 0, "updatedAt" to it.deletedAt, "deleted" to true)) }
            })
            add("library", JsonArray().apply {
                library.forEach { m ->
                    add(obj(
                        "sourceId" to m.sourceId, "mediaId" to m.mediaId, "title" to m.title, "cover" to m.coverUrl, "type" to m.type,
                        "status" to m.status, "author" to m.author, "genres" to m.genres, "addedAt" to m.dateAdded,
                        "readingStatus" to m.readingStatus, "updatedAt" to m.libraryUpdatedAt, "deleted" to !m.inLibrary
                    ).apply { add("categories", JsonArray().apply { dao.categoryNamesFor(m.sourceId, m.mediaId).forEach { add(it) } }) })
                }
            })
            add("history", JsonArray().apply {
                history.forEach { h ->
                    add(obj(
                        "sourceId" to h.sourceId, "mediaId" to h.mediaId, "chapterId" to h.chapterId, "chapterTitle" to h.chapterTitle,
                        "title" to h.title, "cover" to h.coverUrl, "type" to h.type, "readAt" to h.readAt, "updatedAt" to h.readAt, "deleted" to false
                    ))
                }
                tombstones.filter { it.kind == TOMBSTONE_HISTORY }.forEach { t ->
                    val (sourceId, mediaId) = t.key.split('\n').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                    add(obj(
                        "sourceId" to sourceId, "mediaId" to mediaId, "chapterId" to "-", "chapterTitle" to "", "title" to "", "cover" to "",
                        "type" to "MANGA", "readAt" to t.deletedAt, "updatedAt" to t.deletedAt, "deleted" to true
                    ))
                }
            })
            add("bookmarks", JsonArray().apply {
                bookmarks.forEach { b ->
                    add(obj(
                        "sourceId" to b.sourceId, "mediaId" to b.mediaId, "chapterId" to b.chapterId, "chapterTitle" to b.chapterTitle,
                        "position" to b.position, "label" to b.label, "createdAt" to b.createdAt, "updatedAt" to b.updatedAt, "deleted" to false
                    ))
                }
                tombstones.filter { it.kind == TOMBSTONE_BOOKMARK }.forEach { t ->
                    val parts = t.key.split('\n')
                    if (parts.size == 4) add(obj(
                        "sourceId" to parts[0], "mediaId" to parts[1], "chapterId" to parts[2], "position" to (parts[3].toIntOrNull() ?: 0),
                        "chapterTitle" to "", "label" to "", "createdAt" to t.deletedAt, "updatedAt" to t.deletedAt, "deleted" to true
                    ))
                }
            })
            if (shared != null) add("settings", shared)
        }

        val since = prefs.getLong("rev", 0L)
        var request = first
        var answer: JsonObject
        while (true) {
            val progress = dao.dirtyProgress(PROGRESS_BATCH)
            request.add("progress", JsonArray().apply {
                progress.forEach { c ->
                    add(obj(
                        "sourceId" to c.sourceId, "chapterId" to c.chapterId, "mediaId" to c.mediaId, "read" to c.read,
                        "page" to c.lastPageRead, "total" to c.totalPages, "fraction" to c.progress, "updatedAt" to c.progressUpdatedAt
                    ))
                }
            })
            val last = progress.size < PROGRESS_BATCH
            // Only the last request asks for what changed elsewhere; the others just send.
            request.addProperty("since", if (last) since else Long.MAX_VALUE)
            answer = post(server, session, request)
            progress.forEach { dao.progressSent(it.sourceId, it.chapterId, it.progressUpdatedAt) }
            if (last) break
            request = JsonObject()
        }

        // Sent: clear the flags of rows that did not change again meanwhile.
        categories.forEach { dao.categorySent(it.id, it.updatedAt) }
        library.forEach { dao.librarySent(it.sourceId, it.mediaId, it.libraryUpdatedAt) }
        history.forEach { dao.historySent(it.sourceId, it.mediaId, it.readAt) }
        bookmarks.forEach { dao.bookmarkSent(it.id, it.updatedAt) }
        tombstones.forEach { dao.tombstoneSent(it.kind, it.key, it.deletedAt) }
        if (shared != null) preferences.markSharedSent(sharedAt)

        apply(answer)
        applySettings(answer)
        prefs.edit().putLong("rev", answer.get("rev")?.asLong ?: since).apply()
    }

    private fun sharedSettings(at: Long): JsonObject {
        val excl = preferences.exclusions.value
        return JsonObject().apply {
            preferences.app.value.enabledSources?.let { set -> add("enabledSources", JsonArray().apply { set.sorted().forEach { add(it) } }) }
            add("exclusions", JsonObject().apply {
                add("global", JsonArray().apply { excl.global.sorted().forEach { add(it) } })
                add("perSource", JsonObject().apply {
                    excl.bySource.forEach { (source, genres) -> add(source, JsonArray().apply { genres.sorted().forEach { add(it) } }) }
                })
            })
            addProperty("updatedAt", at)
        }
    }

    private fun post(server: String, session: String, body: JsonObject): JsonObject {
        val request = authed(Request.Builder().url("$server/api/sync").post(body.toString().toRequestBody(json)), session).build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) {
                // The session was closed (password changed, account deleted): connecting again is needed.
                throw IOException(tr("La sesión ya no vale. Vuelve a conectar con tu usuario y contraseña."))
            }
            if (!r.isSuccessful) throw IOException(errorOf(text) ?: tr("El servidor respondió {0}", r.code))
            return JsonParser.parseString(text).asJsonObject
        }
    }

    /** Applies other devices' changes, unless this phone changed the same thing later. */
    private suspend fun apply(answer: JsonObject) = db.withTransaction {
        val now = System.currentTimeMillis()
        // Categories first: titles refer to them by name.
        answer.array("categories").forEach { e ->
            val c = e.asJsonObject
            val name = c.str("name").takeIf { it.isNotBlank() } ?: return@forEach
            val at = c.long("updatedAt")
            val local = dao.categories().firstOrNull { it.name == name }
            if (local != null && local.dirty && local.updatedAt > at) return@forEach
            if (c.bool("deleted")) {
                if (local != null) dao.deleteCategory(local.id)
            } else if (local == null) {
                dao.insertCategory(CategoryEntity(name = name, sortOrder = c.int("sortOrder"), updatedAt = at))
            } else {
                dao.updateCategory(local.copy(sortOrder = c.int("sortOrder"), updatedAt = at, dirty = false))
            }
            dao.dropTombstone(TOMBSTONE_CATEGORY, name)
        }

        answer.array("library").forEach { e ->
            val r = e.asJsonObject
            val sourceId = r.str("sourceId"); val mediaId = r.str("mediaId")
            val at = r.long("updatedAt")
            val local = dao.media(sourceId, mediaId)
            if (local != null && local.libraryDirty && local.libraryUpdatedAt > at) return@forEach
            val deleted = r.bool("deleted")
            val base = local ?: MediaEntity(
                sourceId = sourceId, mediaId = mediaId, title = r.str("title"), coverUrl = r.str("cover"), type = r.str("type").ifBlank { "MANGA" },
                status = r.str("status"), description = "", author = r.str("author"), genres = r.str("genres")
            )
            dao.upsertMedia(base.copy(
                title = base.title.ifBlank { r.str("title") },
                coverUrl = base.coverUrl.ifBlank { r.str("cover") },
                inLibrary = !deleted,
                dateAdded = if (deleted) 0L else r.long("addedAt").takeIf { it > 0 } ?: now,
                readingStatus = ReadingStatus.parse(r.str("readingStatus").let { if (it == "PLAN") "PLAN_TO_READ" else it }).name,
                libraryUpdatedAt = at,
                libraryDirty = false
            ))
            if (!deleted) {
                val wanted = r.array("categories").mapNotNull { it.takeIf { j -> j.isJsonPrimitive }?.asString }
                val known = dao.categories().associateBy { it.name }.toMutableMap()
                val ids = wanted.map { name -> known[name]?.id ?: dao.insertCategory(CategoryEntity(name = name, updatedAt = at)).also { id -> known[name] = CategoryEntity(id, name, 0, at) } }
                dao.clearCategoriesOf(sourceId, mediaId)
                dao.assign(ids.map { MediaCategoryEntity(sourceId, mediaId, it) })
            }
        }

        answer.array("progress").forEach { e ->
            val p = e.asJsonObject
            val sourceId = p.str("sourceId"); val chapterId = p.str("chapterId")
            val at = p.long("updatedAt")
            val local = dao.chapter(sourceId, chapterId)
            val fraction = p.double("fraction").toFloat()
            if (local != null) {
                if (local.progressDirty && local.progressUpdatedAt > at) return@forEach
                if (local.progressUpdatedAt == at && !local.progressDirty) return@forEach
                dao.applyProgress(sourceId, chapterId, p.bool("read"), p.int("page"), p.int("total"), fraction, at)
            } else {
                val pending = dao.pending(sourceId, chapterId)
                if (pending == null || pending.updatedAt < at) {
                    dao.upsertPending(PendingProgressEntity(sourceId, chapterId, p.str("mediaId"), p.bool("read"), p.int("page"), p.int("total"), fraction, at))
                }
            }
        }

        answer.array("history").forEach { e ->
            val h = e.asJsonObject
            val sourceId = h.str("sourceId"); val mediaId = h.str("mediaId")
            val at = h.long("updatedAt")
            val local = dao.history(sourceId, mediaId)
            if (local != null && local.dirty && local.readAt > at) return@forEach
            if (h.bool("deleted")) {
                dao.deleteHistory(sourceId, mediaId)
                return@forEach
            }
            // History shows titles from the media table: a title never opened here needs a row there.
            if (dao.media(sourceId, mediaId) == null) {
                dao.upsertMedia(MediaEntity(
                    sourceId = sourceId, mediaId = mediaId, title = h.str("title"), coverUrl = h.str("cover"),
                    type = h.str("type").ifBlank { "MANGA" }, status = "", description = "", author = "", genres = ""
                ))
            }
            dao.upsertHistory(HistoryEntity(sourceId, mediaId, h.str("chapterId"), h.str("chapterTitle"), h.long("readAt"), dirty = false))
            dao.dropTombstone(TOMBSTONE_HISTORY, "$sourceId\n$mediaId")
        }

        answer.array("bookmarks").forEach { e ->
            val b = e.asJsonObject
            val sourceId = b.str("sourceId"); val chapterId = b.str("chapterId"); val position = b.int("position")
            val at = b.long("updatedAt")
            val local = dao.bookmark(sourceId, chapterId, position)
            if (local != null && local.dirty && local.updatedAt > at) return@forEach
            if (b.bool("deleted")) {
                dao.deleteBookmark(sourceId, chapterId, position)
            } else {
                dao.putBookmark(BookmarkEntity(
                    id = local?.id ?: 0, sourceId = sourceId, mediaId = b.str("mediaId"), chapterId = chapterId,
                    chapterTitle = b.str("chapterTitle"), position = position, label = b.str("label"),
                    createdAt = b.long("createdAt"), updatedAt = at, dirty = false
                ))
            }
        }
    }

    /** The shared settings, outside the database transaction (they live in the preferences). */
    private fun applySettings(answer: JsonObject) {
        answer.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject?.let { s ->
            val at = s.long("updatedAt")
            if (at > preferences.sharedUpdatedAt) {
                val enabled = s.get("enabledSources")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { j -> j.isJsonPrimitive }?.asString }?.toSet()
                val excl = s.get("exclusions")?.takeIf { it.isJsonObject }?.asJsonObject
                val global = excl?.array("global")?.mapNotNull { it.takeIf { j -> j.isJsonPrimitive }?.asString }?.toSet().orEmpty()
                val bySource = excl?.get("perSource")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { (k, v) ->
                    k to (v.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { j -> j.isJsonPrimitive }?.asString }?.toSet().orEmpty())
                }.orEmpty().filterValues { it.isNotEmpty() }
                preferences.applySharedFromServer(enabled, GenreExclusions(global, bySource), at)
            }
        }
    }

    private fun authed(builder: Request.Builder, session: String) = builder.header("Cookie", "$COOKIE=$session")

    /** The theme chosen on the server (web or app) is the one this phone shows too. */
    private fun fetchTheme(server: String, session: String) {
        client.newCall(authed(Request.Builder().url("$server/api/themes/current"), session).build()).execute().use { r ->
            if (!r.isSuccessful) return
            val theme = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject.get("theme")?.takeIf { it.isJsonObject }?.toString()
            if (theme != preferences.app.value.customTheme) preferences.updateApp { it.copy(customTheme = theme) }
        }
    }

    private fun fetchSourceHealth(server: String, session: String) {
        client.newCall(authed(Request.Builder().url("$server/api/sources"), session).build()).execute().use { r ->
            if (r.isSuccessful) onSourceHealth(parseDegraded(r.body?.string().orEmpty()))
        }
    }

    /** What the connected server has switched on and whether its home worker is up (GET /api/features). */
    suspend fun features(): ServerFeatures? = withContext(Dispatchers.IO) {
        val server = _state.value.server ?: return@withContext null
        val session = token ?: return@withContext null
        runCatching {
            client.newCall(authed(Request.Builder().url("$server/api/features"), session).build()).execute().use { r ->
                if (r.isSuccessful) parseFeatures(r.body?.string().orEmpty()) else null
            }
        }.getOrNull()
    }

    /** The connected server's address (for opening its web pages, e.g. a room), or null. */
    val serverUrl: String? get() = _state.value.server

    /**
     * A JSON call to the connected server's API with the session (social features); null when not connected,
     * throws with the server's message when it refuses.
     */
    suspend fun api(method: String, path: String, body: JsonObject? = null): JsonElement? = withContext(Dispatchers.IO) {
        val server = _state.value.server ?: return@withContext null
        val session = token ?: return@withContext null
        require(path.startsWith("/api/")) { "Ruta no válida" }
        val request = Request.Builder().url(server + path).method(method, body?.toString()?.toRequestBody(json) ?: if (method == "GET") null else ByteArray(0).toRequestBody(json))
        client.newCall(authed(request, session).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(errorOf(text) ?: tr("El servidor respondió {0}", r.code))
            if (text.isBlank()) null else JsonParser.parseString(text)
        }
    }

    /**
     * The session cookie for [url] when it is an address of the connected server (same scheme, host and port), so the
     * player can open its streams (the home Jellyfin); null for any other site, which never gets it.
     */

    /** The connected server and its session cookie, for the relay used when a source is blocked (F2.3). */
    fun relayTarget(): Pair<okhttp3.HttpUrl, String>? {
        val server = _state.value.server?.toHttpUrlOrNull() ?: return null
        return cookieFor(server)?.let { server to it }
    }

    fun cookieFor(url: okhttp3.HttpUrl): String? {
        val server = _state.value.server?.toHttpUrlOrNull() ?: return null
        val session = token ?: return null
        return if (sameServer(server, url)) "$COOKIE=$session" else null
    }

    /** Sources only the server has (its home Jellyfin), offered here while connected; none otherwise. */
    suspend fun hostedSources(): List<ServerHostedSource> = runCatching {
        api("GET", "/api/sources")?.asJsonArray?.toList().orEmpty().mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = o.get("id")?.asString ?: return@mapNotNull null
            if (id !in ServerHostedSource.HOSTED) return@mapNotNull null
            val types = o.getAsJsonArray("types")?.mapNotNull { t -> runCatching { com.nelson.yomukai.domain.model.MediaType.valueOf(t.asString) }.getOrNull() }
                ?.toSet()?.ifEmpty { null } ?: setOf(com.nelson.yomukai.domain.model.MediaType.ANIME)
            ServerHostedSource(this, id, o.get("name")?.asString ?: id, types)
        }
    }.getOrDefault(emptyList())

    /** A WebSocket to the connected server's API with the session (watch-party rooms); null when not connected. */

    /**
     * The server's live stream for this user (SSE: notifications and direct messages), as (event, data) pairs while
     * the collector runs; it reconnects after a drop. Used by the open conversation for «escribiendo» and «visto».
     */
    fun liveEvents(): kotlinx.coroutines.flow.Flow<Pair<String, String>> = kotlinx.coroutines.flow.flow {
        val streamClient = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
        while (true) {
            val server = _state.value.server ?: return@flow
            val session = token ?: return@flow
            runCatching {
                val call = streamClient.newCall(authed(Request.Builder().url("$server/api/notifications/stream"), session).build())
                call.execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val source = r.body?.source() ?: return@use
                    var event = "message"
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> emit(event to line.removePrefix("data:").trim())
                            line.isEmpty() -> event = "message"
                        }
                    }
                }
            }
            kotlinx.coroutines.delay(3_000)
        }
    }.flowOn(Dispatchers.IO)

    fun webSocket(path: String, listener: okhttp3.WebSocketListener): okhttp3.WebSocket? {
        val server = _state.value.server ?: return null
        val session = token ?: return null
        require(path.startsWith("/api/")) { "Ruta no válida" }
        val url = server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + path
        val socketClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()
        return socketClient.newWebSocket(authed(Request.Builder().url(url), session).build(), listener)
    }

    /** Saves a file of the connected server's API (e.g. a dictionary) to [target]; false when not connected or refused. */
    suspend fun download(path: String, target: java.io.File, maxBytes: Long): Boolean = withContext(Dispatchers.IO) {
        val server = _state.value.server ?: return@withContext false
        val session = token ?: return@withContext false
        require(path.startsWith("/api/")) { "Ruta no válida" }
        val part = java.io.File(target.parentFile, target.name + ".part")
        val ok = runCatching {
            client.newBuilder().readTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
                .newCall(authed(Request.Builder().url(server + path), session).build()).execute().use { r ->
                    val body = r.body
                    if (!r.isSuccessful || body == null || body.contentLength() > maxBytes) return@use false
                    part.parentFile?.mkdirs()
                    // Counted as it arrives: a response without a size cannot fill the phone.
                    var written = 0L
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                written += n
                                if (written > maxBytes) return@use false
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    written in 1..maxBytes && part.renameTo(target)
                }
        }.getOrDefault(false)
        if (!ok) part.delete()
        ok
    }

    /** A natural voice of the connected server (see the web's novel reader). */
    data class ServerVoice(val id: String, val label: String, val lang: String, val gender: String, val region: String)

    /** The natural voices the connected server can read with; empty when not connected or it has none. */
    suspend fun voices(): List<ServerVoice> = withContext(Dispatchers.IO) {
        val server = _state.value.server ?: return@withContext emptyList()
        val session = token ?: return@withContext emptyList()
        runCatching {
            client.newCall(authed(Request.Builder().url("$server/api/tts/voices"), session).build()).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                val root = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
                if (!root.bool("available")) return@use emptyList()
                root.array("voices").mapNotNull { e ->
                    val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    ServerVoice(o.str("id"), o.str("label"), o.str("lang"), o.str("gender"), o.str("region"))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** WAV audio of [text] read by the server's [voice]; throws when the server cannot. */
    suspend fun speak(text: String, voice: String, rate: Float): ByteArray = withContext(Dispatchers.IO) {
        val server = _state.value.server ?: throw IOException(tr("Sin servidor"))
        val session = token ?: throw IOException(tr("Sin sesión"))
        val body = obj("text" to text, "voice" to voice, "rate" to rate).toString().toRequestBody(json)
        client.newCall(authed(Request.Builder().url("$server/api/tts").post(body), session).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException(errorOf(r.body?.string()) ?: tr("El servidor respondió {0}", r.code))
            r.body?.bytes() ?: throw IOException(tr("Sin audio"))
        }
    }

    companion object {
        private const val COOKIE = "yk_session"

        /** Whether [url] is an address of [server] (scheme, host and port all equal): only those get the session. */
        fun sameServer(server: okhttp3.HttpUrl, url: okhttp3.HttpUrl): Boolean =
            server.scheme == url.scheme && server.host.equals(url.host, ignoreCase = true) && server.port == url.port
        private const val PROGRESS_BATCH = 5_000

        /** "yomukai.example.com" or "https://yomukai.example.com/" -> "https://yomukai.example.com". */

        /** Broken sources (id to reason) from the server's source list; never throws. */
        fun parseDegraded(text: String): Map<String, String> = runCatching {
            JsonParser.parseString(text).asJsonArray.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val reason = o.get("degraded")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                o.str("id").takeIf { it.isNotBlank() }?.let { it to reason }
            }.toMap()
        }.getOrDefault(emptyMap())

        /** Never throws: an unknown or old answer means everything off. */
        fun parseFeatures(text: String): ServerFeatures = runCatching {
            val root = JsonParser.parseString(text).asJsonObject
            val flags = root.getAsJsonObject("flags")?.entrySet()?.associate { (k, v) -> k to (v.takeIf { it.isJsonPrimitive }?.asBoolean == true) }.orEmpty()
            val worker = root.getAsJsonObject("worker") ?: JsonObject()
            val available = root.getAsJsonObject("available")?.entrySet()?.filter { (_, v) -> v.takeIf { it.isJsonPrimitive }?.asBoolean == true }?.map { it.key }?.toSet().orEmpty()
            ServerFeatures(flags, worker.bool("online"), worker.bool("gpu"), available)
        }.getOrDefault(ServerFeatures())

        /** [connect] answers this when the account has 2FA: ask for the code and call again with it. */
        val TWO_FACTOR_NEEDED: String get() = tr("Escribe el código de verificación en dos pasos")

        fun normalizeServer(input: String): String? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" && url.scheme != "http") return null
            return url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
        }

        /** Plain HTTP only in test builds, for a server on the computer running the emulator. */
        private fun allowsPlainHttp(server: String): Boolean {
            val host = server.toHttpUrlOrNull()?.host ?: return false
            return BuildConfig.DEBUG && (host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1")
        }

        private fun errorOf(text: String?): String? =
            runCatching { JsonParser.parseString(text).asJsonObject.get("error")?.asString }.getOrNull()

        private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject().apply {
            pairs.forEach { (k, v) ->
                when (v) {
                    null -> Unit
                    is String -> addProperty(k, v)
                    is Number -> addProperty(k, v)
                    is Boolean -> addProperty(k, v)
                    else -> addProperty(k, v.toString())
                }
            }
        }

        private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        private fun JsonObject.prim(key: String): JsonElement? = get(key)?.takeIf { it.isJsonPrimitive }
        private fun JsonObject.str(key: String) = prim(key)?.asString.orEmpty()
        private fun JsonObject.long(key: String) = prim(key)?.asLong ?: 0L
        private fun JsonObject.int(key: String) = prim(key)?.asInt ?: 0
        private fun JsonObject.double(key: String) = prim(key)?.asDouble ?: 0.0
        private fun JsonObject.bool(key: String) = prim(key)?.asBoolean ?: false
    }
}
