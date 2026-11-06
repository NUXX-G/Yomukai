package com.nelson.yomukai.server

import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The page may only load its own code, styles, images and media; nothing can frame it; the browser's camera,
 * microphone, location and the like are off. Inline style attributes are allowed (Svelte uses them); inline
 * scripts are not. blob: is for spoken audio and the HLS player's worker.
 */
const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; " +
        "media-src 'self' blob:; connect-src 'self'; worker-src 'self' blob:; frame-src 'self'; font-src 'self' data:; " +
        "manifest-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"

const val PERMISSIONS_POLICY =
    "camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), bluetooth=(), midi=(), " +
        "magnetometer=(), gyroscope=(), accelerometer=(), fullscreen=(self), picture-in-picture=(self), autoplay=(self)"

/** True when the visitor reached us over HTTPS (directly, or through the reverse proxy we trust). */
fun isSecure(call: ApplicationCall, config: Config): Boolean =
    call.request.local.scheme == "https" || (config.trustProxy && call.request.headers["X-Forwarded-Proto"] == "https")

/** Security headers on every answer; HSTS only over HTTPS (over plain HTTP it would lock a LAN install out). */
fun securityHeaders(config: Config) = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        val h = call.response
        h.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        h.header("Permissions-Policy", PERMISSIONS_POLICY)
        h.header("X-Content-Type-Options", "nosniff")
        h.header("Referrer-Policy", "no-referrer")
        h.header("X-Frame-Options", "DENY")
        h.header("Cross-Origin-Opener-Policy", "same-origin")
        if (isSecure(call, config)) h.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    }
}

/** Token buckets by text key (addresses, "user:id"...), with a bounded number of keys. */
class KeyedRateLimiter(private val maxKeys: Int = 50_000) {
    private class Bucket(var tokens: Double, var at: Long)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun allow(key: String, perMinute: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (buckets.size > maxKeys) buckets.entries.removeIf { now - it.value.at > 10 * 60_000L }
        val bucket = buckets.getOrPut(key) { Bucket(perMinute.toDouble(), now) }
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
 * Per-address limits before anything else runs: the login, guest, invitation and setup endpoints get a tight
 * budget (password guessing, account farming), the API a generous one (a person reading never gets near it).
 * Per-user limits come later, in [requireUser].
 */
fun addressRateLimit(config: Config, limiter: KeyedRateLimiter, addressOf: (ApplicationCall) -> String) =
    createApplicationPlugin("AddressRateLimit") {
        onCall { call ->
            val path = normalizedPath(call.request.path())
            val (bucket, perMinute) = when {
                path.startsWith("/auth/") && call.request.local.method.value != "GET" -> "auth" to config.authRatePerMinute
                path.startsWith("/auth/") || path.startsWith("/api/") -> "api" to config.ipRatePerMinute
                else -> return@onCall
            }
            val key = "$bucket:" + LoginThrottle.addressKey(addressOf(call))
            if (!limiter.allow(key, perMinute)) {
                call.response.header("Retry-After", "30")
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Demasiadas peticiones desde tu conexión; espera un momento"))
            }
        }
    }

/**
 * Small secrets kept in the database (2FA keys, later the Jellyfin API key) are encrypted with a key derived from the
 * server secret: a copied database alone does not reveal them. AES-256-GCM, random 12-byte nonce, "v1:" prefix.
 */
class SecretBox(serverSecret: ByteArray, purpose: String) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(serverSecret + ":$purpose".toByteArray()), "AES")
    private val random = SecureRandom()

    fun seal(plain: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
        return "v1:" + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain.toByteArray()))
    }

    fun open(sealed: String): String? = runCatching {
        require(sealed.startsWith("v1:"))
        val bytes = Base64.getDecoder().decode(sealed.removePrefix("v1:"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()
}

/** Decoded, with repeated slashes collapsed and lower-cased: "//AUTH/%6Cogin" is counted like "/auth/login". */
fun normalizedPath(path: String): String =
    runCatching { path.decodeURLPart() }.getOrDefault(path).replace(Regex("/{2,}"), "/").lowercase()

fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Equal-time comparison for secrets. */
fun sameSecret(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
