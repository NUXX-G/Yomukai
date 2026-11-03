package com.nelson.yomukai.data.search

import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.domain.model.MediaItem
import kotlinx.coroutines.CancellationException

/** What one source answered to a search. [trusted] holds keys of results it gave for the query itself. */
data class SourceSearchResult(val items: List<MediaItem>, val error: String?, val trusted: Set<String>)

/**
 * Searches one source the way the app does: sites match literally, so when the exact query yields
 * little, relaxed variants are tried (longest word, shortened word), and then other names of the
 * work (Frieren / Sousou no Frieren). [SmartSearch] ranks everything afterwards. Shared by the
 * Android app and the web server.
 */
object SourceSearch {
    suspend fun search(source: CatalogueSource, query: String, aliases: suspend () -> List<String>): SourceSearchResult {
        val items = LinkedHashMap<String, MediaItem>()
        val trusted = HashSet<String>()
        var error: String? = null
        for ((index, variant) in SmartSearch.queryVariants(query).withIndex()) {
            if (index > 0 && items.size >= RELAX_THRESHOLD) break
            source.searchMedia(variant)
                .onSuccess { list ->
                    list.forEach { items.putIfAbsent(it.id, it) }
                    if (index == 0) trusted += list.map(SmartSearch::keyOf)
                }
                .onFailure { e -> if (e is CancellationException) throw e else error = e.message ?: "Error" }
            // A hard failure (blocked/offline) will not improve with another variant.
            if (error != null && items.isEmpty()) break
        }
        // Few results may just mean the site uses another name for it.
        if (error == null && items.size < RELAX_THRESHOLD) {
            aliases().take(MAX_ALIASES).forEach { alias ->
                source.searchMedia(alias).onSuccess { list ->
                    list.filter { SmartSearch.scoreTitle(alias, it.title) >= ALIAS_SCORE }.forEach { item ->
                        items.putIfAbsent(item.id, item)
                        trusted += SmartSearch.keyOf(item)
                    }
                }
            }
        }
        return SourceSearchResult(items.values.toList(), if (items.isEmpty()) error else null, trusted)
    }

    private const val RELAX_THRESHOLD = 5
    private const val MAX_ALIASES = 3
    private const val ALIAS_SCORE = 0.5
}
