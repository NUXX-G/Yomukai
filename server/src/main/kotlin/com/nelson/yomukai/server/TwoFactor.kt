package com.nelson.yomukai.server

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Time-based one-time passwords (RFC 6238 over RFC 4226), as Google Authenticator, Aegis or 2FAS expect. */
object Totp {
    private const val STEP_S = 30L
    private const val DIGITS = 6
    private val random = SecureRandom()
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** A new 160-bit secret, Base32 as authenticator apps take it. */
    fun newSecret(): String = base32(ByteArray(20).also(random::nextBytes))

    fun step(atMs: Long): Long = atMs / 1000 / STEP_S

    /** The code for one time step. */
    fun code(secret: ByteArray, step: Long, algorithm: String = "HmacSHA1", digits: Int = DIGITS): String {
        val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(secret, algorithm)) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /**
     * The step [code] belongs to, accepting one step either side for clock drift, or null. Steps at or before
     * [lastUsedStep] are refused, so a code seen over someone's shoulder cannot be used a second time.
     */
    fun verify(secretBase32: String, code: String, atMs: Long, lastUsedStep: Long = -1): Long? {
        val clean = code.filter(Char::isDigit)
        if (clean.length != DIGITS) return null
        val secret = decodeBase32(secretBase32) ?: return null
        val now = step(atMs)
        return (now - 1..now + 1).firstOrNull { s -> s > lastUsedStep && sameSecret(code(secret, s), clean) }
    }

    fun uri(secret: String, account: String, issuer: String = "Yomukai"): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secret&issuer=${enc(issuer)}&algorithm=SHA1&digits=6&period=30"
    }

    fun base32(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                append(BASE32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) append(BASE32[(buffer shl (5 - bits)) and 31])
    }

    fun decodeBase32(text: String): ByteArray? {
        val clean = text.uppercase().filter { it != '=' && !it.isWhitespace() }
        if (clean.isEmpty() || clean.any { it !in BASE32 }) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            buffer = (buffer shl 5) or BASE32.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

/** One-use codes for when the phone with the authenticator is lost. Only their SHA-256 is stored. */
object RecoveryCodes {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    fun generate(count: Int = 10): List<String> = List(count) {
        (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("").let { "${it.take(5)}-${it.drop(5)}" }
    }

    fun normalize(code: String): String = code.lowercase().filter { it.isLetterOrDigit() }
    fun hash(code: String): String = sha256Hex("recovery:" + normalize(code))
}
