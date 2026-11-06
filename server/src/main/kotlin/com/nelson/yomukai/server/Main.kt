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
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("Yomukai")
private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

fun main(args: Array<String>) {
    val config = Config.fromEnv()
    if (args.isNotEmpty()) kotlin.system.exitProcess(runCommand(config, args.toList()))
    val lock = ServerLock.tryAcquire(config.dataDir)
        ?: error("Ya hay un servidor de Yomukai usando ${config.dataDir} (o una restauración en curso)")
    Runtime.getRuntime().addShutdownHook(Thread { lock.close() })
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { yomukai(config) }.start(wait = true)
}

private fun backupsOf(config: Config) =
    Backups(File(config.dataDir, "yomukai.db"), config.backupDir, config.backupKeepDays, config.backupCopyDir)

/** `server backup`, `server backups`, `server restore <archivo|latest>`: maintenance from the command line. */
fun runCommand(config: Config, args: List<String>): Int {
    val dbFile = File(config.dataDir, "yomukai.db")
    return when (args.first()) {
        "backup" -> {
            val file = kotlinx.coroutines.runBlocking { backupsOf(config).backup(Database(dbFile)) }
            println("Copia hecha: ${file.absolutePath}")
            0
        }
        // Run by cron on the VPS (never on GitHub: Cloudflare blocks its addresses). See docs/VPS.md.
        "canary" -> {
            val services = Services(config)
            val wanted = args.drop(1).toSet()
            val targets = services.sources.all.filter { wanted.isEmpty() || it.id in wanted }
            val results = kotlinx.coroutines.runBlocking { services.canary.checkAll(targets) }
            results.forEach { println("${it.source}: ${it.status} — ${it.summary}") }
            // Give the notifier a moment to send what it queued.
            Thread.sleep(3_000)
            if (results.any { it.status == com.nelson.yomukai.data.source.SourceHealth.DEGRADED }) 3 else 0
        }
        "backups" -> {
            Backups.list(config.backupDir).forEach { println("${it.name}  ${it.length() / 1024} KB") }
            0
        }
        "restore" -> {
            val arg = args.getOrNull(1) ?: run {
                System.err.println("Uso: server restore <archivo .db.gz | latest>")
                return 2
            }
            val backup = if (arg == "latest") Backups.list(config.backupDir).firstOrNull()
                ?: run { System.err.println("No hay copias en ${config.backupDir}"); return 1 }
            else File(arg).let { if (it.isFile) it else File(config.backupDir, arg) }
            val lock = ServerLock.tryAcquire(config.dataDir) ?: run {
                System.err.println("El servidor está en marcha: páralo antes de restaurar (docker compose stop yomukai)")
                return 1
            }
            lock.use {
                val previous = Backups.restore(backup, dbFile)
                println("Restaurada ${backup.name}." + (previous?.let { " La base anterior queda en ${it.name}" } ?: ""))
            }
            0
        }
        else -> {
            System.err.println("Comandos: backup | backups | restore <archivo|latest> | canary [fuente...]")
            2
        }
    }
}

class Services(val config: Config) {
    val http = ServerHttp(config.flareSolverrUrl)
    val db = Database(File(config.dataDir, "yomukai.db"))
    val auth = Auth(db, config)
    val sources = SourceManager(http, null).also { manager ->
        http.solverHosts = manager.all.mapNotNull { it.baseUrl.toHttpUrlOrNull()?.host?.lowercase()?.removePrefix("www.") }.toSet()
    }
    val aniList = AniListClient(http)
    val metadata = MetadataRepository(
        aniList, KitsuClient(http), MalClient(http, config.malClientId), MetadataStore(config.dataDir),
        MangaUpdatesClient(http), OpenLibraryClient(http)
    )
    val proxy = MediaProxy(http, config.secret, File(config.dataDir, "cache"), config.guestStreams, config.imageCacheBytes)
    val catalog = Catalog(sources, aniList, metadata, proxy)
    val downloads = Downloader(catalog, proxy, http)

    /** Natural voices for reading novels aloud (Piper, installed in the Docker image). */
    val speech = Speech(http, config.dataDir, File(System.getenv("PIPER_PATH")?.ifBlank { null } ?: "/opt/piper/piper"))

    val notifier = Notifier(config.discordWebhook, config.ntfyUrl, config.ntfyToken)

