package com.nelson.yomukai.server

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Everything is set through environment variables, as Docker expects. Only the admin password is
 * needed in practice; the install script generates it.
 */
data class Config(
    val port: Int,
    val dataDir: File,
    val webDir: File,
    val adminUser: String,
    val adminPassword: String?,
    /** Signs proxied media URLs so the server is no open proxy. Generated and kept in [dataDir] when unset. */
    val secret: ByteArray,
    val guestsEnabled: Boolean,
    val guestDays: Int,
    val flareSolverrUrl: String?,
    val malClientId: String,
    /** Requests a guest may make per minute; accounts get [ACCOUNT_RATE]. */
    val guestRatePerMinute: Int,
    /** Video streams a guest may have open at once. */
    val guestStreams: Int,
    /** Public address, for links in the export file; optional. */
    val publicUrl: String?
) {
    companion object {
        const val ACCOUNT_RATE = 600

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val dataDir = File(env["DATA_DIR"] ?: "data").apply { mkdirs() }
            return Config(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                dataDir = dataDir,
                webDir = File(env["WEB_DIR"] ?: "web/dist"),
                adminUser = env["ADMIN_USER"]?.trim()?.ifBlank { null } ?: "admin",
                adminPassword = env["ADMIN_PASSWORD"]?.ifBlank { null },
                secret = env["SECRET"]?.ifBlank { null }?.toByteArray() ?: persistentSecret(dataDir),
                guestsEnabled = env["GUESTS_ENABLED"]?.lowercase() != "false",
                guestDays = env["GUEST_DAYS"]?.toIntOrNull()?.coerceIn(1, 365) ?: 7,
                flareSolverrUrl = env["FLARESOLVERR_URL"]?.trimEnd('/')?.ifBlank { null },
                malClientId = env["MAL_CLIENT_ID"].orEmpty().trim(),
                guestRatePerMinute = env["GUEST_RATE"]?.toIntOrNull() ?: 240,
                guestStreams = env["GUEST_STREAMS"]?.toIntOrNull() ?: 2,
                publicUrl = env["PUBLIC_URL"]?.trimEnd('/')?.ifBlank { null }
            )
        }

        /** A random key written once, so signed links survive restarts. */
        private fun persistentSecret(dir: File): ByteArray {
            val file = File(dir, "secret.key")
            if (file.exists()) return Base64.getDecoder().decode(file.readText().trim())
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            file.writeText(Base64.getEncoder().encodeToString(key))
            return key
        }
    }
}
