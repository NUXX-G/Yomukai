package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

/** Reads a JSON object body of at most [max] characters; anything else is a 400. */
suspend fun ApplicationCall.jsonBody(max: Int = 16_384): JsonObject {
    val text = receiveText()
    require(text.length <= max) { "Petición demasiado grande" }
    return runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse { throw IllegalArgumentException("JSON no válido") }
}

fun JsonObject.text(name: String, max: Int = 500): String =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString?.take(max).orEmpty()

val USERNAME = Regex("[\\p{L}\\p{N}_.-]{2,32}")
const val MIN_PASSWORD_LENGTH = 8

fun checkNewPassword(password: String) {
    require(password.length in MIN_PASSWORD_LENGTH..256) { "La contraseña tiene que tener entre $MIN_PASSWORD_LENGTH y 256 caracteres" }
}

/** A short description of the browser or app behind a session, from its User-Agent. */
fun deviceOf(userAgent: String?): String {
    val ua = userAgent.orEmpty()
    val app = when {
        ua.startsWith("okhttp") || ua.contains("Yomukai-Android") -> "App de Android"
        ua.contains("Edg/") -> "Edge"
        ua.contains("Firefox/") -> "Firefox"
        ua.contains("Chrome/") -> "Chrome"
        ua.contains("Safari/") -> "Safari"
        ua.isBlank() -> "Desconocido"
        else -> ua.substringBefore('/').take(30)
    }
    val os = when {
        ua.contains("Android") -> "Android"
        ua.contains("iPhone") || ua.contains("iPad") -> "iOS"
        ua.contains("Windows") -> "Windows"
        ua.contains("Mac OS") -> "macOS"
        ua.contains("Linux") -> "Linux"
        else -> ""
    }
    return if (os.isEmpty() || app == "App de Android") app else "$app en $os"
}

// ---------- Invitations ----------

data class Invite(val id: Long, val note: String, val createdAt: Long, val expiresAt: Long, val usedAt: Long?, val usedBy: String?, val revoked: Boolean)

/** One-use, expiring sign-up links made by an admin. Only a hash of each link's token is stored. */
class InviteStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun create(createdBy: Long, days: Int, note: String): Pair<Invite, String> {
        val token = Passwords.token()
        val now = clock()
        val id = db.tx {
            update(
                "INSERT INTO invites(token_hash, created_by, note, created_at, expires_at) VALUES(?,?,?,?,?)",
                sha256Hex(token), createdBy, note.take(100), now, now + days.coerceIn(1, 30) * Auth.DAY_MS
            )
            lastId()
        }
        return all().first { it.id == id } to token
    }

    /** The invite behind [token] if it can still be used. */
    suspend fun valid(token: String): Invite? {
        if (token.length !in 20..100) return null
        val now = clock()
        return db.tx {
            queryOne("SELECT i.*, NULL AS used_name FROM invites i WHERE token_hash = ? AND used_at IS NULL AND revoked = 0 AND expires_at > ?", sha256Hex(token), now) { invite() }
        }
    }

    /** Creates the account and spends the invite in one transaction: two people racing for the same link cannot both win. */
    suspend fun redeem(token: String, name: String, passHash: String): User {
        val now = clock()
        return db.tx {
            val id = queryOne(
                "SELECT id FROM invites WHERE token_hash = ? AND used_at IS NULL AND revoked = 0 AND expires_at > ?", sha256Hex(token), now
            ) { getLong(1) } ?: throw IllegalArgumentException("La invitación no existe, ya se usó o ha caducado")
            require(queryOne("SELECT 1 FROM users WHERE name = ? COLLATE NOCASE", name) { 1 } == null) { "Ese nombre ya está cogido" }
            update("INSERT INTO users(name, pass_hash, is_admin, is_guest, created_at) VALUES(?,?,0,0,?)", name, passHash, now)
            val userId = lastId()
            require(update("UPDATE invites SET used_at = ?, used_by = ? WHERE id = ? AND used_at IS NULL", now, userId, id) == 1) { "La invitación ya se usó" }
            User(userId, name, isAdmin = false, isGuest = false, createdAt = now, expiresAt = null)
        }
    }

    suspend fun revoke(id: Long) = db.tx { update("UPDATE invites SET revoked = 1 WHERE id = ?", id) }

    suspend fun all(): List<Invite> = db.tx {
        query("SELECT i.*, u.name AS used_name FROM invites i LEFT JOIN users u ON u.id = i.used_by ORDER BY i.created_at DESC LIMIT 100") { invite() }
    }

    private fun java.sql.ResultSet.invite() = Invite(
        getLong("id"), getString("note"), getLong("created_at"), getLong("expires_at"), longOrNull("used_at"), getString("used_name"), getInt("revoked") == 1
    )
}

// ---------- Audit log ----------