    val features = FeatureFlags(db)
    val workers = WorkerRegistry(db)
    val workerLimiter = RateLimiter()
    val jobs = JobQueue(db, ResultCache(db, File(config.dataDir, "results"), config.resultCacheBytes))
    val bandwidth = Bandwidth(db, config.bandwidthCapBytes, notifier).also { meter -> proxy.onStreamBytes = meter::record }
    val invites = InviteStore(db)
    val audit = AuditLog(db)
    val setup = SetupWizard(db, config.dataDir)
    val addressLimiter = KeyedRateLimiter()
    val health = SourceHealthStore(db)

    /** Signed source configurations: the bundled ones, then newer ones from the repository (see SourceBundles). */
    val bundles = com.nelson.yomukai.data.source.declarative.SourceBundleUpdater(http, File(config.dataDir, "sources"), config.sourcesUrl)
    val canary = Canary(health, notifier, pauseMs = config.canaryPauseMs, onBroken = { source, steps ->
        // A broken source asks the home worker for a fix proposal (a PR), at most once a day per source.
        if (features.isOn("source_repair")) {
            val payload = com.google.gson.JsonObject().apply {
                addProperty("sourceId", source.id)
                addProperty("report", steps.joinToString("\n") { (if (it.ok) "OK " else "FALLO ") + it.name + ": " + it.detail })
            }
            val day = System.currentTimeMillis() / Auth.DAY_MS
            jobs.enqueue(JobQueue.Request(JobTypes.SOURCE_REPAIR, payload, JobPriority.BACKGROUND, dedupKey = "repair:${source.id}:$day", ttlMs = Auth.DAY_MS, maxAttempts = 1))
        }
    })
    val works = Works(db, catalog, aniList, sources).also { w ->
        catalog.workKeys = w::knownKeys
        catalog.dtos.betterCover = w::betterCover
    }
    val events = Events(db)
    val notifications = Notifications(db, notifier, config.pushUrl, config.publicUrl)
    val rooms = Rooms(db, events, onOpen = { who, title -> notifications.discord("$who ha abierto una sala: $title") })
    val forum = Forum(db, events, notifications, config.dataDir)
    val themes = ThemesService(db, events, config.dataDir)
    val voices by lazy { VoiceRouter(this) }
    val ocr by lazy { OcrStore(this) }
    val words = Words(db, events)
    val integrations = Integrations(db, config.secret)
    val language by lazy { WorkerLanguage(this) }
    private val animeCheck: (String) -> Boolean = { id -> sources.get(id) is com.nelson.yomukai.data.source.AnimeSource }
    val social by lazy { Social(db, events, config.dataDir, animeCheck, works::keyOf, notifications::send) }
    val achievements = Achievements(db, events, animeCheck, notifications::send).also { a -> events.onEvent = { user, kind -> a.check(user, kind) } }
    val discover = Discover(com.nelson.yomukai.data.discovery.AniListDiscover(http), db, catalog, works) { proxy.cover(it, emptyMap()) }
    val ytdlp = YtDlp(
        File(System.getenv("YTDLP_PATH")?.ifBlank { null } ?: "/opt/yt-dlp"),
        allowedHosts = YtDlp.DEFAULT_HOSTS + System.getenv("YTDLP_HOSTS").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.contains('.') }
    )

    init {
        bundles.current()?.let(sources::apply)
        if (ytdlp.available) {
            com.nelson.yomukai.data.source.anime.VideoExtractors.preferred = { video -> if (features.isOn("ytdlp")) ytdlp.resolve(video) else null }
        }
        sources.onDomainChange = { id, from, to ->
            notifier.admin("domain-$id-$to", "La fuente $id cambió de dominio", "Ahora está en $to (antes $from). Se sigue sola; actualiza baseUrl y domains en sources/$id.json.")
        }
    }
    suspend fun prefs(user: User) = UserPrefs.parse(db.settings(user.id), sources.defaultEnabledIds)
}

