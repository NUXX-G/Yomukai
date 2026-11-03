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
import java.util.concurrent.ConcurrentHashMap


/**
 * Hides titles of excluded genres in two steps, so lists never wait for it. [known] answers at once
 * with what is already known; [check] then reads the pages of titles whose genres nobody listed,
 * a few at a time and in the background, and each answer is remembered.
 */
class ContentFilter(private val preferences: AppPreferences, private val library: LibraryRepository) : TitleFilter {
    /** Genres by source|id; an empty list means the title's page was read and lists none. */
    private val genres = ConcurrentHashMap<String, List<String>>()

    private fun key(item: MediaItem) = SmartSearch.keyOf(item)

    private suspend fun knownGenres(item: MediaItem): List<String>? {
        if (item.genres.isNotEmpty()) return item.genres
        genres[key(item)]?.let { return it }
        val stored = library.getMedia(item.sourceId, item.id)?.genres?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        if (stored.isEmpty()) return null
        genres[key(item)] = stored
        return stored
    }

    /** Removes what is already known to be excluded. Does no network work. */
    override suspend fun known(source: CatalogueSource, items: List<MediaItem>): List<MediaItem> {
        val excluded = preferences.exclusions.value.forSource(source.id)
        if (excluded.isEmpty()) return items
        return items.filterNot { GenreRules.isExcluded(knownGenres(it).orEmpty() + it.type.displayName, excluded) }
    }

    /**
     * [known], plus a short wait for the pages of titles without listed genres, so excluded titles
     * do not flash on screen and vanish a moment later. What is still unknown after the wait is shown
     * and checked by [check] in the background; every page read meanwhile is remembered.
     */
    suspend fun screen(source: CatalogueSource, items: List<MediaItem>): List<MediaItem> {
        val allowed = known(source, items)
        if (preferences.exclusions.value.forSource(source.id).isEmpty()) return allowed
        val hidden = withTimeoutOrNull(SCREEN_WAIT_MS) { check(source, allowed) }
        return if (hidden != null) allowed.filterNot { it.id in hidden } else known(source, allowed)
    }

    /** Reads the pages of titles without known genres and returns the ids that turned out to be excluded. */
    suspend fun check(source: CatalogueSource, items: List<MediaItem>): Set<String> {
        val excluded = preferences.exclusions.value.forSource(source.id)
        if (excluded.isEmpty()) return emptySet()
        val unknown = items.filter { knownGenres(it) == null }.take(MAX_LOOKUPS)
        val limiter = Semaphore(PARALLEL)
        return coroutineScope {
            unknown.map { item ->
                async {
                    val found = limiter.withPermit { lookup(source, item) }
                    item.id.takeIf { GenreRules.isExcluded(found, excluded) }
                }
            }.awaitAll().filterNotNull().toSet()
        }
    }

    private suspend fun lookup(source: CatalogueSource, item: MediaItem): List<String> {
        val full = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { source.fetchMediaDetails(item).getOrNull() } ?: return emptyList()
        genres[key(item)] = full.genres
        runCatching { library.cacheMedia(full) }
        return full.genres
    }

    /**
     * Recommendations carry their genres and tags, and only the global list applies to them. The
     * type counts too, as it does for source lists, so excluding "Manhwa" hides recommended manhwa
     * instead of showing cards that then lead nowhere.
     */
    override fun allows(related: RelatedTitle): Boolean =
        !GenreRules.isExcluded(related.genres + related.tags + related.type.displayName, preferences.exclusions.value.global)

    private companion object {
        const val PARALLEL = 3
        const val MAX_LOOKUPS = 24
        const val LOOKUP_TIMEOUT_MS = 10_000L
        const val SCREEN_WAIT_MS = 2_500L
    }
}
