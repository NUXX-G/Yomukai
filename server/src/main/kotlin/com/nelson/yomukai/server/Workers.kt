package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A registered worker as it described itself on its last hello. */
data class WorkerInfo(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val revokedAt: Long?,
    val lastSeen: Long?,
    val capabilities: JsonObject,
    val version: String,
    val paused: Boolean,
    /** Granted by the admin only: the database copy holds every account, so not every worker gets it. */
    val canBackup: Boolean = false
) {
    val engines: List<String> get() = capabilities.getAsJsonArray("engines")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
    val hasGpu: Boolean get() = capabilities.get("gpu")?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank() == true
    fun online(now: Long) = revokedAt == null && lastSeen != null && now - lastSeen < WorkerRegistry.ONLINE_MS
}

/** Workers and their tokens. Only a SHA-256 of each token is stored; the token itself is shown once, when created. */
class WorkerRegistry(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    private val byToken = ConcurrentHashMap<String, Pair<WorkerInfo, Long>>()

    suspend fun create(name: String): Pair<WorkerInfo, String> {
        require(name.matches(Regex("[\\p{L}\\p{N} _.-]{2,40}"))) { "Nombre de 2 a 40 letras, números, espacio, punto o guion" }
        val token = "ykw_" + Passwords.token()
        val now = clock()
        val id = db.tx {
            require(queryOne("SELECT 1 FROM workers WHERE name = ?", name) { 1 } == null) { "Ya hay un worker llamado '$name'" }
            update("INSERT INTO workers(name, token_hash, created_at) VALUES(?,?,?)", name, hash(token), now)
            lastId()
        }
        return get(id)!! to token
    }

    suspend fun revoke(id: Long) {
        db.tx { update("UPDATE workers SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", clock(), id) }
        byToken.entries.removeIf { it.value.first.id == id }
    }

    suspend fun setCanBackup(id: Long, allowed: Boolean) {
        db.tx { update("UPDATE workers SET can_backup = ? WHERE id = ?", allowed, id) }
        byToken.entries.removeIf { it.value.first.id == id }
    }

    suspend fun setPaused(id: Long, paused: Boolean) {
        db.tx { update("UPDATE workers SET paused = ? WHERE id = ?", paused, id) }
        byToken.entries.removeIf { it.value.first.id == id }
    }

    /** The worker owning [token], if it exists and is not revoked. Cached a few seconds. */
    suspend fun authenticate(token: String?): WorkerInfo? {
        if (token == null || !token.startsWith("ykw_") || token.length > 100) return null
        val key = hash(token)
        val now = clock()
        byToken[key]?.let { (info, at) -> if (now - at < CACHE_MS) return info }
        val info = db.tx { queryOne("SELECT * FROM workers WHERE token_hash = ? AND revoked_at IS NULL", key) { worker() } }
        if (info == null) byToken.remove(key) else byToken[key] = info to now
        return info
    }

    /** A worker says it is alive and what it can do. */
    suspend fun hello(id: Long, capabilities: JsonObject, version: String) {
        db.tx { update("UPDATE workers SET last_seen = ?, capabilities = ?, version = ? WHERE id = ?", clock(), sanitize(capabilities).toString(), version.take(40), id) }
        byToken.entries.removeIf { it.value.first.id == id }
    }

    suspend fun seen(id: Long) {
        db.tx { update("UPDATE workers SET last_seen = ? WHERE id = ?", clock(), id) }
    }

    suspend fun get(id: Long): WorkerInfo? = db.tx { queryOne("SELECT * FROM workers WHERE id = ?", id) { worker() } }

    suspend fun all(): List<WorkerInfo> = db.tx { query("SELECT * FROM workers ORDER BY revoked_at IS NOT NULL, name") { worker() } }

    suspend fun online(): List<WorkerInfo> = all().filter { it.online(clock()) && !it.paused }

    private fun java.sql.ResultSet.worker() = WorkerInfo(
        id = getLong("id"), name = getString("name"), createdAt = getLong("created_at"), revokedAt = longOrNull("revoked_at"),
        lastSeen = longOrNull("last_seen"),
        capabilities = runCatching { JsonParser.parseString(getString("capabilities")).asJsonObject }.getOrElse { JsonObject() },
        version = getString("version"), paused = getInt("paused") == 1, canBackup = getInt("can_backup") == 1
    )

    companion object {
        const val ONLINE_MS = 90_000L
        private const val CACHE_MS = 15_000L
        fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Only known, bounded fields are kept from what a worker says about itself. */
        fun sanitize(input: JsonObject): JsonObject = JsonObject().apply {
            fun num(name: String, max: Long) = input.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asLong?.coerceIn(0, max)?.let { addProperty(name, it) }
            num("cpus", 1024); num("ramMb", 4_194_304); num("maxConcurrent", 64)
            input.get("gpu")?.takeIf { it.isJsonPrimitive }?.asString?.take(80)?.let { addProperty("gpu", it) }
            input.get("os")?.takeIf { it.isJsonPrimitive }?.asString?.take(80)?.let { addProperty("os", it) }
            input.get("busy")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean?.let { addProperty("busy", it) }
            add("engines", com.google.gson.JsonArray().also { arr ->
                input.getAsJsonArray("engines")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
                    ?.filter { it in JobTypes.ALL }?.distinct()?.forEach(arr::add)
            })
        }
    }
}