/** Everything the server does on its own: start-up work, sweeps, the canary's view, backups, resolvers. */
private fun Application.startBackgroundJobs(config: Config, services: Services) {
    launch { runCatching { refreshDegraded(services.health, services.sources) } }
    // Top AniList titles are looked for in the sources, one every 20 s, so the home rows can open them directly.
    launch {
        while (true) {
            delay(20_000L)
            runCatching { services.discover.resolveNext() }.onFailure { log.debug("Descubrir: {}", it.message) }
        }
    }
    // Library and history rows get their work a few at a time (AniList allows ~30 requests a minute).
    launch {
        runCatching { services.works.loadKnown() }
        while (true) {
            delay(2 * 60_000L)
            runCatching { services.works.backfill() }.onFailure { log.debug("Obras pendientes: {}", it.message) }
        }
    }
    launch {
        while (true) {
            runCatching { services.bundles.fetchNewer(services.sources.bundleVersion) }
                .onSuccess { b ->
                    if (b != null) {
                        services.sources.apply(b)
                        log.info("Fuentes actualizadas a la versión {} ({} declarativas)", b.version, b.configs.size)
                        if (b.rejected.isNotEmpty()) services.notifier.admin("bundle-rejected-" + b.version, "Configuraciones de fuente rechazadas", b.rejected.joinToString("\n"))
                    }
                }
                .onFailure { log.info("Sin paquete de fuentes nuevo: {}", it.message) }
            delay(6 * 3_600_000L)
        }
    }
    launch {
        services.auth.bootstrap()
        services.setup.prepare(config.publicUrl)
    }
    if (config.backupsEnabled) backupsOf(config).schedule(this, services.db, config.backupHour)
    // Every minute: job leases and expiry, the traffic meter, and a warning when every worker has gone quiet.
    launch {
        var workersWereOnline = false
        while (true) {
            delay(60_000L)
            runCatching { services.jobs.sweep() }.onFailure { log.warn("Limpieza de la cola", it) }
            runCatching { refreshDegraded(services.health, services.sources) }.onFailure { log.warn("Salud de las fuentes", it) }
            runCatching { services.bandwidth.flush() }.onFailure { log.warn("Contador de tráfico", it) }
            runCatching {
                val online = services.workers.online().isNotEmpty()
                if (workersWereOnline && !online) {
                    services.notifier.admin(
                        "workers-offline", "Servidor de casa apagado",
                        "Ningún worker responde. Los trabajos pesados esperarán en la cola hasta que vuelva."
                    )
                }
                workersWereOnline = online
            }
        }
    }
    // Guests whose week is over go away, with all their data, once an hour.
    launch {
        while (true) {
            runCatching { services.auth.cleanUpGuests() }.onFailure { log.warn("Limpieza de invitados", it) }
            runCatching { services.rooms.cleanup(); services.events.cleanup(); services.notifications.cleanup() }.onFailure { log.warn("Limpieza de salas", it) }
            delay(60 * 60_000L)
        }
    }
}

fun Application.yomukai(config: Config, services: Services = Services(config)) {
    install(DefaultHeaders)
    install(securityHeaders(config))
    install(addressRateLimit(config, services.addressLimiter) { clientAddress(it, config) })
    install(ContentNegotiation) { gson { disableHtmlEscaping() } }
    install(Compression) {
        gzip { matchContentType(ContentType.Application.Json, ContentType.Text.Any, ContentType.Application.JavaScript) }
        deflate { matchContentType(ContentType.Application.Json, ContentType.Text.Any) }
    }
    install(SSE)
    install(io.ktor.server.websocket.WebSockets) {
        pingPeriodMillis = 20_000
        timeoutMillis = 45_000
        maxFrameSize = Rooms.MAX_FRAME.toLong()
        masking = false
    }
    install(StatusPages) {
        exception<NoSuchElementException> { call, e -> call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "No encontrado"))) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Petición no válida"))) }
        exception<Throwable> { call, e ->
            log.warn("Error en {}", call.request.local.uri, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error interno")))
        }
    }

    startBackgroundJobs(config, services)

    routing {
        authRoutes(services)
        accountPublicRoutes(services, { clientAddress(it, config) }, { isSecure(it, config) })
        route("/api") {
            run {
                install(requireUser(services.auth) { LoginThrottle.addressKey(clientAddress(it, config)) })
                catalogRoutes(services)
                userRoutes(services)
                get("/img") { services.proxy.serveImage(call) }
                get("/stream") {
                    if (!call.user.isAdmin && services.bandwidth.blocked(services.features.isOn("bandwidth_cap"))) {
                        return@get call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Se ha llegado al tope mensual de vídeo por el servidor. Prueba un servidor directo o espera al mes que viene")
                        )
                    }
                    services.proxy.serveStream(call, call.user)
                }
                featureRoutes(services)
                accountUserRoutes(services)
                workRoutes(services)
                discoverRoutes(services)
                playbackRoutes(services)
                roomRoutes(services) { clientAddress(it, config) }
                socialRoutes(services)
                achievementRoutes(services)
                forumRoutes(services)
                notificationRoutes(services)
                themeRoutes(services)
                learningRoutes(services)
                workerLanguageRoutes(services)
                // Files for the user's device. GET for one chapter (a plain link), POST from a form for many.
                get("/download") {
                    val p = call.request.queryParameters
                    services.downloads.serve(call, call.user, p["s"].orEmpty(), p["id"].orEmpty(), p.getAll("c").orEmpty().take(MAX_DOWNLOAD_IDS), p["t"])
                }
                post("/download") {
                    val p = call.receiveParameters()
                    val ids = p.getAll("c").orEmpty() + p["ids"].orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    services.downloads.serve(call, call.user, p["s"].orEmpty(), p["id"].orEmpty(), ids.take(MAX_DOWNLOAD_IDS), p["t"])
                }
            }
            route("/admin") {
                install(requireUser(services.auth, admin = true) { clientAddress(it, config) })
                install(auditAdminActions(services.audit) { clientAddress(it, config) })
                adminRoutes(services)
                accountAdminRoutes(services)
                workerAdminRoutes(services)
                forumAdminRoutes(services)
                integrationAdminRoutes(services)
                get("/status") { call.respond(mapOf("bandwidth" to services.bandwidth.summary(), "memory" to memoryStatus())) }
            }
        }
        workerRoutes(services)
        webApp(config.webDir)
    }
    log.info("Yomukai escuchando en el puerto {} (datos en {})", config.port, config.dataDir.absolutePath)
}