data class AuditEntry(val id: Long, val at: Long, val actor: String, val action: String, val target: String, val detail: String, val ip: String)

/** What admins (and later moderators) did: who, what, on what, from where. Kept a year. */
class AuditLog(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun record(actor: User?, action: String, target: String = "", detail: String = "", ip: String = "") {
        val now = clock()
        db.tx {
            update(
                "INSERT INTO audit_log(at, actor_id, actor_name, action, target, detail, ip) VALUES(?,?,?,?,?,?,?)",
                now, actor?.id, actor?.name ?: "sistema", action.take(100), target.take(200), detail.take(500), ip.take(64)
            )
            update("DELETE FROM audit_log WHERE at < ?", now - 365 * Auth.DAY_MS)
        }
    }

    suspend fun recent(limit: Int = 200): List<AuditEntry> = db.tx {
        query("SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", limit.coerceIn(1, 1000)) {
            AuditEntry(getLong("id"), getLong("at"), getString("actor_name"), getString("action"), getString("target"), getString("detail"), getString("ip"))
        }
    }
}

/** Handlers can add a human detail ("usuario ana") to the automatic audit entry of their request. */
val AuditDetailKey = AttributeKey<String>("auditDetail")
fun ApplicationCall.auditDetail(text: String) = attributes.put(AuditDetailKey, text)

/**
 * Every admin request that changes something (anything but GET) is written to the audit log once answered, with its
 * result. Nothing has to remember to log: new admin and moderation routes are covered by being under /api/admin.
 */
fun auditAdminActions(audit: AuditLog, addressOf: (ApplicationCall) -> String) = createRouteScopedPlugin("AuditAdmin") {
    on(ResponseSent) { call ->
        if (call.request.httpMethod.value == "GET") return@on
        val user = runCatching { call.user }.getOrNull() ?: return@on
        val status = call.response.status()?.value ?: 0
        val action = "${call.request.httpMethod.value} ${call.request.path().removePrefix("/api/admin")}"
        val detail = (call.attributes.getOrNull(AuditDetailKey) ?: "") + if (status >= 400) " (rechazado: $status)" else ""
        val ip = addressOf(call)
        call.application.launch { runCatching { audit.record(user, action, detail = detail.trim(), ip = ip) } }
    }
}

// ---------- First-run setup ----------

/**
 * A fresh server has no admin and no default password. Until someone creates the admin, a one-time setup token is
 * kept in data/setup-token (readable only by the server's user) and printed in the log; the setup page asks for it,
 * so a stranger who finds the new server first cannot claim it.
 */
class SetupWizard(private val db: Database, private val dataDir: File) {
    private val log = LoggerFactory.getLogger("Setup")
    private val file get() = File(dataDir, "setup-token")

    @Volatile private var done: Boolean? = null

    suspend fun needed(): Boolean {
        done?.let { return !it }
        val needed = db.userCount(admins = true) == 0
        if (!needed) done = true
        return needed
    }

    /** Makes (or reads) the setup token and prints where to use it. */
    suspend fun prepare(publicUrl: String?) {
        if (!needed()) {
            file.delete()
            return
        }
        val token = file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length >= 20 } ?: Passwords.token().also { t ->
            file.writeText(t)
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            }
        }
        val base = publicUrl ?: "http://TU-SERVIDOR"
        log.warn("Primer arranque: crea la cuenta de administrador en $base/configurar?token=$token")
    }

    suspend fun complete(token: String, name: String, password: String): User {
        require(needed()) { "Este servidor ya está configurado" }
        val expected = file.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        require(expected.isNotEmpty() && sameSecret(expected, token.trim())) { "Código de configuración incorrecto" }
        require(name.matches(USERNAME)) { "Nombre de 2 a 32 letras, números, punto, guion o guion bajo" }
        checkNewPassword(password)
        val user = db.createUser(name, Passwords.hash(password), admin = true, guest = false, expiresAt = null)
        done = true
        file.delete()
        log.info("Administrador '{}' creado desde el asistente", name)
        return user
    }
}

// ---------- Routes ----------

