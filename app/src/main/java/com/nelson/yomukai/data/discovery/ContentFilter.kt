package com.nelson.yomukai.data.discovery

import com.nelson.yomukai.data.prefs.AppPreferences
import com.nelson.yomukai.data.repository.LibraryRepository
import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.domain.model.MediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Which genres hide a title. Pure, so it is unit tested. */
object GenreRules {
    private val SAME = listOf(
        setOf("bl", "yaoi", "boys love", "boy s love", "shounen ai", "shonen ai"),
        setOf("gl", "yuri", "girls love", "girl s love", "shoujo ai", "shojo ai"),
        setOf("gore", "violencia extrema"),
        setOf("harem", "harén"),
        setOf("sci fi", "ciencia ficcion", "science fiction"),
        setOf("terror", "horror"),
        setOf("accion", "action"),
        setOf("romance", "romantico"),
        setOf("comedia", "comedy"),
        setOf("fantasia", "fantasy"),
        setOf("sobrenatural", "supernatural"),
        setOf("psicologico", "psychological"),
        setOf("deportes", "sports"),
        setOf("recuentos de la vida", "slice of life")
    ).map { group -> group.map(SmartSearch::normalize).toSet() }

    private fun variants(genre: String): Set<String> {
        val key = SmartSearch.normalize(genre)
        return SAME.firstOrNull { key in it } ?: setOf(key)
    }

    /** True when any of [genres] is one of [excluded], in any language or spelling the rules know. */
    fun isExcluded(genres: Collection<String>, excluded: Set<String>): Boolean {
        val banned = excluded.flatMap(::variants).toSet()
        return genres.any { SmartSearch.normalize(it) in banned }
    }
}

/**
 * Hides titles of excluded genres. Most sites list no genres in their catalogues, so the genres
 * of those titles are read from their pages, a few at a time, and remembered in the database.
 * Nothing of this runs while no genre is excluded.
 */
class ContentFilter(private val preferences: AppPreferences, private val library: LibraryRepository) {
    suspend fun apply(source: CatalogueSource, items: List<MediaItem>): List<MediaItem> {
        val excluded = preferences.exclusions.value.forSource(source.id)
        if (excluded.isEmpty() || items.isEmpty()) return items
        val limiter = Semaphore(PARALLEL)
        var lookups = 0
        return coroutineScope {
            items.map { item ->
                val known = item.genres.ifEmpty { library.getMedia(item.sourceId, item.id)?.genres?.split('|')?.filter { it.isNotBlank() }.orEmpty() }
                val lookup = known.isEmpty() && lookups++ < MAX_LOOKUPS
                async {
                    val genres = if (lookup) limiter.withPermit { lookupGenres(source, item) } else known
                    item.takeUnless { GenreRules.isExcluded(genres + item.type.displayName, excluded) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun lookupGenres(source: CatalogueSource, item: MediaItem): List<String> {
        val full = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { source.fetchMediaDetails(item).getOrNull() } ?: return emptyList()
        runCatching { library.cacheMedia(full) }
        return full.genres
    }

    /** Recommendations carry their genres, and only the global list applies to them. */
    fun allows(related: RelatedTitle): Boolean = !GenreRules.isExcluded(related.genres, preferences.exclusions.value.global)

    private companion object {
        const val PARALLEL = 4
        const val MAX_LOOKUPS = 40
        const val LOOKUP_TIMEOUT_MS = 12_000L
    }
}