/** JVM memory as the server sees it (heap in use, heap limit, process RSS when the OS tells). */
fun memoryStatus(): Map<String, Long?> {
    val rt = Runtime.getRuntime()
    val rss = runCatching { java.io.File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLong()?.times(1024) }.getOrNull()
    return mapOf("heapUsed" to rt.totalMemory() - rt.freeMemory(), "heapMax" to rt.maxMemory(), "rss" to rss)
}

suspend fun ApplicationCall.fail(e: Throwable) =
    respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "La fuente no respondió")))

private suspend inline fun <reified T : Any> ApplicationCall.reply(result: Result<T>) =
    result.fold({ respond(it) }, { fail(it) })

private fun ApplicationCall.q(name: String): String =
    request.queryParameters[name] ?: throw IllegalArgumentException("Falta el parámetro '$name'")

private fun Route.authRoutes(s: Services) {
    fun secure(call: ApplicationCall) = isSecure(call, s.config)

    post("/auth/login") {
        val body = JsonParser.parseString(call.receiveText()).asJsonObject
        val name = body.get("name")?.asString.orEmpty().take(64)
        val password = body.get("password")?.asString.orEmpty().take(256)
        // Limited per address and per address+account: nobody can lock a friend out from elsewhere.
        val ip = LoginThrottle.addressKey(clientAddress(call, s.config))
        val account = name.trim().lowercase()
        val keys = listOf("ip:$ip", "pair:$ip|$account")
        if (!s.auth.throttle.begin(keys)) {
            val wait = s.auth.throttle.waitFor(keys).coerceAtLeast(1)
            return@post call.respond(
                HttpStatusCode.TooManyRequests,
                mapOf("error" to "Demasiados intentos. Prueba otra vez en ${(wait / 60_000) + 1} min")
            )
        }
        val result = try {
            // Many failures against this account from anywhere slow every attempt on it down.
            s.auth.throttle.accountDelay(account).takeIf { it > 0 }?.let { delay(it) }
            s.auth.login(name, password, deviceOf(call.request.headers["User-Agent"]), clientAddress(call, s.config))
        } catch (e: Throwable) {
            s.auth.throttle.finish(keys, success = false)
            throw e
        }
        s.auth.throttle.finish(keys, success = result !is Auth.LoginResult.Failed)
        if (result is Auth.LoginResult.Failed) {
            s.auth.throttle.accountFailed(account)
            return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Usuario o contraseña incorrectos"))
        }
        // Right password, 2FA on: no session yet, the code page finishes the login.
        if (result is Auth.LoginResult.NeedsCode) return@post call.respond(mapOf("twoFactor" to true, "challenge" to result.challenge))
        val session = (result as Auth.LoginResult.Session).let { it.user to it.token }
        if (session.first.suspendedAt != null) {
            s.auth.logout(session.second)
            return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to SUSPENDED_MESSAGE, "suspended" to true))
        }
        call.response.cookies.append(s.auth.sessionCookie(session.second, secure(call)))
        call.respond(session.first.dto())
    }
    post("/auth/guest") {
        if (!s.config.guestsEnabled) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "El modo invitado está desactivado"))
        s.auth.guestAllowedFrom(LoginThrottle.addressKey(clientAddress(call, s.config)))?.let {
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to it))
        }
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
            ?: return@get call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("guestsEnabled" to s.config.guestsEnabled, "guestDays" to s.config.guestDays, "setupNeeded" to s.setup.needed())
            )
        if (user.suspendedAt != null) {
            return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to SUSPENDED_MESSAGE, "suspended" to true, "guestsEnabled" to s.config.guestsEnabled, "guestDays" to s.config.guestDays))
        }
        call.respond(mapOf("user" to user.dto(), "guestsEnabled" to s.config.guestsEnabled, "guestDays" to s.config.guestDays))
    }
}

