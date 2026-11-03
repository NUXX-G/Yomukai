package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.discovery.AniListClient
import com.nelson.yomukai.data.discovery.RandomKind
import com.nelson.yomukai.data.metadata.KitsuClient
import com.nelson.yomukai.data.metadata.MalClient
import com.nelson.yomukai.data.metadata.MangaUpdatesClient
import com.nelson.yomukai.data.metadata.MetadataRepository
import com.nelson.yomukai.data.metadata.MetadataStore
import com.nelson.yomukai.data.metadata.OpenLibraryClient
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.PublicationStatus
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("Yomukai")
private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

fun main() {
    val config = Config.fromEnv()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { yomukai(config) }.start(wait = true)
}

class Services(val config: Config) {
    val http = ServerHttp(config.flareSolverrUrl)
    val db = Database(File(config.dataDir, "yomukai.db"))
    val auth = Auth(db, config)
    val sources = SourceManager(http)
    val aniList = AniListClient(http)
    val metadata = MetadataRepository(
        aniList, KitsuClient(http), MalClient(http, config.malClientId), MetadataStore(config.dataDir),
        MangaUpdatesClient(http), OpenLibraryClient(http)
    )
    val proxy = MediaProxy(http, config.secret, File(config.dataDir, "cache"), config.guestStreams)
    val catalog = Catalog(sources, aniList, metadata, proxy)

    suspend fun prefs(user: User) = UserPrefs.parse(db.settings(user.id))
}

fun Application.yomukai(config: Config, services: Services = Services(config)) {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
    }
    install(ContentNegotiation) { gson { disableHtmlEscaping() } }
    install(Compression) {
        gzip { matchContentType(ContentType.Application.Json, ContentType.Text.Any, ContentType.Application.JavaScript) }
        deflate { matchContentType(ContentType.Application.Json, ContentType.Text.Any) }
    }
    install(SSE)
    install(StatusPages) {
        exception<NoSuchElementException> { call, e -> call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "No encontrado"))) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Petición no válida"))) }
        exception<Throwable> { call, e ->
            log.warn("Error en {}", call.request.local.uri, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error interno")))
        }
    }

    launch { services.auth.bootstrap() }
    // Guests whose week is over go away, with all their data, once an hour.
    launch {
        while (true) {
            runCatching { services.auth.cleanUpGuests() }.onFailure { log.warn("Limpieza de invitados", it) }
            delay(60 * 60_000L)
        }
    }

    routing {
        authRoutes(services)
        route("/api") {
            run {
                install(requireUser(services.auth))
                catalogRoutes(services)
                userRoutes(services)
                get("/img") { services.proxy.serveImage(call) }
                get("/stream") { services.proxy.serveStream(call, call.user) }
            }
            route("/admin") {
                install(requireUser(services.auth, admin = true))
                adminRoutes(services)
            }
        }
        webApp(config.webDir)
    }
    log.info("Yomukai escuchando en el puerto {} (datos en {})", config.port, config.dataDir.absolutePath)
}

suspend fun ApplicationCall.fail(e: Throwable) =
    respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "La fuente no respondió")))

private suspend inline fun <reified T : Any> ApplicationCall.reply(result: Result<T>) =
    result.fold({ respond(it) }, { fail(it) })

private fun ApplicationCall.q(name: String): String =
    request.queryParameters[name] ?: throw IllegalArgumentException("Falta el parámetro '$name'")

