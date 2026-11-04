package com.nelson.yomukai.data.search

import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.domain.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** What one source answered to a search. [trusted] holds keys of results it gave for the query itself. */
data class SourceSearchResult(val items: List<MediaItem>, val error: String?, val trusted: Set<String>)

/**
 * Searches one source the way the app does: sites match literally, so when the exact query yields
 * little, relaxed variants (longest word, shortened word) and other names of the work (Frieren /
 * Sousou no Frieren) are tried too, all at the same time so a source with few results answers in
 * about the time of one request. [SmartSearch] ranks everything afterwards. Shared by the Android
 * app and the web server.
 */
object SourceSearch {
    suspend fun search(source: CatalogueSource, query: String, aliases: suspend () -> List<String>): SourceSearchResult = coroutineScope {
        val items = LinkedHashMap<String, MediaItem>()
        val trusted = HashSet<String>()
        val variants = SmartSearch.queryVariants(query)
        var error: String? = null
        source.searchMedia(variants.firstOrNull() ?: query)
            .onSuccess { list ->
                list.forEach { items.putIfAbsent(it.id, it) }
                trusted += list.map(SmartSearch::keyOf)
            }
            .onFailure { e -> if (e is CancellationException) throw e else error = e.message ?: "Error" }
        // A hard failure (blocked/offline) will not improve with another wording; plenty of results need none.
        if (error != null || items.size >= RELAX_THRESHOLD) {
            return@coroutineScope SourceSearchResult(items.values.toList(), if (items.isEmpty()) error else null, trusted)
        }
        // Few results may just mean the site words it or names it differently: every other try at once.
        val relaxed = variants.drop(1).map { variant -> async { source.searchMedia(variant).getOrNull().orEmpty() } }
        val named = async {
            aliases().take(MAX_ALIASES).map { alias -> async { alias to source.searchMedia(alias).getOrNull().orEmpty() } }.awaitAll()
        }
        relaxed.awaitAll().forEach { list -> list.forEach { items.putIfAbsent(it.id, it) } }
        named.await().forEach { (alias, list) ->
            list.filter { SmartSearch.scoreTitle(alias, it.title) >= ALIAS_SCORE }.forEach { item ->
                items.putIfAbsent(item.id, item)
                trusted += SmartSearch.keyOf(item)
            }
        }
        SourceSearchResult(items.values.toList(), null, trusted)
    }

    private const val RELAX_THRESHOLD = 5
    private const val MAX_ALIASES = 3
    private const val ALIAS_SCORE = 0.5
}
