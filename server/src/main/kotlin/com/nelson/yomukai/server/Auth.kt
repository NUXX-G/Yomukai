package com.nelson.yomukai.server

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.path
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
    private class Record(var failures: Int, var inFlight: Int, var since: Long, var lockedUntil: Long, var locks: Int = 0, var lastLock: Long = 0)

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
        // Records of locked keys are kept a day, so the next lock of the same key can be longer.
        all.forEach { it.inFlight++ }
        if (records.size > MAX_RECORDS) {
            records.entries.removeIf { now - it.value.since > WINDOW_MS && it.value.lockedUntil < now && it.value.inFlight == 0 && now - it.value.lastLock > MAX_LOCK_MS }
        }
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
                if (record.failures >= MAX_FAILURES) {
                    // A key that keeps getting locked is locked for longer each time: 15 min, 30, 1 h... up to a day.
                    if (now - record.lastLock > MAX_LOCK_MS) record.locks = 0
                    record.lockedUntil = now + lockFor(record.locks)
                    record.locks++
                    record.lastLock = now
                    record.failures = 0
                    record.since = now
                }
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
        const val MAX_LOCK_MS = 24 * 60 * 60_000L
        fun lockFor(previousLocks: Int): Long = (LOCK_MS shl previousLocks.coerceAtMost(7)).coerceAtMost(MAX_LOCK_MS)
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val IPV6_CHARS = Regex("""^[0-9A-Fa-f:.]{2,45}$""")
        const val SLOW_AFTER = 10
        private const val STEP_MS = 250L
        const val MAX_DELAY_MS = 5_000L
        private const val MAX_RECORDS = 100_000

        /** The key for an address: IPv6 by its /64, which is what one connection usually owns. */
        fun addressKey(address: String): String {
            // Parsed, never split as text: "2001:db8::1" and its long form are the same address.
            // Only IP literals are parsed; a name is never looked up (it may come from a header).
            val text = address.substringBefore('%').trim()
            if (!IPV4.matches(text) && !(':' in text && IPV6_CHARS.matches(text))) return "other:" + text.take(64)
            val parsed = runCatching { java.net.InetAddress.getByName(text) }.getOrNull() ?: return address
            return when (parsed) {
                // IPv4 and IPv4-mapped IPv6 (Java gives an Inet4Address for both).
                is java.net.Inet4Address -> parsed.hostAddress
                is java.net.Inet6Address -> parsed.address.take(8).joinToString("") { "%02x".format(it) } + "/64"
                else -> address
            }
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
            existing == null && password == null -> Unit // the first-run setup page creates the admin (SetupWizard)
            existing == null -> {
                db.createUser(config.adminUser, Passwords.hash(password!!), admin = true, guest = false, expiresAt = null)
                log.info("Administrador '{}' creado", config.adminUser)
            }
            password != null && !Passwords.verify(password, existing.second) -> {
                db.setPassword(existing.first.id, Passwords.hash(password))
                log.info("Contraseña del administrador actualizada desde ADMIN_PASSWORD")
            }
        }
    }

    /** What a password login led to: a session, a second step (2FA), or nothing. */
    sealed class LoginResult {
        data class Session(val user: User, val token: String) : LoginResult()
        data class NeedsCode(val challenge: String) : LoginResult()
        data object Failed : LoginResult()
    }

    private class Challenge(val userId: Long, val expires: Long, var attempts: Int, val device: String, val ip: String)
    private val challenges = ConcurrentHashMap<String, Challenge>()
    val totpBox = SecretBox(config.secret, "totp")

    suspend fun login(name: String, password: String, device: String = "", ip: String = ""): LoginResult {
        val (user, hash) = db.userByName(name.trim()) ?: return LoginResult.Failed
        if (user.isGuest || !Passwords.verify(password, hash)) return LoginResult.Failed
        if (db.tx { queryOne("SELECT totp_enabled FROM users WHERE id = ?", user.id) { getInt(1) == 1 } } == true) {
            val now = System.currentTimeMillis()
            challenges.entries.removeIf { it.value.expires < now }
            val challenge = Passwords.token()
            challenges[challenge] = Challenge(user.id, now + CHALLENGE_MS, 0, device, ip)
            return LoginResult.NeedsCode(challenge)
        }
        return LoginResult.Session(user, newSession(user, device, ip))
    }

    /** Second step: the authenticator's code or a recovery code. Five tries per challenge. */
    suspend fun completeTwoFactor(challenge: String, code: String): Pair<User, String>? {
        val now = System.currentTimeMillis()
        val c = challenges[challenge]?.takeIf { it.expires > now } ?: return null
        val attempts = synchronized(c) { ++c.attempts }
        if (attempts > MAX_CODE_ATTEMPTS) {
            challenges.remove(challenge)
            return null
        }
        if (!checkCode(c.userId, code, now)) return null
        if (challenges.remove(challenge) == null) return null
        val user = db.userById(c.userId) ?: return null
        return user to newSession(user, c.device, c.ip)
    }

    /** A TOTP code (each one usable once) or an unused recovery code. */
    suspend fun checkCode(userId: Long, code: String, now: Long = System.currentTimeMillis()): Boolean {
        val row = db.tx { queryOne("SELECT totp_secret, totp_last_step FROM users WHERE id = ? AND totp_enabled = 1", userId) { getString(1) to getLong(2) } }
            ?: return false
        val secret = row.first?.let(totpBox::open) ?: return false
        Totp.verify(secret, code, now, row.second)?.let { step ->
            val fresh = db.tx { update("UPDATE users SET totp_last_step = ? WHERE id = ? AND totp_last_step < ?", step, userId, step) } == 1
            return fresh
        }
        val hash = RecoveryCodes.hash(code)
        return db.tx { update("UPDATE recovery_codes SET used_at = ? WHERE user_id = ? AND code_hash = ? AND used_at IS NULL", now, userId, hash) } == 1
    }

    /** Starts turning 2FA on: a new secret, kept (encrypted) but not active until [enableTotp] confirms a code. */
    suspend fun startTotp(user: User): Pair<String, String> {
        require(!user.isGuest) { "Los invitados no pueden activar la verificación en dos pasos" }
        val secret = Totp.newSecret()
        val changed = db.tx { update("UPDATE users SET totp_secret = ? WHERE id = ? AND totp_enabled = 0", totpBox.seal(secret), user.id) }
        require(changed == 1) { "La verificación en dos pasos ya está activada" }
        return secret to Totp.uri(secret, user.name)
    }

    /** Confirms the first code and switches 2FA on; returns the recovery codes, shown once. */
    suspend fun enableTotp(user: User, code: String): List<String>? {
        val sealed = db.tx { queryOne("SELECT totp_secret FROM users WHERE id = ? AND totp_enabled = 0", user.id) { getString(1) } } ?: return null
        val secret = totpBox.open(sealed) ?: return null
        val step = Totp.verify(secret, code, System.currentTimeMillis()) ?: return null
        val codes = RecoveryCodes.generate()
        db.tx {
            update("UPDATE users SET totp_enabled = 1, totp_last_step = ? WHERE id = ?", step, user.id)
            update("DELETE FROM recovery_codes WHERE user_id = ?", user.id)
            codes.forEach { update("INSERT INTO recovery_codes(user_id, code_hash) VALUES(?,?)", user.id, RecoveryCodes.hash(it)) }
        }
        return codes
    }

    suspend fun disableTotp(userId: Long) = db.tx {
        update("UPDATE users SET totp_enabled = 0, totp_secret = NULL, totp_last_step = -1 WHERE id = ?", userId)
        update("DELETE FROM recovery_codes WHERE user_id = ?", userId)
    }

    /** Whether 2FA is on, and how many recovery codes are left. */
    suspend fun totpStatus(userId: Long): Pair<Boolean, Int> = db.tx {
        val on = queryOne("SELECT totp_enabled FROM users WHERE id = ?", userId) { getInt(1) == 1 } ?: false
        val left = queryOne("SELECT COUNT(*) FROM recovery_codes WHERE user_id = ? AND used_at IS NULL", userId) { getInt(1) } ?: 0
        on to left
    }

    suspend fun passwordMatches(userId: Long, password: String): Boolean {
        val hash = db.tx { queryOne("SELECT pass_hash FROM users WHERE id = ?", userId) { getString(1) } }
        return Passwords.verify(password, hash)
    }

    /** A guest: no password, everything allowed, gone after [Config.guestDays]. */
    suspend fun guest(): Pair<User, String> {
        val expires = System.currentTimeMillis() + config.guestDays * DAY_MS
        val name = "invitado-" + Passwords.token().take(8).lowercase()
        val user = db.createUser(name, null, admin = false, guest = true, expiresAt = expires)
        return user to newSession(user)
    }

    suspend fun newSession(user: User, device: String = "", ip: String = ""): String {
        val token = Passwords.token()
        db.createSession(token, user.id, device, ip)
        return token
    }

    suspend fun userFor(token: String?, ip: String = ""): User? {
        if (token.isNullOrBlank() || token.length > 100) return null
        val now = System.currentTimeMillis()
        sessionCache[token]?.takeIf { now - it.second < SESSION_CACHE_MS }?.let { (user, _) ->
            return user.takeUnless { it.isGuest && (it.expiresAt ?: Long.MAX_VALUE) < now }
        }
        val user = db.userBySession(token) ?: return null.also { sessionCache.remove(token) }
        if (user.isGuest && (user.expiresAt ?: Long.MAX_VALUE) < now) return null
        sessionCache[token] = user to now
        // "Last activity" of the sessions list: written when the cache entry is renewed, about once a minute.
        db.touchSession(token, now, ip)
        return user
    }

    /** Drops cached sessions by token hash (after one is closed from the sessions list). */
    fun forgetToken(hash: String) = sessionCache.keys.removeIf { sha256Hex(it) == hash }

    suspend fun logout(token: String) {
        sessionCache.remove(token)
        db.deleteSession(token)
    }

    /** Forgets cached sessions of a user, after deleting it or changing its password. */
    fun forget(userId: Long) = sessionCache.entries.removeIf { it.value.first.id == userId }

    fun allow(user: User): Boolean =
        limiter.allow(user.id, if (user.isGuest) config.guestRatePerMinute else Config.ACCOUNT_RATE)

    private val usage = ConcurrentHashMap<String, LongArray>()

    /** Connections whose guest was suspended: no new guests from there for a day. */
    private val blockedAddresses = ConcurrentHashMap<String, Long>()
    private val guestsCreated = ConcurrentHashMap<String, MutableList<Long>>()

    /** Null when [address] may create a guest now; otherwise why not. Counts the guest when allowed. */
    fun guestAllowedFrom(address: String, now: Long = System.currentTimeMillis()): String? {
        blockedAddresses[address]?.let { until -> if (until > now) return "Desde esta conexión no se pueden crear invitados durante un tiempo" else blockedAddresses.remove(address) }
        val recent = guestsCreated.getOrPut(address) { mutableListOf() }
        synchronized(recent) {
            recent.removeAll { now - it > GUEST_CREATION_WINDOW_MS }
            if (recent.size >= GUESTS_PER_WINDOW) return "Demasiados invitados desde esta conexión; espera un rato o pide una cuenta"
            recent += now
        }
        if (guestsCreated.size > 10_000) guestsCreated.entries.removeIf { (_, v) -> synchronized(v) { v.all { now - it > GUEST_CREATION_WINDOW_MS } } }
        return null
    }

    /**
     * Counts a request that makes the server ask the sources for something. An account that asks for far
     * more than any person reading could (a script, a scraper) is suspended until an admin lifts it,
     * so one user cannot get the server blocked by the sites or slow it down for everyone else.
     * Returns false when this request suspended the account.
     */
    suspend fun countSourceRequest(user: User, address: String = "", now: Long = System.currentTimeMillis()): Boolean {
        if (user.isAdmin) return true
        val limit = if (user.isGuest) config.guestAbuseLimit else config.abuseLimit
        fun over(key: String): Boolean {
            val window = usage.getOrPut(key) { longArrayOf(now, 0) }
            return synchronized(window) {
                if (now - window[0] > ABUSE_WINDOW_MS) { window[0] = now; window[1] = 0 }
                window[1]++
                window[1] > limit
            }
        }
        val byUser = over("u:${user.id}")
        // Guests are also counted per connection, so making new guests does not restart the count. Several
        // people can share a connection (an office, a phone network): crossing that shared count only refuses
        // the request and stops new guests from there for a while; it never suspends anyone by itself.
        if (!byUser && user.isGuest && address.isNotEmpty() && over("ip:$address")) {
            blockedAddresses[address] = now + ADDRESS_BLOCK_MS
            throw TooManyFromAddress()
        }
        if (!byUser) return true
        db.suspend(user.id, "Más de $limit peticiones a las fuentes en 10 minutos")
        if (user.isGuest && address.isNotEmpty()) blockedAddresses[address] = now + ADDRESS_BLOCK_MS
        usage.remove("u:${user.id}")
        forget(user.id)
        log.warn("Cuenta {} suspendida por uso excesivo", user.name)
        return false
    }

    suspend fun reactivate(userId: Long) {
        db.reactivate(userId)
        usage.remove("u:$userId")
        forget(userId)
    }

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
        private const val CHALLENGE_MS = 5 * 60_000L
        private const val MAX_CODE_ATTEMPTS = 5
        private const val ABUSE_WINDOW_MS = 10 * 60_000L
        private const val ADDRESS_BLOCK_MS = 60 * 60_000L
        private const val GUEST_CREATION_WINDOW_MS = 60 * 60_000L
        private const val GUESTS_PER_WINDOW = 5
        private const val TEN_YEARS_S = 10 * 365 * 24 * 3600
    }
}

