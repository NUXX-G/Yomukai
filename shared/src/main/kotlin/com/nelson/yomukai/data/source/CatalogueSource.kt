package com.nelson.yomukai.data.source

import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource

/** Common contract for every catalogue (manga or novel) the app can browse. */
interface CatalogueSource {
    /** Stable identifier persisted in the database; never change it once shipped. */
    val id: String
    val name: String
    val baseUrl: String
    /** "ES", "EN" or "MULTI". */
    val language: String
    val supportedTypes: Set<MediaType>

    /** Sources whose sites are frequently offline or heavily protected; disabled by default. */
    val isExperimental: Boolean get() = false
    val supportsLatestPaging: Boolean get() = false

    /** Other domains this source loads from (image CDNs, players) so per-source Tor exclusion covers them. */
    val extraHosts: List<String> get() = emptyList()

    /** Headers needed to load images/pages from this source (usually a Referer). */
    val imageHeaders: Map<String, String> get() = mapOf("Referer" to "$baseUrl/")

    /** Filters this source understands in its catalogue; empty = only the default listing. */
    val filters: List<SourceFilter> get() = emptyList()

    /**
     * One page of the full catalogue with [values] applied. Sources without filters simply
     * return their paged latest listing.
     */
    suspend fun browse(page: Int, values: FilterValues = emptyMap()): Result<List<MediaItem>> = fetchLatestUpdates(page)

    suspend fun fetchLatestUpdates(page: Int = 1): Result<List<MediaItem>>
    suspend fun searchMedia(query: String): Result<List<MediaItem>>

    /** Full metadata (description, author, genres...). Defaults to no extra info. */
    suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = Result.success(media)

    /** Chapters in any order; the repository normalizes ordering. */
    suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>>
}

interface MangaSource : CatalogueSource {
    /** Absolute image URLs for every page of the chapter, in reading order. */
    suspend fun fetchPageList(chapterUrl: String): Result<List<String>>
}

interface NovelSource : CatalogueSource {
    /** Plain text of the chapter with paragraphs separated by blank lines. */
    suspend fun fetchChapterText(chapterUrl: String): Result<String>
}

interface AnimeSource : CatalogueSource {
    /** Servers for an episode, direct streams first. */
    suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>>
}
