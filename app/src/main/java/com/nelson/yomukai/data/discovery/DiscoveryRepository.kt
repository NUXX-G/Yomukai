package com.nelson.yomukai.data.discovery

import androidx.compose.runtime.Immutable
import com.nelson.yomukai.data.prefs.AppPreferences
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** The same work in another source. */
@Immutable
data class Alternative(
    val item: MediaItem,
    val sourceName: String,
    val language: String,
    val confidence: MatchConfidence,
    /** Null when the list could not be read. */
    val chapterCount: Int? = null,
    val lastChapter: Float? = null
)

/** Finds what is similar to a title and where else the same title is available. */
class DiscoveryRepository(
    private val sources: SourceManager,
    private val preferences: AppPreferences,
    private val aniList: AniListClient
) {
    private fun readableIn(source: CatalogueSource, type: MediaType): Boolean = when (type) {
        MediaType.ANIME -> source is AnimeSource
        MediaType.NOVEL -> source is NovelSource
        else -> source is MangaSource
    }

    private fun candidates(type: MediaType, excludeSourceId: String?): List<CatalogueSource> =
        sources.enabled(preferences.app.value.enabledSources)
            .filter { it.id != excludeSourceId && readableIn(it, type) }

    /** AniList's names first: they are what other sources tend to use. Latin script searches best. */
    private suspend fun allNames(media: MediaItem): List<String> {
        val own = listOf(media.title) + media.altTitles
        val known = runCatching { aniList.lookup(own, media.type) }.getOrNull()?.names.orEmpty()
        return (own + known).filter { it.isNotBlank() }.distinct()
    }

    private fun searchable(names: List<String>): List<String> =
        names.sortedBy { name -> name.any { it.code > LATIN_LIMIT } }.take(MAX_QUERIES)

    /**
     * Titles like [media]: its community's recommendations, or for works AniList does not cover
     * (Western comics, mostly) what its own source returns for the title's main word.
     */
    suspend fun similar(media: MediaItem): List<RelatedTitle> {
        val own = listOf(media.title) + media.altTitles
        val entry = runCatching { aniList.lookup(own, media.type) }.getOrNull()
        if (entry != null && entry.related.isNotEmpty()) return entry.related.take(MAX_SIMILAR)
        val word = media.title.split(' ').filter { it.length >= MIN_WORD }.maxByOrNull { it.length } ?: return emptyList()
        val source = sources.get(media.sourceId) ?: return emptyList()
        return source.searchMedia(word).getOrDefault(emptyList())
            .filter { it.id != media.id }
            .take(MAX_SIMILAR)
            .map { RelatedTitle(it.title, listOf(it.title), it.coverUrl, it.type, item = it) }
    }

    /**
     * Copies of [media] in the other enabled sources. [onFound] reports each one as soon as it is
     * confirmed, so slow sources do not hold back the fast ones.
     */
    suspend fun alternatives(media: MediaItem, onFound: (Alternative) -> Unit) {
        val names = allNames(media)
        forEachSource(media.type, media.sourceId) { source ->
            match(source, names, media.author, media.type)?.let { found ->
                val chapters = withTimeoutOrNull(CHAPTERS_TIMEOUT_MS) { source.fetchChapterList(found.item.id) }?.getOrNull()
                onFound(found.copy(chapterCount = chapters?.size, lastChapter = chapters?.maxOfOrNull { it.chapterNumber }))
            }
        }
    }

    /** Where a recommended title can be read, for opening it. */
    suspend fun locate(related: RelatedTitle): List<Alternative> {
        val found = java.util.Collections.synchronizedList(mutableListOf<Alternative>())
        forEachSource(related.type, null) { source ->
            match(source, related.names, "", related.type)?.let { found += it }
        }
        return found.sortedBy { it.sourceName }
    }

    private suspend fun forEachSource(type: MediaType, exclude: String?, block: suspend (CatalogueSource) -> Unit) = coroutineScope {
        val limiter = Semaphore(MAX_PARALLEL)
        candidates(type, exclude).map { source ->
            async { limiter.withPermit { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { block(source) } } }
        }.awaitAll()
    }

    /** Searches [source] for the title and returns the entry that is the same work, if any. */
    private suspend fun match(source: CatalogueSource, names: List<String>, author: String, type: MediaType): Alternative? {
        for (query in searchable(names)) {
            val results = source.searchMedia(query).getOrNull() ?: continue
            pick(results, names, author, type)?.let { (item, confidence) ->
                return Alternative(item, source.name, source.language, confidence)
            }
            // Search pages rarely list other names: the closest entries' own pages may have them.
            results.filter { TitleMatcher.nearMatch(names, it) }.take(MAX_DETAILS).forEach { near ->
                val full = source.fetchMediaDetails(near).getOrNull() ?: return@forEach
                val confidence = TitleMatcher.compare(names, author, type, full)
                if (confidence != MatchConfidence.NONE) return Alternative(full, source.name, source.language, confidence)
            }
        }
        return null
    }

    private fun pick(results: List<MediaItem>, names: List<String>, author: String, type: MediaType): Pair<MediaItem, MatchConfidence>? =
        results.map { it to TitleMatcher.compare(names, author, type, it) }
            .filter { it.second != MatchConfidence.NONE }
            .minByOrNull { it.second.ordinal }

    private companion object {
        const val MAX_SIMILAR = 20
        const val MAX_QUERIES = 3
        const val MAX_DETAILS = 2
        const val MAX_PARALLEL = 4
        const val MIN_WORD = 4
        const val LATIN_LIMIT = 0x24F
        const val SOURCE_TIMEOUT_MS = 30_000L
        const val CHAPTERS_TIMEOUT_MS = 20_000L
    }
}
