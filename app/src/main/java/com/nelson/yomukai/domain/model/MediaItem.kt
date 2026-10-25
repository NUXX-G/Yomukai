package com.nelson.yomukai.domain.model

/**
 * A title exposed by a source. [id] is the source-specific key that the same source
 * accepts in fetchMediaDetails / fetchChapterList (an absolute URL for HTML scrapers,
 * an API id for JSON sources).
 */
data class MediaItem(
    val id: String,
    val title: String,
    val coverUrl: String,
    val type: MediaType,
    val status: String,
    val description: String,
    val sourceId: String,
    val author: String = "",
    val genres: List<String> = emptyList()
)
