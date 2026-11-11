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
        Feature("jellyfin", "Jellyfin y NAS", "Descargar al Jellyfin, obras seguidas y exportar cómics a Kavita/Komga (lo hace el servidor de casa)"),
        Feature("ocr", "OCR de cómics", "Texto de los bocadillos (móvil o servidor de casa), compartido para no repetirlo"),
        Feature("learning", "Aprender idiomas", "Palabras guardadas con repaso espaciado (FSRS) y exportación a Anki; cada usuario lo activa"),
        Feature("themes", "Temas", "Temas personalizados con editor, contraste WCAG y galería entre amigos"),
        Feature("forum", "Foro", "Hilo por obra y categorías generales, con spoilers por capítulo y moderación"),
        Feature("work_index", "Índice de obras", "El servidor recorre las fuentes poco a poco y junta sus títulos en obras por idioma: listas, búsqueda y fichas al instante"),
        Feature("tts_browser", "Voces naturales en el navegador", "Voces inglesas Kokoro que el navegador genera solo (unos 90 MB la primera vez); el servidor solo guarda el modelo"),
        Feature("tts_nightly", "Voces preparadas de madrugada", "De 3 a 6 el servidor de casa prepara el audio del siguiente capítulo de las novelas que cada uno lee, con sus voces"),
        Feature("season_social", "Temporada en el calendario", "Recomendaciones y comentarios del grupo sobre los estrenos y lo que valora la comunidad de AniList"),
        Feature("online_translate", "Traductor en línea de respaldo", "Si el servidor de casa está apagado y el navegador no traduce, lo seleccionado (frases cortas) se traduce con MyMemory, gratis y con un tope diario"),
        Feature("rooms", "Salas para ver juntos", "Watch party sincronizada con chat y reacciones; cada cliente reproduce su propio vídeo"),
        Feature("open_rooms", "Salas comunes", "Salas abiertas a todo el grupo con cola de episodios de varios animes, votos para saltar y paso automático"),
        Feature("dms", "Mensajes directos", "Conversaciones 1 a 1 en tiempo real entre miembros (texto y obras compartidas); nunca invitados"),
        Feature("sharing", "Compartir obras", "Mandar una obra, episodio o capítulo a un mensaje directo, una sala o un hilo del foro, con escudo anti-spoilers"),
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
