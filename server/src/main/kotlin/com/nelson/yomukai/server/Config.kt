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
    /** Way out when a site cannot be reached from here (LaLiga's blocks in Spain): socks5://host:port or http://host:port. */
    val outboundProxy: String? = null,
    val malClientId: String,
    /** Requests a guest may make per minute; accounts get [ACCOUNT_RATE]. */
    val guestRatePerMinute: Int,
    /** Video streams a guest may have open at once. */
    val guestStreams: Int,
    /**
     * Requests to the sources (lists, searches, chapters, pages...) an account may make in ten minutes
     * before it is suspended until an admin lifts it; [guestAbuseLimit] for guests. Admins are exempt.
     */
    val abuseLimit: Int = 1500,
    val guestAbuseLimit: Int = 300,
    /** Read the visitor address from the proxy's X-Forwarded-For entry; set only behind nginx, Caddy or Traefik. */
    val trustProxy: Boolean,
    /** Public address, for links in the export file; optional. */
    val publicUrl: String?,
    /** Nightly SQLite copies: folder, days kept, hour (server time zone), and an optional second folder (NAS/worker). */
    val backupDir: File = File(dataDir, "backups"),
    val backupKeepDays: Int = 14,
    val backupHour: Int = 4,
    val backupCopyDir: File? = null,
    val backupsEnabled: Boolean = true,
    /** Disk for finished worker results (audio, OCR, subtitles...); least recently used go first. */
    val resultCacheBytes: Long = 2L * 1024 * 1024 * 1024,
    /** Disk for page and cover images already fetched. */
    val imageCacheBytes: Long = 2L * 1024 * 1024 * 1024,
    /** Monthly cap for video through the VPS; 0 = only measure. */
    val bandwidthCapBytes: Long = 0,
    /** "All in one" install: the worker in the same docker compose uses this token, registered at start-up. */
    val localWorkerToken: String? = null,
    /** Extra signed source repositories the admin trusts: "https://…/bundle.json#BASE64_ED25519_KEY", comma separated. */
    val sourceRepos: List<SourceCatalog.Repo> = emptyList(),
    /** Upload of the home connection in Mbit/s: rooms watching from the NAS are advised a quality that fits. */
    val nasUploadMbps: Int = 20,
    /** Admin warnings: Discord webhook and/or ntfy topic URL (with optional token). */
    val discordWebhook: String? = null,
    val ntfyUrl: String? = null,
    val ntfyToken: String? = null,
    /** ntfy server for users' phone notifications (each user gets a private random topic). */
    val pushUrl: String = "https://ntfy.sh",
    /** Per address and minute: login/guest/invitation/setup attempts, and API requests. */
    val authRatePerMinute: Int = 30,
    val ipRatePerMinute: Int = 1200,
    /** Pause between sources in a canary run, to stay gentle with the sites. */
    val canaryPauseMs: Long = 20_000,
    /** Where newer signed source bundles come from. */
    val sourcesUrl: String = com.nelson.yomukai.data.source.declarative.SourceBundles.REMOTE_URL,
    /** Optional TMDB key: wide backdrops for anime title pages (never sent to clients). */
    val tmdbApiKey: String = ""
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
                guestsEnabled = env["GUESTS_ENABLED"]?.lowercase() == "true",
                guestDays = env["GUEST_DAYS"]?.toIntOrNull()?.coerceIn(1, 365) ?: 1,
                flareSolverrUrl = env["FLARESOLVERR_URL"]?.trimEnd('/')?.ifBlank { null },
                outboundProxy = env["OUTBOUND_PROXY"]?.trim()?.ifBlank { null },
                malClientId = env["MAL_CLIENT_ID"].orEmpty().trim(),
                tmdbApiKey = env["TMDB_API_KEY"].orEmpty().trim(),
                guestRatePerMinute = env["GUEST_RATE"]?.toIntOrNull() ?: 120,
                abuseLimit = env["ABUSE_LIMIT"]?.toIntOrNull()?.coerceAtLeast(100) ?: 1500,
                guestAbuseLimit = env["GUEST_ABUSE_LIMIT"]?.toIntOrNull()?.coerceAtLeast(50) ?: 300,
                guestStreams = env["GUEST_STREAMS"]?.toIntOrNull() ?: 2,
                trustProxy = env["TRUST_PROXY"]?.lowercase() == "true",
                publicUrl = env["PUBLIC_URL"]?.trimEnd('/')?.ifBlank { null },
                backupDir = env["BACKUP_DIR"]?.ifBlank { null }?.let(::File) ?: File(dataDir, "backups"),
                backupKeepDays = env["BACKUP_KEEP_DAYS"]?.toIntOrNull()?.coerceIn(1, 365) ?: 14,
                backupHour = env["BACKUP_HOUR"]?.toIntOrNull()?.coerceIn(0, 23) ?: 4,
                backupCopyDir = env["BACKUP_COPY_DIR"]?.ifBlank { null }?.let(::File),
                backupsEnabled = env["BACKUPS_ENABLED"]?.lowercase() != "false",
                resultCacheBytes = (env["RESULT_CACHE_MB"]?.toLongOrNull()?.coerceIn(64, 1_000_000) ?: 2048) * 1024 * 1024,
                imageCacheBytes = (env["IMAGE_CACHE_MB"]?.toLongOrNull()?.coerceIn(64, 1_000_000) ?: 2048) * 1024 * 1024,
                nasUploadMbps = env["NAS_UPLOAD_MBPS"]?.toIntOrNull()?.coerceIn(1, 10_000) ?: 20,
                sourceRepos = SourceCatalog.parseRepos(env["SOURCE_REPOS"]),
                localWorkerToken = env["LOCAL_WORKER_TOKEN"]?.trim()?.takeIf { it.startsWith("ykw_") && it.length in 30..100 },
                bandwidthCapBytes = ((env["BANDWIDTH_CAP_GB"]?.toDoubleOrNull()?.coerceIn(0.0, 1_000_000.0) ?: 0.0) * 1_073_741_824).toLong(),
                discordWebhook = env["DISCORD_WEBHOOK_URL"]?.trim()?.takeIf { it.startsWith("https://") },
                ntfyUrl = env["NTFY_URL"]?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                ntfyToken = env["NTFY_TOKEN"]?.trim()?.ifBlank { null },
                pushUrl = env["NTFY_PUSH_URL"]?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: "https://ntfy.sh",
                authRatePerMinute = env["AUTH_RATE"]?.toIntOrNull()?.coerceIn(5, 1_000) ?: 30,
                ipRatePerMinute = env["IP_RATE"]?.toIntOrNull()?.coerceIn(60, 100_000) ?: 1200,
                canaryPauseMs = (env["CANARY_PAUSE_S"]?.toLongOrNull()?.coerceIn(0, 600) ?: 20) * 1000,
                sourcesUrl = env["SOURCES_URL"]?.trim()?.takeIf { it.startsWith("https://") } ?: com.nelson.yomukai.data.source.declarative.SourceBundles.REMOTE_URL
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