private fun Route.authRoutes(s: Services) {
    fun secure(call: ApplicationCall) = call.request.local.scheme == "https" || call.request.headers["X-Forwarded-Proto"] == "https"

    post("/auth/login") {
        val body = JsonParser.parseString(call.receiveText()).asJsonObject
        val name = body.get("name")?.asString.orEmpty().take(64)
        val password = body.get("password")?.asString.orEmpty().take(256)
        val keys = listOf("ip:" + clientAddress(call, s.config), "user:" + name.trim().lowercase())
        val wait = s.auth.throttle.waitFor(keys)
        if (wait > 0) {
            return@post call.respond(
                HttpStatusCode.TooManyRequests,
                mapOf("error" to "Demasiados intentos. Prueba otra vez en ${(wait / 60_000) + 1} min")
            )
        }
        val session = s.auth.login(name, password)
        if (session == null) {
            s.auth.throttle.failed(keys)
            return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Usuario o contraseña incorrectos"))
        }
        s.auth.throttle.succeeded(keys[1])
        call.response.cookies.append(s.auth.sessionCookie(session.second, secure(call)))
        call.respond(session.first.dto())
    }
    post("/auth/guest") {
        if (!s.config.guestsEnabled) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "El modo invitado está desactivado"))
        val (user, token) = s.auth.guest()
        call.response.cookies.append(s.auth.sessionCookie(token, secure(call)))
        call.respond(user.dto())
    }
    post("/auth/logout") {
        call.request.cookies[Auth.COOKIE]?.let { s.auth.logout(it) }
        call.response.cookies.append(Auth.COOKIE, "", maxAge = 0, path = "/")
        call.respond(mapOf("ok" to true))
    }
    get("/auth/me") {
        val user = s.auth.userFor(call.request.cookies[Auth.COOKIE])
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("guestsEnabled" to s.config.guestsEnabled, "guestDays" to s.config.guestDays))
        call.respond(mapOf("user" to user.dto(), "guestsEnabled" to s.config.guestsEnabled, "guestDays" to s.config.guestDays))
    }
}

private fun User.dto() = UserDto(name, isAdmin, isGuest, expiresAt)

/** The visitor's address; behind a reverse proxy (TRUST_PROXY, on by default) the one it forwards. */
private fun clientAddress(call: ApplicationCall, config: Config): String {
    if (config.trustProxy) {
        call.request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return call.request.local.remoteAddress
}

private fun Route.adminRoutes(s: Services) {
    get("/users") {
        call.respond(mapOf("users" to s.db.users().map { it.dto() + ("id" to it.id) }, "guests" to s.db.guestCount()))
    }
    post("/users") {
        val body = JsonParser.parseString(call.receiveText()).asJsonObject
        val name = body.get("name")?.asString?.trim().orEmpty()
        require(name.matches(Regex("[\\p{L}\\p{N}_.-]{2,32}"))) { "Nombre de 2 a 32 letras, números, punto, guion o guion bajo" }
        require(s.db.userByName(name) == null) { "Ya existe un usuario '$name'" }
        val password = body.get("password")?.asString?.takeIf { it.length >= MIN_PASSWORD } ?: Passwords.generate()
        s.db.createUser(name, Passwords.hash(password), admin = body.get("admin")?.asBoolean == true, guest = false, expiresAt = null)
        call.respond(mapOf("name" to name, "password" to password))
    }
    post("/users/{id}/password") {
        val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Usuario no válido")
        val password = Passwords.generate()
        s.db.setPassword(id, Passwords.hash(password))
        s.auth.forget(id)
        call.respond(mapOf("password" to password))
    }
    delete("/users/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Usuario no válido")
        require(id != call.user.id) { "No puedes borrarte a ti mismo" }
        s.db.deleteUser(id)
        s.auth.forget(id)
        call.respond(mapOf("ok" to true))
    }
}

private operator fun UserDto.plus(pair: Pair<String, Any?>): Map<String, Any?> =
    mapOf("name" to name, "admin" to admin, "guest" to guest, "expiresAt" to expiresAt, pair)