private val WorkerKey = AttributeKey<WorkerInfo>("worker")
val ApplicationCall.worker: WorkerInfo get() = attributes[WorkerKey]

/** Only requests with a valid, unrevoked worker token in `Authorization: Bearer …` get through. */
fun requireWorker(registry: WorkerRegistry, limiter: RateLimiter) = createRouteScopedPlugin("RequireWorker") {
    onCall { call ->
        val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        val worker = registry.authenticate(token)
        when {
            worker == null -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token de worker no válido"))
            // Worker ids are kept apart from user ids in the limiter by being negative.
            !limiter.allow(-worker.id, WORKER_RATE) -> call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Demasiadas peticiones"))
            else -> call.attributes.put(WorkerKey, worker)
        }
    }
}

private const val WORKER_RATE = 600

/** What each job type needs to be offered to users right now. */
data class Availability(val workersOnline: Int, val gpu: Boolean, val engines: Set<String>)

suspend fun WorkerRegistry.availability(): Availability {
    val online = online()
    return Availability(online.size, online.any { it.hasGpu }, online.flatMap { it.engines }.toSet())
}

/** The API the workers call. They connect out to the VPS: nothing at home is ever exposed. */
fun Route.workerRoutes(s: Services) {
    route("/worker") {
        install(requireWorker(s.workers, s.workerLimiter))
        post("/hello") {
            val body = JsonParser.parseString(call.receiveText().take(16_384)).asJsonObject
            val caps = body.getAsJsonObject("capabilities") ?: JsonObject()
            s.workers.hello(call.worker.id, caps, body.get("version")?.asString.orEmpty())
            call.respond(mapOf("ok" to true, "name" to call.worker.name, "paused" to call.worker.paused, "pollSeconds" to 5))
        }
        post("/claim") {
            if (!s.features.isOn("worker_jobs") || call.worker.paused) return@post call.respond(HttpStatusCode.NoContent)
            s.workers.seen(call.worker.id)
            val gpuOnline = s.workers.online().any { it.hasGpu }
            val job = s.jobs.claim(call.worker, gpuOnline) ?: return@post call.respond(HttpStatusCode.NoContent)
            call.respond(mapOf("id" to job.id, "type" to job.type, "payload" to job.payload, "attempt" to job.attempts, "priority" to job.priority))
        }
        post("/jobs/{id}/heartbeat") {
            val ok = s.jobs.heartbeat(call.parameters["id"]!!.toLong(), call.worker.id)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.Gone, mapOf("ok" to ok))
        }
        put("/jobs/{id}/result") {
            val id = call.parameters["id"]!!.toLong()
            val type = call.request.contentType().withoutParameters().toString()
            val job = s.jobs.complete(id, call.worker.id, call.receiveChannel().toInputStream(), type)
            call.respond(mapOf("ok" to true, "status" to job.status.db))
        }
        post("/jobs/{id}/fail") {
            val body = JsonParser.parseString(call.receiveText().take(4_096)).asJsonObject
            val job = s.jobs.fail(call.parameters["id"]!!.toLong(), call.worker.id, body.get("error")?.asString.orEmpty().ifBlank { "Error" }, body.get("retry")?.asBoolean != false)
            call.respond(mapOf("ok" to true, "status" to job.status.db))
        }
        // Latest database backup, so a home worker can keep a copy on the NAS.
        get("/backups/latest") {
            if (!call.worker.canBackup || call.worker.paused) {
                return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Este worker no tiene permiso para copias de seguridad"))
            }
            val file = Backups.list(s.config.backupDir).firstOrNull() ?: return@get call.respond(HttpStatusCode.NoContent)
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.name}\"")
            call.respondFile(file)
        }
    }
}