val UserKey = AttributeKey<User>("yomukai.user")

val ApplicationCall.user: User get() = attributes[UserKey]

/** Lets through only requests with a valid session, within their rate; [admin] restricts to admins. */
fun requireUser(auth: Auth, admin: Boolean = false, addressOf: (io.ktor.server.application.ApplicationCall) -> String = { "" }) =
    createRouteScopedPlugin("RequireUser${if (admin) "Admin" else ""}") {
    onCall { call ->
        val user = auth.userFor(call.request.cookies[Auth.COOKIE], addressOf(call))
        when {
            user == null -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Inicia sesión o entra como invitado"))
            user.suspendedAt != null -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to SUSPENDED_MESSAGE, "suspended" to true))
            admin && !user.isAdmin -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Solo para administradores"))
            !auth.allow(user) -> call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Demasiadas peticiones, espera un momento"))
            isSourceRequest(call.request.path()) && !countOrRefuse(call, auth, user, addressOf) -> Unit
            else -> call.attributes.put(UserKey, user)
        }
    }
}

const val SUSPENDED_MESSAGE = "Tu cuenta está suspendida por un uso excesivo. Pide al administrador que la reactive."

/** Requests that make the server ask the sources (or AniList) for something: what abuse would hammer. */
private val SOURCE_PATHS = listOf(
    "/api/sources/", "/api/search", "/api/media", "/api/chapters", "/api/pages", "/api/text", "/api/videos",
    "/api/metadata", "/api/similar", "/api/alternatives", "/api/locate", "/api/random", "/api/foryou",
    "/api/updates", "/api/download"
)

/**
 * Decoded and with repeated slashes collapsed, as the router sees it: "/api/%70ages" or "//api/pages"
 * reach the same handler as "/api/pages" and must be counted the same.
 */
fun isSourceRequest(path: String): Boolean {
    val clean = runCatching { path.decodeURLPart() }.getOrDefault(path).replace(Regex("/{2,}"), "/")
    return SOURCE_PATHS.any { clean.startsWith(it) }
}

/** Too many requests from one connection's guests together: this request is refused, nobody is suspended. */
class TooManyFromAddress : RuntimeException("Demasiadas peticiones desde esta conexión; espera un rato")

/** False when the request was answered here (account suspended now, or the connection's guests over their count). */
private suspend fun countOrRefuse(
    call: io.ktor.server.application.ApplicationCall, auth: Auth, user: User,
    addressOf: (io.ktor.server.application.ApplicationCall) -> String
): Boolean = try {
    if (auth.countSourceRequest(user, addressOf(call))) true
    else { call.respond(HttpStatusCode.Forbidden, mapOf("error" to SUSPENDED_MESSAGE, "suspended" to true)); false }
} catch (e: TooManyFromAddress) {
    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to e.message)); false
}