private fun Route.catalogRoutes(s: Services) {
    get("/sources") {
        val prefs = s.prefs(call.user)
        val enabled = s.sources.enabled(prefs.enabledSources).map { it.id }.toSet()
        call.respond(s.sources.all.map { it.toDto(it.id in enabled) })
    }
    get("/sources/{id}/latest") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        call.reply(s.catalog.latest(call.parameters["id"]!!, page, s.prefs(call.user)))
    }
    get("/sources/{id}/browse") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        call.reply(s.catalog.browse(call.parameters["id"]!!, page, call.request.queryParameters["f"], s.prefs(call.user)))
    }
    sse("/search") {
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        if (query.isBlank()) return@sse
        val types = call.request.queryParameters["types"]?.split(',')?.mapNotNull { runCatching { MediaType.valueOf(it) }.getOrNull() }?.toSet()
        val language = call.request.queryParameters["lang"]?.takeIf { it == "ES" || it == "EN" }
        val prefs = s.prefs(call.user)
        s.catalog.search(query, types, language, prefs) { update -> send(ServerSentEvent(gson.toJson(update), event = "update")) }
        send(ServerSentEvent("{}", event = "end"))
    }
    get("/media") { call.reply(s.catalog.media(call.q("s"), call.q("id")).map(s.catalog.dtos::media)) }
    get("/chapters") {
        val status = call.request.queryParameters["status"]?.let { runCatching { PublicationStatus.valueOf(it) }.getOrNull() }
        call.reply(s.catalog.chapters(call.q("s"), call.q("id"), status))
    }
    get("/pages") { call.reply(s.catalog.pages(call.q("s"), call.q("c"))) }
    get("/text") { call.reply(s.catalog.text(call.q("s"), call.q("c")).map { mapOf("text" to it) }) }
    get("/videos") { call.reply(s.catalog.videos(call.q("s"), call.q("c"))) }
    get("/metadata") { call.reply(s.catalog.metadata(call.q("s"), call.q("id"))) }
    get("/similar") { call.reply(s.catalog.similar(call.user, s.prefs(call.user), call.q("s"), call.q("id"))) }
    get("/alternatives") { call.reply(s.catalog.alternatives(call.user, s.prefs(call.user), call.q("s"), call.q("id"))) }
    post("/locate") {
        val related = gson.fromJson(call.receiveText(), RelatedDto::class.java)
        call.reply(s.catalog.locate(call.user, s.prefs(call.user), s.catalog.relatedFrom(related)))
    }
    get("/random") {
        val kind = call.request.queryParameters["kind"]?.let { runCatching { RandomKind.valueOf(it) }.getOrNull() } ?: RandomKind.ANY
        val genre = call.request.queryParameters["genre"]?.ifBlank { null }
        val result = s.catalog.random(call.user, s.prefs(call.user), kind, genre)
        result.fold(
            { found ->
                if (found == null) call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Sin conexión ahora mismo. Prueba en un momento."))
                else call.respond(mapOf("pick" to found.first, "places" to found.second))
            },
            { call.fail(it) }
        )
    }
    get("/foryou") {
        val user = call.user
        val history = s.db.history(user.id, 24).map { MediaItem(it.mediaId, it.title, it.cover, typeOf(it.type), "", "", it.sourceId) }
        val library = s.db.library(user.id).map { MediaItem(it.mediaId, it.title, it.cover, typeOf(it.type), it.status, "", it.sourceId) }
        // Recent history and library in turns: what was just read says as much as what was kept.
        val seen = history.zip(library).flatMap { listOf(it.first, it.second) } + history.drop(library.size) + library.drop(history.size)
        call.reply(s.catalog.forYou(user, s.prefs(user), seen))
    }
}

private fun typeOf(name: String) = runCatching { MediaType.valueOf(name) }.getOrDefault(MediaType.MANGA)