private fun User.dto() = UserDto(name, isAdmin, isGuest, expiresAt)

/**
 * The visitor's address. With TRUST_PROXY (off by default; turn it on only behind a reverse proxy
 * such as nginx or Caddy) the last X-Forwarded-For entry is used: the one the proxy itself added.
 * Earlier entries come from the visitor and can be anything.
 */
private fun clientAddress(call: ApplicationCall, config: Config): String {
    val peer = call.request.local.remoteAddress
    // Forwarded headers count only when the connection itself comes from the proxy: a private
    // address on the same machine or Docker network. A visitor reaching the port directly cannot.
    val fromProxy = runCatching { !PublicAddresses.isPublic(java.net.InetAddress.getByName(peer)) }.getOrDefault(false)
    if (config.trustProxy && fromProxy) {
        call.request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        call.request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return peer
}

private fun Route.adminRoutes(s: Services) {
    get("/users") {
        call.respond(mapOf("users" to s.db.users().map {
            it.dto() + mapOf("id" to it.id, "suspended" to (it.suspendedAt != null), "suspendedAt" to it.suspendedAt, "suspendedReason" to it.suspendedReason)
        }, "guests" to s.db.guestCount()))
    }
    post("/users/{id}/reactivate") {
        s.auth.reactivate(call.parameters["id"]!!.toLong())
        call.respond(mapOf("ok" to true))
    }
    post("/users") {
        val body = JsonParser.parseString(call.receiveText()).asJsonObject
        val name = body.get("name")?.asString?.trim().orEmpty()
        require(name.matches(Regex("[\\p{L}\\p{N}_.-]{2,32}"))) { "Nombre de 2 a 32 letras, números, punto, guion o guion bajo" }
        require(s.db.userByName(name) == null) { "Ya existe un usuario '$name'" }
        val password = body.get("password")?.asString?.takeIf { it.length >= MIN_PASSWORD } ?: Passwords.generate()
        s.db.createUser(name, Passwords.hash(password), admin = body.get("admin")?.asBoolean == true, guest = false, expiresAt = null)
        call.auditDetail("usuario $name")
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
        call.auditDetail("usuario " + (s.db.userById(id)?.name ?: id.toString()))
        s.db.deleteUser(id)
        s.auth.forget(id)
        call.respond(mapOf("ok" to true))
    }
}

private operator fun UserDto.plus(pair: Pair<String, Any?>): Map<String, Any?> =
    mapOf("name" to name, "admin" to admin, "guest" to guest, "expiresAt" to expiresAt, pair)

private operator fun UserDto.plus(extra: Map<String, Any?>): Map<String, Any?> =
    mapOf("name" to name, "admin" to admin, "guest" to guest, "expiresAt" to expiresAt) + extra

private fun Route.catalogRoutes(s: Services) {
    get("/sources") {
        val prefs = s.prefs(call.user)
        // "enabled" is what the user chose; broken sources are flagged so lists hide them and Settings says why.
        val chosen = prefs.enabledSources ?: s.sources.defaultEnabledIds
        call.respond(s.sources.all.map { it.toDto(it.id in chosen, s.sources.degraded[it.id]) })
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
    voiceRoutes(s)
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
    // New chapters of every saved title, counted from the last one read. Chapter lists are cached
    // for a few minutes, so opening the library again costs nothing.
    get("/updates") {
        val user = call.user
        val now = System.currentTimeMillis()
        UpdateLimits.recent[user.id]?.takeIf { now - it.first < UpdateLimits.REUSE_MS }?.let { return@get call.respond(it.second) }
        // One run per person at a time: a second click waits for nothing and costs the sites nothing.
        if (!UpdateLimits.running.add(user.id)) {
            return@get call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Ya se están buscando novedades"))
        }
        try {
        val enabled = s.catalog.enabled(s.prefs(user)).map { it.id }.toSet()
        val library = s.db.library(user.id).filter { it.sourceId in enabled }.take(MAX_UPDATE_TITLES)
        val progress = s.db.allProgress(user.id).groupBy { it.sourceId + "|" + it.mediaId }
        // Lists already in memory are free; only a limited number per run may be fetched from the sites.
        var budget = if (user.isGuest) UpdateLimits.GUEST_FETCHES else UpdateLimits.ACCOUNT_FETCHES
        val allowed = library.filter { row ->
            s.catalog.hasCachedChapters(row.sourceId, row.mediaId) || budget-- > 0
        }
        val rows = kotlinx.coroutines.coroutineScope {
            allowed.map { row ->
                async {
                    UpdateLimits.gate.withPermit {
                        val list = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                            s.catalog.chapters(row.sourceId, row.mediaId, null).getOrNull()
                        } ?: return@withPermit null
                        val chapters = list.chapters
                        val read = progress[row.sourceId + "|" + row.mediaId].orEmpty().filter { it.read }.map { it.chapterId }.toSet()
                        val lastRead = chapters.lastOrNull { it.id in read }?.index
                        val latest = chapters.lastOrNull()
                        mapOf(
                            "sourceId" to row.sourceId, "mediaId" to row.mediaId, "total" to chapters.size,
                            "unread" to if (lastRead == null) null else chapters.count { it.index > lastRead },
                            "next" to chapters.firstOrNull { lastRead == null || it.index > lastRead }?.id,
                            "latestLabel" to latest?.label, "lastUpdate" to list.lastUpdate
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
        UpdateLimits.recent[user.id] = now to rows
        if (UpdateLimits.recent.size > 2000) UpdateLimits.recent.entries.removeIf { now - it.value.first > UpdateLimits.REUSE_MS }
        call.respond(rows)
        } finally {
            UpdateLimits.running.remove(user.id)
        }
    }
}

private const val MAX_UPDATE_TITLES = 300

/** Keeps the new-chapters check from turning one request into hundreds aimed at the sites. */
private object UpdateLimits {
    /** Shared by everyone: at most this many chapter lists are fetched at once. */
    val gate = kotlinx.coroutines.sync.Semaphore(8)
    val running: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val recent = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, List<Map<String, Any?>>>>()
    const val REUSE_MS = 2 * 60_000L
    const val ACCOUNT_FETCHES = 60
    const val GUEST_FETCHES = 20
}

private fun typeOf(name: String) = runCatching { MediaType.valueOf(name) }.getOrDefault(MediaType.MANGA)

private fun Route.userRoutes(s: Services) {
    // Covers are swapped for AniList's large one when the title is joined to a work (sources often give thumbnails).
    get("/library") {
        call.respond(s.db.library(call.user.id).map { r -> s.works.betterCover(r.sourceId, r.mediaId)?.let { r.copy(cover = s.proxy.cover(it, emptyMap())) } ?: r })
    }
    put("/library") {
        val text = call.receiveText()
        require(text.length < MAX_ROW_CHARS) { "Demasiado grande" }
        val json = JsonParser.parseString(text).asJsonObject
        val row = gson.fromJson(json, LibraryRow::class.java)
        require(!row.sourceId.isNullOrBlank() && !row.mediaId.isNullOrBlank()) { "Falta la obra" }
        val now = System.currentTimeMillis()
        // A save that does not mention categories keeps the ones the title already has.
        val categories = if (json.has("categories")) row.categories else s.db.libraryRow(call.user.id, row.sourceId, row.mediaId)?.categories
        val isNew = s.db.libraryRow(call.user.id, row.sourceId, row.mediaId)?.deleted != false
        s.db.putLibrary(call.user.id, row.copy(addedAt = row.addedAt.takeIf { it > 0 } ?: now, categories = categories, updatedAt = now, deleted = false))
        if (isNew) s.social.onLibraryAdd(call.user, row.sourceId, row.mediaId)
        s.achievements.check(call.user.id, "library")
        call.respond(mapOf("ok" to true))
    }
    get("/categories") { call.respond(s.db.categories(call.user.id)) }
    // {old, new}: only new creates, both rename, only old deletes; titles follow.
    post("/categories") {
        val body = JsonParser.parseString(call.receiveText().take(MAX_ROW_CHARS)).asJsonObject
        val old = body.get("old")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val new = body.get("new")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.take(100)?.takeIf { it.isNotEmpty() }
        require(old != null || new != null) { "Falta el nombre" }
        if (new != null && old == null) require(s.db.categories(call.user.id).size < MAX_CATEGORIES) { "Demasiadas categorías" }
        s.db.editCategory(call.user.id, old, new, body.get("sortOrder")?.asInt ?: 0)
        call.respond(s.db.categories(call.user.id))
    }
    get("/bookmarks") { call.respond(s.db.bookmarks(call.user.id, call.q("s"), call.q("id"))) }
    put("/bookmarks") {
        val row = gson.fromJson(call.receiveText().take(MAX_ROW_CHARS), BookmarkRow::class.java)
        require(!row.sourceId.isNullOrBlank() && !row.chapterId.isNullOrBlank()) { "Falta el capítulo" }
        val now = System.currentTimeMillis()
        s.db.putBookmark(call.user.id, row.copy(createdAt = row.createdAt.takeIf { it > 0 } ?: now, updatedAt = now))
        call.respond(mapOf("ok" to true))
    }
    post("/sync") {
        // The app signs in with a real account; guests (a week, one browser) have nothing to sync.
        if (call.user.isGuest) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Las cuentas de invitado no se sincronizan"))
        val text = call.receiveText()
        require(text.length < MAX_IMPORT_CHARS) { "Demasiados cambios de una vez" }
        val root = JsonParser.parseString(text).asJsonObject
        fun <T> list(key: String, type: Class<T>): List<T> = root.getAsJsonArray(key)?.map { gson.fromJson(it, type) }.orEmpty()
        val library = list("library", LibraryRow::class.java)
        val categories = list("categories", CategoryRow::class.java)
        val progress = list("progress", ProgressRow::class.java)
        val history = list("history", HistoryRow::class.java)
        val bookmarks = list("bookmarks", BookmarkRow::class.java)
        require(library.size + categories.size + progress.size + history.size + bookmarks.size <= MAX_SYNC_ROWS) { "Demasiados cambios de una vez" }
        require(categories.size <= MAX_CATEGORIES) { "Demasiadas categorías" }
        val shared = root.getAsJsonObject("settings")
        require(shared == null || shared.toString().length < MAX_SETTINGS_CHARS) { "Ajustes demasiado grandes" }
        // Chapters the phone just finished count as activity too (one at a time, never bulk marks).
        progress.filter { !it.sourceId.isNullOrBlank() && !it.chapterId.isNullOrBlank() && !it.mediaId.isNullOrBlank() }.map { it.normalized() }
            .takeIf { it.size <= Social.LIVE_ROWS }?.let { rows ->
                s.social.onProgress(call.user, rows, history.lastOrNull { h -> rows.any { it.mediaId == h.mediaId } }?.normalized())
            }
        // Rows without their keys are dropped rather than stored half empty.
        val changes = s.db.sync(
            call.user.id, root.get("since")?.asLong ?: 0L,
            library.filter { !it.sourceId.isNullOrBlank() && !it.mediaId.isNullOrBlank() },
            categories.filter { !it.name.isNullOrBlank() },
            progress.filter { !it.sourceId.isNullOrBlank() && !it.chapterId.isNullOrBlank() && !it.mediaId.isNullOrBlank() },
            history.filter { !it.sourceId.isNullOrBlank() && !it.mediaId.isNullOrBlank() && !it.chapterId.isNullOrBlank() },
            bookmarks.filter { !it.sourceId.isNullOrBlank() && !it.chapterId.isNullOrBlank() && !it.mediaId.isNullOrBlank() },
            shared
        )
        call.respond(changes)
    }
    delete("/library") {
        s.db.removeLibrary(call.user.id, call.q("s"), call.q("id"))
        call.respond(mapOf("ok" to true))
    }
    get("/progress") { call.respond(s.db.progress(call.user.id, call.q("s"), call.q("id"))) }
    post("/progress") {
        val body = JsonParser.parseString(call.receiveText()).asJsonObject
        val rows = body.getAsJsonArray("rows")?.map { gson.fromJson(it, ProgressRow::class.java) }.orEmpty()
            .filter { !it.sourceId.isNullOrBlank() && !it.chapterId.isNullOrBlank() && !it.mediaId.isNullOrBlank() }
        if (rows.size > MAX_PROGRESS_ROWS) throw IllegalArgumentException("Demasiados capítulos de una vez")
        val history = body.getAsJsonObject("history")?.let { gson.fromJson(it, HistoryRow::class.java) }
        s.social.onProgress(call.user, rows.map { it.normalized() }, history?.normalized())
        s.db.putProgress(call.user.id, rows)
        history?.let { s.db.putHistory(call.user.id, it) }
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
        val incoming = JsonParser.parseString(text).asJsonObject
        // The sources and excluded genres also live in the Android app: they count as changed only
        // when they really are different, so saving the font size does not overrule the phone.
        val stored = s.db.settings(call.user.id)?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        val sharedChanged = stored == null || Database.SHARED_SETTINGS.any { incoming.get(it) != stored.get(it) }
        val sharedAt = if (sharedChanged) System.currentTimeMillis() else s.db.sharedUpdatedAtOf(call.user.id)
        s.db.putSettings(call.user.id, text, sharedAt)
        call.respond(mapOf("ok" to true))
    }
    get("/export") {
        if (call.user.isGuest) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Los invitados no pueden exportar datos"))
        val user = call.user
        val export = JsonObject().apply {
            addProperty("format", "yomukai-export")
            addProperty("version", 2)
            addProperty("exportedAt", System.currentTimeMillis())
            addProperty("user", user.name)
            add("library", gson.toJsonTree(s.db.library(user.id)))
            add("categories", gson.toJsonTree(s.db.categories(user.id)))
            add("bookmarks", gson.toJsonTree(s.db.allBookmarks(user.id)))
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
        if (call.user.isGuest) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Los invitados no pueden importar datos"))
        val text = call.receiveText()
        require(text.length < MAX_IMPORT_CHARS) { "El archivo es demasiado grande" }
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        require(root?.get("format")?.asString == "yomukai-export") { "No es una copia de Yomukai" }
        fun <T> list(key: String, type: Class<T>): List<T> = root!!.getAsJsonArray(key)?.mapNotNull { runCatching { gson.fromJson(it, type) }.getOrNull() }.orEmpty()
        val library = list("library", LibraryRow::class.java).filter { !it.sourceId.isNullOrBlank() && !it.mediaId.isNullOrBlank() }
            .map { it.copy(deleted = false) }
        val progress = list("progress", ProgressRow::class.java).filter { !it.sourceId.isNullOrBlank() && !it.chapterId.isNullOrBlank() && !it.mediaId.isNullOrBlank() }
        val history = list("history", HistoryRow::class.java).filter { !it.sourceId.isNullOrBlank() && !it.mediaId.isNullOrBlank() && !it.chapterId.isNullOrBlank() }
            .map { it.copy(deleted = false) }
        val categories = list("categories", CategoryRow::class.java).filter { !it.name.isNullOrBlank() }.map { it.copy(deleted = false) }.take(MAX_CATEGORIES)
        val bookmarks = list("bookmarks", BookmarkRow::class.java).filter { !it.sourceId.isNullOrBlank() && !it.chapterId.isNullOrBlank() && !it.mediaId.isNullOrBlank() }
            .map { it.copy(deleted = false) }
        val settings = root!!.getAsJsonObject("settings")?.toString()
        if (call.request.queryParameters["mode"] == "replace") {
            s.db.replaceAll(call.user.id, library, progress, history, categories, bookmarks, settings)
        } else {
            // Merging: what the copy brings counts as changed now, except reading progress, where the
            // newest of the two wins.
            val now = System.currentTimeMillis()
            s.db.tx {
                with(s.db) {
                    categories.forEach { upsertCategory(call.user.id, it.copy(updatedAt = now)) }
                    library.forEach { upsertLibrary(call.user.id, it.copy(updatedAt = now)) }
                    progress.forEach { upsertProgress(call.user.id, it) }
                    history.forEach { upsertHistory(call.user.id, it.copy(updatedAt = maxOf(it.readAt, 1))) }
                    bookmarks.forEach { upsertBookmark(call.user.id, it.copy(updatedAt = now)) }
                    if (settings != null) upsertSettings(call.user.id, settings, now)
                }
            }
        }
        call.respond(mapOf("library" to library.size, "progress" to progress.size, "history" to history.size))
    }
}

/** The built web app; any path that is not a file is a page of the app and gets index.html. */
private fun Route.webApp(dir: File) {
    if (!File(dir, "index.html").exists()) log.warn("No está la web compilada en {}: solo funcionará la API", dir.absolutePath)
    // Existing files are served as they are; any other path is a page of the app and gets index.html.
    staticFiles("/", dir) {
        default("index.html")
        contentType { file ->
            when (file.extension) {
                "webmanifest" -> ContentType.parse("application/manifest+json")
                "js", "mjs" -> ContentType.parse("text/javascript")
                else -> null
            }
        }
        cacheControl { file ->
            // Built files carry a hash in their name and never change; the rest must stay fresh.
            if (file.parentFile?.name == "assets") listOf(io.ktor.http.CacheControl.MaxAge(31_536_000, visibility = io.ktor.http.CacheControl.Visibility.Public))
            else listOf(io.ktor.http.CacheControl.NoCache(null))
        }
    }
}

private const val MAX_PROGRESS_ROWS = 5_000
private const val MAX_ROW_CHARS = 64 * 1024
private const val MAX_CATEGORIES = 200
private const val MAX_DOWNLOAD_IDS = 200

/** Per sync call; the app sends bigger change sets in several calls. */
private const val MAX_SYNC_ROWS = 20_000
private const val MIN_PASSWORD = 8
private const val MAX_SETTINGS_CHARS = 256 * 1024
private const val MAX_IMPORT_CHARS = 32 * 1024 * 1024
