package com.nelson.yomukai.server

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2 with a random salt per password; the format carries its own parameters. */
object Passwords {
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2$$ITERATIONS$${b64(salt)}$${b64(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, stored: String?): Boolean {
        val parts = stored?.split('$') ?: return false
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = Base64.getUrlDecoder().decode(parts[2])
        val expected = Base64.getUrlDecoder().decode(parts[3])
        return MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)).encoded

    fun token(): String = b64(ByteArray(32).also(random::nextBytes))

    /** Readable password for the install script and new accounts: 4 groups of 4. */
    fun generate(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..4).joinToString("-") { (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Per-user request budget per minute: a token bucket refilled continuously. */
class RateLimiter {
    private class Bucket(var tokens: Double, var at: Long)

    private val buckets = ConcurrentHashMap<Long, Bucket>()

    fun allow(userId: Long, perMinute: Int, now: Long = System.currentTimeMillis()): Boolean {
        val bucket = buckets.getOrPut(userId) { Bucket(perMinute.toDouble(), now) }
        synchronized(bucket) {
            bucket.tokens = minOf(perMinute.toDouble(), bucket.tokens + (now - bucket.at) * perMinute / 60_000.0)
            bucket.at = now
            if (bucket.tokens < 1.0) return false
            bucket.tokens -= 1.0
            return true
        }
    }
}

/**
 * Slows down password guessing: after [MAX_FAILURES] wrong attempts from one address, or against one
 * account, within [WINDOW_MS], that key is locked for [LOCK_MS]. A right password clears the account.
 */
class LoginThrottle {
    private class Record(var failures: Int, var inFlight: Int, var since: Long, var lockedUntil: Long)

    private val records = ConcurrentHashMap<String, Record>()

    /** Milliseconds until [keys] may try again; 0 when none is locked. */
    fun waitFor(keys: List<String>, now: Long = System.currentTimeMillis()): Long =
        keys.maxOf { key -> records[key]?.lockedUntil?.minus(now)?.coerceAtLeast(0) ?: 0 }

    /**
     * Reserves one attempt on every key before the password is checked, so many requests sent at
     * once cannot all slip under the limit. False when any key is locked or out of attempts;
     * every true must be followed by [finish].
     */
    @Synchronized
    fun begin(keys: List<String>, now: Long = System.currentTimeMillis()): Boolean {
        val all = keys.map { records.getOrPut(it) { Record(0, 0, now, 0) } }
        all.forEach { if (now - it.since > WINDOW_MS && it.inFlight == 0) { it.failures = 0; it.since = now } }
        if (all.any { it.lockedUntil > now || it.failures + it.inFlight >= MAX_FAILURES }) return false
        all.forEach { it.inFlight++ }
        if (records.size > MAX_RECORDS) records.entries.removeIf { now - it.value.since > WINDOW_MS && it.value.lockedUntil < now && it.value.inFlight == 0 }
        return true
    }

    /**
     * Ends an attempt started with [begin]. A failure counts against every key. A success clears
     * only the keys starting with "pair:": the address keeps its own count, so one right password
     * never resets the guesses made against other accounts.
     */
    @Synchronized
    fun finish(keys: List<String>, success: Boolean, now: Long = System.currentTimeMillis()) {
        keys.forEach { key ->
            val record = records[key] ?: return@forEach
            record.inFlight = (record.inFlight - 1).coerceAtLeast(0)
            if (success) {
                if (key.startsWith("pair:") && record.inFlight == 0) records.remove(key)
            } else {
                record.failures++
                if (record.failures >= MAX_FAILURES) record.lockedUntil = now + LOCK_MS
            }
        }
    }

    private val accountFailures = ConcurrentHashMap<String, Record>()

    /**
     * Account-wide brake that never locks the owner out: after [SLOW_AFTER] failures from anywhere
     * within [WINDOW_MS], every attempt on that account waits a little longer, up to [MAX_DELAY_MS].
     */
    fun accountDelay(account: String, now: Long = System.currentTimeMillis()): Long {
        val record = accountFailures[account] ?: return 0
        if (now - record.since > WINDOW_MS) { accountFailures.remove(account); return 0 }
        return ((record.failures - SLOW_AFTER).coerceAtLeast(0) * STEP_MS).coerceAtMost(MAX_DELAY_MS)
    }

    fun accountFailed(account: String, now: Long = System.currentTimeMillis()) {
        val record = accountFailures.getOrPut(account) { Record(0, 0, now, 0) }
        synchronized(record) {
            if (now - record.since > WINDOW_MS) { record.failures = 0; record.since = now }
            record.failures++
        }
        if (accountFailures.size > MAX_RECORDS) accountFailures.entries.removeIf { now - it.value.since > WINDOW_MS }
    }

    /** One wrong attempt, outside [begin]/[finish] (tests). */
    fun failed(keys: List<String>, now: Long = System.currentTimeMillis()) {
        if (begin(keys, now)) finish(keys, success = false, now = now)
    }

    companion object {
        const val MAX_FAILURES = 5
        const val WINDOW_MS = 15 * 60_000L
        const val LOCK_MS = 15 * 60_000L
        const val SLOW_AFTER = 10
        private const val STEP_MS = 250L
        const val MAX_DELAY_MS = 5_000L
        private const val MAX_RECORDS = 100_000

        /** The key for an address: IPv6 by its /64, which is what one connection usually owns. */
        fun addressKey(address: String): String {
            if (':' !in address) return address
            val parts = address.substringBefore('%').split(':')
            return if (parts.size >= 4 && "" !in parts.take(4)) parts.take(4).joinToString(":") + "::/64" else address
        }
    }
}

class Auth(private val db: Database, private val config: Config) {
    val throttle = LoginThrottle()

    private val log = LoggerFactory.getLogger("YomukaiAuth")
    private val limiter = RateLimiter()

    /** Sessions already looked up, so most requests skip the database. */
    private val sessionCache = ConcurrentHashMap<String, Pair<User, Long>>()

    /** Creates the admin on the first start, or resets its password when ADMIN_PASSWORD changes. */
    suspend fun bootstrap() {
        val existing = db.userByName(config.adminUser)
        val password = config.adminPassword
        when {
            existing == null -> {
                val chosen = password ?: Passwords.generate().also {
                    log.warn("No hay ADMIN_PASSWORD: contraseña generada para '{}': {}", config.adminUser, it)
                }
                db.createUser(config.adminUser, Passwords.hash(chosen), admin = true, guest = false, expiresAt = null)
                log.info("Administrador '{}' creado", config.adminUser)
            }
            password != null && !Passwords.verify(password, existing.second) -> {
                db.setPassword(existing.first.id, Passwords.hash(password))
                log.info("Contraseña del administrador actualizada desde ADMIN_PASSWORD")
            }
        }
    }

    suspend fun login(name: String, password: String): Pair<User, String>? {
        val (user, hash) = db.userByName(name.trim()) ?: return null
        if (user.isGuest || !Passwords.verify(password, hash)) return null
        return user to newSession(user)
    }

    /** A guest: no password, everything allowed, gone after [Config.guestDays]. */
    suspend fun guest(): Pair<User, String> {
        val expires = System.currentTimeMillis() + config.guestDays * DAY_MS
        val name = "invitado-" + Passwords.token().take(8).lowercase()
        val user = db.createUser(name, null, admin = false, guest = true, expiresAt = expires)
        return user to newSession(user)
    }

    private suspend fun newSession(user: User): String {
        val token = Passwords.token()
        db.createSession(token, user.id)
        return token
    }

    suspend fun userFor(token: String?): User? {
        if (token.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        sessionCache[token]?.takeIf { now - it.second < SESSION_CACHE_MS }?.let { (user, _) ->
            return user.takeUnless { it.isGuest && (it.expiresAt ?: Long.MAX_VALUE) < now }
        }
        val user = db.userBySession(token) ?: return null.also { sessionCache.remove(token) }
        if (user.isGuest && (user.expiresAt ?: Long.MAX_VALUE) < now) return null
        sessionCache[token] = user to now
        return user
    }

    suspend fun logout(token: String) {
        sessionCache.remove(token)
        db.deleteSession(token)
    }

    /** Forgets cached sessions of a user, after deleting it or changing its password. */
    fun forget(userId: Long) = sessionCache.entries.removeIf { it.value.first.id == userId }

    fun allow(user: User): Boolean =
        limiter.allow(user.id, if (user.isGuest) config.guestRatePerMinute else Config.ACCOUNT_RATE)

    suspend fun cleanUpGuests() {
        val removed = db.deleteExpiredGuests()
        if (removed > 0) {
            sessionCache.entries.removeIf { (_, value) -> value.first.isGuest && (value.first.expiresAt ?: 0) < System.currentTimeMillis() }
            log.info("Borrados {} invitados caducados y sus datos", removed)
        }
    }

    fun sessionCookie(token: String, secure: Boolean) = Cookie(
        name = COOKIE, value = token, maxAge = TEN_YEARS_S, path = "/", httpOnly = true, secure = secure,
        extensions = mapOf("SameSite" to "Lax")
    )

    companion object {
        const val COOKIE = "yk_session"
        const val DAY_MS = 86_400_000L
        private const val SESSION_CACHE_MS = 60_000L
        private const val TEN_YEARS_S = 10 * 365 * 24 * 3600
    }
}

val UserKey = AttributeKey<User>("yomukai.user")

val ApplicationCall.user: User get() = attributes[UserKey]

/** Lets through only requests with a valid session, within their rate; [admin] restricts to admins. */
fun requireUser(auth: Auth, admin: Boolean = false) = createRouteScopedPlugin("RequireUser${if (admin) "Admin" else ""}") {
    onCall { call ->
        val user = auth.userFor(call.request.cookies[Auth.COOKIE])
        when {
            user == null -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Inicia sesión o entra como invitado"))
            admin && !user.isAdmin -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Solo para administradores"))
            !auth.allow(user) -> call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Demasiadas peticiones, espera un momento"))
            else -> call.attributes.put(UserKey, user)
        }
    }
}
