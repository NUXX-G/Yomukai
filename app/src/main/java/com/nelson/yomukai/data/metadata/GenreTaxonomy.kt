package com.nelson.yomukai.data.metadata

import com.nelson.yomukai.data.search.SmartSearch

/**
 * Yomukai's own genre names. Every source spells genres its own way and in its own language
 * ("Acción", "Action", "accion", "Shounen-ai", "BL"...); they all map to one Spanish label here, so
 * the same genre looks the same everywhere and an exclusion catches every spelling. Pure.
 */
object GenreTaxonomy {
    /** Canonical label first, then every known spelling. Matching ignores case, accents and punctuation. */
    private val GROUPS: List<List<String>> = listOf(
        listOf("Acción", "action", "accion"),
        listOf("Aventura", "adventure", "aventuras"),
        listOf("Comedia", "comedy", "humor", "comedia romantica"),
        listOf("Drama", "dramatic", "dramatico"),
        listOf("Fantasía", "fantasy", "fantasia", "high fantasy", "dark fantasy", "fantasia oscura"),
        listOf("Romance", "romantico", "romantic", "romance drama"),
        listOf("Ciencia ficción", "sci fi", "scifi", "science fiction", "ciencia ficcion"),
        listOf("Terror", "horror", "miedo"),
        listOf("Misterio", "mystery", "detective", "detectives", "policiaco"),
        listOf("Psicológico", "psychological", "psicologico"),
        listOf("Suspense", "thriller", "suspenso", "suspense"),
        listOf("Sobrenatural", "supernatural"),
        listOf("Recuentos de la vida", "slice of life", "vida cotidiana", "cotidiano", "recuentos de vida"),
        listOf("Deportes", "sports", "sport", "deporte"),
        listOf("Mecha", "mechas", "robots"),
        listOf("Música", "music", "musica", "musical"),
        listOf("Escolar", "school", "school life", "vida escolar", "instituto", "colegio"),
        listOf("Histórico", "historical", "historico", "historia"),
        listOf("Militar", "military", "guerra", "war"),
        listOf("Artes marciales", "martial arts", "artes marciales", "wuxia", "murim", "cultivation", "cultivo"),
        listOf("Isekai", "otro mundo", "another world", "reencarnacion", "reincarnation", "transmigration", "transmigracion"),
        listOf("Magia", "magic", "magico", "mahou"),
        listOf("Mahou shoujo", "magical girl", "magical girls", "chicas magicas", "mahou shojo"),
        listOf("Harem", "harén", "harem inverso", "reverse harem"),
        listOf("Ecchi", "fan service", "fanservice"),
        listOf("Gore", "violencia extrema"),
        listOf("BL", "yaoi", "boys love", "boy s love", "boys' love", "shounen ai", "shonen ai"),
        listOf("GL", "yuri", "girls love", "girl s love", "girls' love", "shoujo ai", "shojo ai"),
        listOf("Shounen", "shonen"),
        listOf("Shoujo", "shojo"),
        listOf("Seinen"),
        listOf("Josei"),
        listOf("Tragedia", "tragedy"),
        listOf("Superhéroes", "superhero", "superheroes", "super heroes"),
        listOf("Vampiros", "vampire", "vampires", "vampiro"),
        listOf("Demonios", "demons", "demon", "demonio"),
        listOf("Zombis", "zombie", "zombies", "zombis"),
        listOf("Juegos", "game", "games", "videojuegos", "video games", "gamer", "sistema", "system"),
        listOf("Apocalíptico", "post apocalyptic", "apocalyptic", "apocaliptico", "postapocaliptico", "survival", "supervivencia"),
        listOf("Venganza", "revenge"),
        listOf("Cocina", "cooking", "gourmet", "comida", "food"),
        listOf("Medicina", "medical", "medico"),
        listOf("Gender bender", "genderswap", "cambio de genero"),
        listOf("Adulto", "adult", "mature", "maduro", "+18", "smut", "hentai")
    )

    private val canonicalOf: Map<String, String> = buildMap {
        GROUPS.forEach { group ->
            val label = group.first()
            group.forEach { put(SmartSearch.normalize(it), label) }
        }
    }

    private val groupOf: Map<String, Set<String>> = buildMap {
        GROUPS.forEach { group ->
            val keys = group.map(SmartSearch::normalize).toSet()
            keys.forEach { put(it, keys) }
        }
    }

    /** "accion" -> "Acción"; an unknown genre keeps its own spelling, tidied up. */
    fun canonical(genre: String): String {
        val key = SmartSearch.normalize(genre)
        canonicalOf[key]?.let { return it }
        val trimmed = genre.trim()
        return trimmed.replaceFirstChar { it.uppercase() }
    }

    /** Every known spelling of [genre], normalized; just its own key when unknown. */
    fun variants(genre: String): Set<String> {
        val key = SmartSearch.normalize(genre)
        return groupOf[key] ?: setOf(key)
    }

    fun isKnown(genre: String): Boolean = SmartSearch.normalize(genre) in canonicalOf

    /** Tags about the format or the release, not the story: they only add noise next to genres. */
    private val NOISE = setOf(
        "long strip", "full color", "web comic", "webcomic", "adaptation", "official colored", "award winning",
        "oneshot", "one shot", "anthology", "doujinshi", "fan colored", "user created", "4 koma", "manhwa", "manhua",
        "manga", "webtoon", "novela", "novel", "comic", "anime"
    ).map(SmartSearch::normalize).toSet()

    fun isNoise(genre: String): Boolean = SmartSearch.normalize(genre) in NOISE

    /** Merges genre lists from several origins into one tidy list without repeats, keeping the order seen. */
    fun merge(vararg lists: Collection<String>): List<String> {
        val seen = LinkedHashMap<String, String>()
        lists.forEach { list ->
            list.filter { it.isNotBlank() && it.length <= MAX_LENGTH && !isNoise(it) }.forEach { genre ->
                val label = canonical(genre)
                seen.putIfAbsent(SmartSearch.normalize(label), label)
            }
        }
        return seen.values.toList()
    }

    private const val MAX_LENGTH = 40
}
