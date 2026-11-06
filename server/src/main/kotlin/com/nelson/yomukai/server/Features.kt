package com.nelson.yomukai.server

import java.util.concurrent.ConcurrentHashMap

/**
 * A big feature that the admin can switch off from the panel if it misbehaves. The web and the Android app ask
 * `/api/features` and hide what is off. New features add an entry here; the name never changes once released.
 */
data class Feature(val name: String, val title: String, val description: String, val default: Boolean = true)

object FeatureCatalog {
    val ALL: List<Feature> = listOf(
        Feature("worker_jobs", "Trabajos en el servidor de casa", "Cola de trabajos pesados (voces, OCR, subtítulos, descargas) para los workers"),
        Feature("bandwidth_cap", "Tope de tráfico de vídeo", "Corta el vídeo que pasa por el VPS al llegar al tope mensual"),
        Feature("ytdlp", "yt-dlp para los vídeos", "Prueba yt-dlp antes que los extractores propios al buscar el vídeo de un reproductor"),
        Feature("source_repair", "Auto-reparación de fuentes", "Cuando el canario ve una fuente rota, el worker propone un arreglo como PR en GitHub (nunca se aplica solo)", default = false)
    )
    private val byName = ALL.associateBy { it.name }
    fun get(name: String): Feature? = byName[name]
}

/** Admin overrides of [FeatureCatalog] defaults, kept in the database and in memory. */
class FeatureFlags(private val db: Database) {
    private val overrides = ConcurrentHashMap<String, Boolean>()

    @Volatile private var loaded = false

    private suspend fun load() {
        if (loaded) return
        db.tx { query("SELECT name, enabled FROM feature_flags") { getString(1) to (getInt(2) == 1) } }.forEach { (k, v) -> overrides[k] = v }
        loaded = true
    }

    suspend fun isOn(name: String): Boolean {
        load()
        val feature = FeatureCatalog.get(name) ?: return false
        return overrides[name] ?: feature.default
    }

    suspend fun all(): Map<String, Boolean> {
        load()
        return FeatureCatalog.ALL.associate { it.name to (overrides[it.name] ?: it.default) }
    }

    suspend fun set(name: String, enabled: Boolean) {
        require(FeatureCatalog.get(name) != null) { "Funcionalidad desconocida: $name" }
        db.tx {
            update(
                "INSERT INTO feature_flags(name, enabled, updated_at) VALUES(?,?,?) ON CONFLICT(name) DO UPDATE SET enabled = excluded.enabled, updated_at = excluded.updated_at",
                name, enabled, System.currentTimeMillis()
            )
        }
        overrides[name] = enabled
    }
}