private fun Route.userRoutes(s: Services) {
    get("/library") { call.respond(s.db.library(call.user.id)) }
    put("/library") {
        val row = gson.fromJson(call.receiveText(), LibraryRow::class.java)
        s.db.putLibrary(call.user.id, row.copy(addedAt = row.addedAt.takeIf { it > 0 } ?: System.currentTimeMillis()))
        call.respond(mapOf("ok" to true))
    }
    delete("/library") {
        s.db.removeLibrary(call.user.id, call.q("s"), call.q("id"))
        call.respond(mapOf("ok" to true))
    }
    get("/progress") { call.respond(s.db.progress(call.user.id, call.q("s"), call.q("id"))) }
    post("/progress") {
        val body = JsonParser.parseString(call.receiveText()).asJsonObject
        val rows = body.getAsJsonArray("rows")?.map { gson.fromJson(it, ProgressRow::class.java) }.orEmpty()
        if (rows.size > MAX_PROGRESS_ROWS) throw IllegalArgumentException("Demasiados capítulos de una vez")
        s.db.putProgress(call.user.id, rows)
        body.getAsJsonObject("history")?.let { s.db.putHistory(call.user.id, gson.fromJson(it, HistoryRow::class.java)) }
        call.respond(mapOf("ok" to true))
    }
    get("/history") { call.respond(s.db.history(call.user.id)) }
    delete("/history") {
        s.db.removeHistory(call.user.id, call.q("s"), call.q("id"))
        call.respond(mapOf("ok" to true))
    }
    get("/settings") { call.respondText(s.db.settings(call.user.id) ?: "{}", ContentType.Application.Json) }
    put("/settings") {
        val text = call.receiveText()
        require(text.length < MAX_SETTINGS_CHARS) { "Ajustes demasiado grandes" }
        JsonParser.parseString(text).asJsonObject
        s.db.putSettings(call.user.id, text)
        call.respond(mapOf("ok" to true))
    }
    get("/export") {
        val user = call.user
        val export = JsonObject().apply {
            addProperty("format", "yomukai-export")
            addProperty("version", 1)
            addProperty("exportedAt", System.currentTimeMillis())
            addProperty("user", user.name)
            add("library", gson.toJsonTree(s.db.library(user.id)))
            add("progress", gson.toJsonTree(s.db.allProgress(user.id)))
            add("history", gson.toJsonTree(s.db.history(user.id, Int.MAX_VALUE)))
            add("settings", s.db.settings(user.id)?.let { JsonParser.parseString(it) } ?: JsonObject())
        }
        val day = java.time.LocalDate.now().toString()
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "yomukai-${user.name}-$day.json").toString()
        )
        call.respondText(gson.toJson(export), ContentType.Application.Json)
    }
    post("/import") {
        val text = call.receiveText()
        require(text.length < MAX_IMPORT_CHARS) { "El archivo es demasiado grande" }
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        require(root?.get("format")?.asString == "yomukai-export") { "No es una copia de Yomukai" }
        val library = root!!.getAsJsonArray("library")?.map { gson.fromJson(it, LibraryRow::class.java) }.orEmpty()
        val progress = root.getAsJsonArray("progress")?.map { gson.fromJson(it, ProgressRow::class.java) }.orEmpty()
        val history = root.getAsJsonArray("history")?.map { gson.fromJson(it, HistoryRow::class.java) }.orEmpty()
        val settings = root.getAsJsonObject("settings")?.toString()
        if (call.request.queryParameters["mode"] == "replace") {
            s.db.replaceAll(call.user.id, library, progress, history, settings)
        } else {
            // Merging keeps what is already here and adds what the copy brings.
            s.db.tx {
                with(s.db) {
                    library.forEach { upsertLibrary(call.user.id, it) }
                    progress.forEach { upsertProgress(call.user.id, it) }
                    history.forEach { upsertHistory(call.user.id, it) }
                    if (settings != null) upsertSettings(call.user.id, settings)
                }
            }
        }
        call.respond(mapOf("library" to library.size, "progress" to progress.size, "history" to history.size))
    }
}

/** The built web app; any path that is not a file is a page of the app and gets index.html. */
private fun Route.webApp(dir: File) {
    val index = File(dir, "index.html")
    staticFiles("/", dir) {
        default("index.html")
        cacheControl { file ->
            // Built files carry a hash in their name and never change; index.html must always be fresh.
            if (file.name == "index.html" || file.name == "sw.js") listOf(io.ktor.http.CacheControl.NoCache(null))
            else listOf(io.ktor.http.CacheControl.MaxAge(31_536_000, visibility = io.ktor.http.CacheControl.Visibility.Public))
        }
    }
    get("{...}") {
        if (call.request.local.uri.startsWith("/api/")) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Ruta desconocida"))
        if (index.exists()) {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondFile(index)
        } else {
            call.respondText("Yomukai: falta la web compilada en ${dir.absolutePath}", status = HttpStatusCode.NotFound)
        }
    }
}

private const val MAX_PROGRESS_ROWS = 5_000
private const val MIN_PASSWORD = 8
private const val MAX_SETTINGS_CHARS = 256 * 1024
private const val MAX_IMPORT_CHARS = 32 * 1024 * 1024