/** Public (no session): second login step, invitation sign-up and first-run setup. */
fun Route.accountPublicRoutes(s: Services, addressOf: (ApplicationCall) -> String, secure: (ApplicationCall) -> Boolean) {
    post("/auth/2fa") {
        val body = call.jsonBody(2_048)
        val (user, token) = s.auth.completeTwoFactor(body.text("challenge", 100), body.text("code", 40))
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Código incorrecto o caducado"))
        call.response.cookies.append(s.auth.sessionCookie(token, secure(call)))
        call.respond(mapOf("user" to mapOf("name" to user.name, "admin" to user.isAdmin, "guest" to false)))
    }
    get("/auth/invite/{token}") {
        val invite = s.invites.valid(call.parameters["token"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "La invitación no existe, ya se usó o ha caducado"))
        call.respond(mapOf("valid" to true, "expiresAt" to invite.expiresAt))
    }
    post("/auth/invite/{token}") {
        val body = call.jsonBody(2_048)
        val name = body.text("name", 64).trim()
        val password = body.text("password", 300)
        require(name.matches(USERNAME)) { "Nombre de 2 a 32 letras, números, punto, guion o guion bajo" }
        checkNewPassword(password)
        val user = s.invites.redeem(call.parameters["token"].orEmpty(), name, Passwords.hash(password))
        s.audit.record(user, "invitación usada", target = name, ip = addressOf(call))
        val token = s.auth.newSession(user, deviceOf(call.request.headers["User-Agent"]), addressOf(call))
        call.response.cookies.append(s.auth.sessionCookie(token, secure(call)))
        call.respond(mapOf("user" to mapOf("name" to user.name, "admin" to false, "guest" to false)))
    }
    get("/auth/setup") { call.respond(mapOf("needed" to s.setup.needed())) }
    post("/auth/setup") {
        val body = call.jsonBody(2_048)
        val user = s.setup.complete(body.text("token", 100), body.text("name", 64).trim(), body.text("password", 300))
        s.audit.record(user, "servidor configurado", target = user.name, ip = addressOf(call))
        val token = s.auth.newSession(user, deviceOf(call.request.headers["User-Agent"]), addressOf(call))
        call.response.cookies.append(s.auth.sessionCookie(token, secure(call)))
        call.respond(mapOf("user" to mapOf("name" to user.name, "admin" to true, "guest" to false)))
    }
}

/** Signed-in users: their sessions and their two-step verification. */
fun Route.accountUserRoutes(s: Services) {
    get("/sessions") {
        val current = call.request.cookies[Auth.COOKIE]?.let(::sha256Hex)
        call.respond(s.db.sessionsOf(call.user.id).map {
            mapOf("id" to it.id, "device" to it.device.ifBlank { "Desconocido" }, "ip" to it.ip, "createdAt" to it.createdAt, "lastSeen" to it.lastSeen, "current" to (it.tokenHash == current))
        })
    }
    delete("/sessions/{id}") {
        val hash = s.db.deleteSessionById(call.user.id, call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Sesión no válida"))
            ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "No encontrada"))
        s.auth.forgetToken(hash)
        call.respond(mapOf("ok" to true))
    }
    post("/sessions/close-others") {
        val token = call.request.cookies[Auth.COOKIE].orEmpty()
        val closed = s.db.deleteOtherSessions(call.user.id, token)
        s.auth.forget(call.user.id)
        call.respond(mapOf("closed" to closed))
    }
    get("/2fa") {
        val (on, left) = s.auth.totpStatus(call.user.id)
        call.respond(mapOf("enabled" to on, "recoveryLeft" to left))
    }
    post("/2fa/setup") {
        require(!call.user.isGuest) { "Los invitados no pueden activar la verificación en dos pasos" }
        val (secret, uri) = s.auth.startTotp(call.user)
        call.respond(mapOf("secret" to secret, "uri" to uri))
    }
    post("/2fa/enable") {
        val codes = s.auth.enableTotp(call.user, call.jsonBody(1_024).text("code", 20))
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Código incorrecto. Revisa la hora del móvil y prueba otra vez"))
        call.respond(mapOf("recoveryCodes" to codes))
    }
    post("/2fa/disable") {
        val body = call.jsonBody(1_024)
        require(s.auth.passwordMatches(call.user.id, body.text("password", 300))) { "Contraseña incorrecta" }
        require(s.auth.checkCode(call.user.id, body.text("code", 40))) { "Código incorrecto" }
        s.auth.disableTotp(call.user.id)
        call.respond(mapOf("ok" to true))
    }
}

/** Admins: invitations and the audit log. */
fun Route.accountAdminRoutes(s: Services) {
    get("/invites") {
        call.respond(s.invites.all().map {
            mapOf("id" to it.id, "note" to it.note, "createdAt" to it.createdAt, "expiresAt" to it.expiresAt, "usedAt" to it.usedAt, "usedBy" to it.usedBy, "revoked" to it.revoked)
        })
    }
    post("/invites") {
        val body = call.jsonBody(1_024)
        val days = body.get("days")?.takeIf { it.isJsonPrimitive }?.asInt ?: 7
        val (invite, token) = s.invites.create(call.user.id, days, body.text("note", 100))
        call.auditDetail("invitación ${invite.id}" + if (invite.note.isNotBlank()) " (${invite.note})" else "")
        call.respond(mapOf("id" to invite.id, "token" to token, "path" to "/invitacion/$token", "expiresAt" to invite.expiresAt))
    }
    delete("/invites/{id}") {
        s.invites.revoke(call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Invitación no válida"))
        call.respond(mapOf("ok" to true))
    }
    get("/audit") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
        call.respond(s.audit.recent(limit))
    }
}
