package com.nelson.yomukai.data.discovery

import com.nelson.yomukai.i18n.tr
import java.util.Locale

/** Spanish names for AniList's formats and genres, and its 0..100 score as "8,4" (the web has the same table). */
object DiscoverLabels {
    private val FORMAT = mapOf(
        "TV" to "Serie", "TV_SHORT" to "Corto", "MOVIE" to "Película", "SPECIAL" to "Especial", "OVA" to "OVA", "ONA" to "ONA",
        "MUSIC" to "Música", "MANGA" to "Manga", "NOVEL" to "Novela", "ONE_SHOT" to "One-shot"
    )
    private val GENRE = mapOf(
        "Action" to "Acción", "Adventure" to "Aventura", "Comedy" to "Comedia", "Drama" to "Drama", "Ecchi" to "Ecchi",
        "Fantasy" to "Fantasía", "Horror" to "Terror", "Mahou Shoujo" to "Mahou shoujo", "Mecha" to "Mecha", "Music" to "Música",
        "Mystery" to "Misterio", "Psychological" to "Psicológico", "Romance" to "Romance", "Sci-Fi" to "Ciencia ficción",
        "Slice of Life" to "Recuentos de la vida", "Sports" to "Deportes", "Supernatural" to "Sobrenatural", "Thriller" to "Suspense"
    )

    fun format(code: String?): String = FORMAT[code]?.let { tr(it) }.orEmpty()
    fun genre(name: String): String = GENRE[name]?.let { tr(it) } ?: tr(name)
    fun score(score: Int?): String = score?.takeIf { it > 0 }?.let { String.format(Locale.forLanguageTag("es"), "%.1f", it / 10f) }.orEmpty()
}
