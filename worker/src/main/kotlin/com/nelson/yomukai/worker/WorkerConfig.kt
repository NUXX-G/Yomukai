package com.nelson.yomukai.worker

import java.io.File

/** Everything comes from environment variables (Docker or systemd). Only the VPS address and the token are required. */
data class WorkerConfig(
    /** The VPS, e.g. https://yomukai.example.com or http://100.x.y.z:8080 over Tailscale. */
    val serverUrl: String,
    val token: String,
    /** Jobs at once; by default from the machine's cores and memory. */
    val concurrency: Int,
    /** Above this load per core the worker stops taking jobs until the machine calms down. */
    val maxLoadPerCore: Double,
    /** Engines idle this long are unloaded from memory. */
    val idleUnloadMs: Long,
    /** Where finished files are prepared before upload. */
    val workDir: File,
    /** If set, the latest VPS database backup is copied here (e.g. the NAS) every few hours. */
    val backupDir: File?,
    /** Limit the engines offered (comma list); empty = all that are installed. */
    val onlyEngines: Set<String>,
    /** While this file exists the worker takes no jobs (a manual "pause" switch). */
    val pauseFile: File?
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): WorkerConfig {
            val url = env["YOMUKAI_URL"]?.trim()?.trimEnd('/').orEmpty()
            require(url.startsWith("https://") || url.startsWith("http://")) { "Falta YOMUKAI_URL (dirección del VPS)" }
            val token = env["WORKER_TOKEN"]?.trim()?.ifBlank { null }
                ?: env["WORKER_TOKEN_FILE"]?.let { File(it).takeIf(File::isFile)?.readText()?.trim() }
                ?: error("Falta WORKER_TOKEN (créalo en Ajustes › Administración › Workers)")
            require(token.startsWith("ykw_")) { "WORKER_TOKEN no parece un token de worker de Yomukai" }
            if (url.startsWith("http://") && !isPrivateHost(url)) {
                error("YOMUKAI_URL con http:// solo se admite dentro de Tailscale o la red local; usa https://")
            }
            return WorkerConfig(
                serverUrl = url,
                token = token,
                concurrency = env["WORKER_CONCURRENCY"]?.toIntOrNull()?.coerceIn(1, 16) ?: Capabilities.defaultConcurrency(),
                maxLoadPerCore = env["WORKER_MAX_LOAD"]?.toDoubleOrNull()?.coerceIn(0.1, 10.0) ?: 0.85,
                idleUnloadMs = (env["WORKER_IDLE_UNLOAD_MIN"]?.toLongOrNull()?.coerceIn(1, 1440) ?: 10) * 60_000,
                workDir = File(env["WORKER_WORK_DIR"] ?: System.getProperty("java.io.tmpdir") + "/yomukai-worker").apply { mkdirs() },
                backupDir = env["WORKER_BACKUP_DIR"]?.ifBlank { null }?.let(::File),
                onlyEngines = env["WORKER_ENGINES"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                pauseFile = env["WORKER_PAUSE_FILE"]?.ifBlank { null }?.let(::File)
            )
        }

        /** Tailscale (100.64.0.0/10, *.ts.net), private ranges and localhost. */
        fun isPrivateHost(url: String): Boolean {
            val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            if (host == "localhost" || host.endsWith(".ts.net") || host.endsWith(".local")) return true
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 100 && parts[1] in 64..127)
        }
    }
}