/** Admin: create (token shown once), list, pause and revoke workers; see the queue. */
fun Route.workerAdminRoutes(s: Services) {
    get("/workers") {
        val now = System.currentTimeMillis()
        val stats = s.jobs.stats()
        call.respond(
            mapOf(
                "workers" to s.workers.all().map {
                    mapOf(
                        "id" to it.id, "name" to it.name, "online" to it.online(now), "lastSeen" to it.lastSeen, "revoked" to (it.revokedAt != null),
                        "paused" to it.paused, "canBackup" to it.canBackup, "capabilities" to it.capabilities, "version" to it.version
                    )
                },
                "queue" to mapOf("queued" to stats.queued, "running" to stats.running, "lastDone" to stats.lastDone?.let { mapOf("type" to it.type, "at" to it.updatedAt) }),
                "results" to mapOf("bytes" to s.jobs.results.totalBytes())
            )
        )
    }
    post("/workers") {
        val body = JsonParser.parseString(call.receiveText().take(4_096)).asJsonObject
        val (info, token) = s.workers.create(body.get("name")?.asString?.trim().orEmpty())
        call.respond(mapOf("id" to info.id, "name" to info.name, "token" to token))
    }
    post("/workers/{id}/pause") {
        val body = JsonParser.parseString(call.receiveText().take(1_024)).asJsonObject
        s.workers.setPaused(call.parameters["id"]!!.toLong(), body.get("paused")?.asBoolean == true)
        call.respond(mapOf("ok" to true))
    }
    post("/workers/{id}/backup") {
        val body = JsonParser.parseString(call.receiveText().take(1_024)).asJsonObject
        s.workers.setCanBackup(call.parameters["id"]!!.toLong(), body.get("allowed")?.asBoolean == true)
        call.respond(mapOf("ok" to true))
    }
    delete("/workers/{id}") {
        s.workers.revoke(call.parameters["id"]!!.toLong())
        call.respond(mapOf("ok" to true))
    }
    get("/jobs") {
        call.respond(
            s.jobs.recent().map {
                mapOf("id" to it.id, "type" to it.type, "status" to it.status.db, "priority" to it.priority, "attempts" to it.attempts, "error" to it.error, "updatedAt" to it.updatedAt)
            }
        )
    }
    post("/jobs/echo") {
        val job = s.jobs.enqueue(JobQueue.Request(JobTypes.ECHO, JsonObject().apply { addProperty("text", "hola") }, requestedBy = call.user.id, ttlMs = 10 * 60_000L))
        call.respond(mapOf("id" to job.id))
    }
    get("/canary") {
        val health = s.health.all().associateBy { it.sourceId }
        call.respond(s.sources.all.map { src ->
            val h = health[src.id]
            mapOf(
                "id" to src.id, "name" to src.name, "status" to (h?.status?.name ?: "UNKNOWN"), "summary" to h?.summary,
                "checkedAt" to h?.checkedAt, "failingSince" to h?.failingSince, "steps" to s.health.steps(src.id)
            )
        })
    }
    post("/canary/{id}") {
        val source = s.sources.get(call.parameters["id"].orEmpty()) ?: throw NoSuchElementException("Fuente desconocida")
        val outcome = s.canary.check(source)
        refreshDegraded(s.health, s.sources)
        call.auditDetail("canario " + source.id)
        call.respond(mapOf("status" to outcome.status.name, "summary" to outcome.summary))
    }
    get("/features") {
        call.respond(FeatureCatalog.ALL.map { f -> mapOf("name" to f.name, "title" to f.title, "description" to f.description, "enabled" to s.features.isOn(f.name)) })
    }
    put("/features/{name}") {
        val body = JsonParser.parseString(call.receiveText().take(1_024)).asJsonObject
        s.features.set(call.parameters["name"]!!, body.get("enabled")?.asBoolean == true)
        call.respond(mapOf("ok" to true))
    }
}

/** Users: what is on and available now, and their own jobs. */
fun Route.featureRoutes(s: Services) {
    get("/features") {
        val flags = s.features.all()
        val availability = s.workers.availability()
        val jobsOn = flags["worker_jobs"] == true
        call.respond(
            mapOf(
                "flags" to flags,
                "worker" to mapOf("online" to (jobsOn && availability.workersOnline > 0), "gpu" to (jobsOn && availability.gpu)),
                "available" to JobTypes.ALL.filter { it != JobTypes.ECHO }.associateWith { jobsOn && it in availability.engines }
            )
        )
    }
    get("/jobs/{id}") {
        val job = s.jobs.get(call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Trabajo no válido"))
        if (job == null || (job.requestedBy != call.user.id && !call.user.isAdmin)) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No encontrado"))
        call.respond(mapOf("id" to job.id, "type" to job.type, "status" to job.status.db, "error" to job.error, "hasResult" to (job.resultKey != null)))
    }
    get("/jobs/{id}/result") {
        val job = s.jobs.get(call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Trabajo no válido"))
        if (job == null || (job.requestedBy != call.user.id && !call.user.isAdmin)) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No encontrado"))
        val key = job.resultKey ?: return@get call.respond(HttpStatusCode.Accepted, mapOf("status" to job.status.db))
        val (file, type) = s.jobs.results.get(key) ?: return@get call.respond(HttpStatusCode.Gone, mapOf("error" to "El resultado ya no está en caché"))
        call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.respond(io.ktor.server.http.content.LocalFileContent(file, ContentType.parse(type)))
    }
}
