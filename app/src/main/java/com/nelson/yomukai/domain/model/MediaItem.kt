package com.nelson.yomukai.domain.model

import androidx.compose.runtime.Immutable

/**
 * A title exposed by a source. [id] is the source-specific key that the same source
 * accepts in fetchMediaDetails / fetchChapterList (an absolute URL for HTML scrapers,
 * an API id for JSON sources).
 */
@Immutable
data class MediaItem(
    val id: String,
    val title: String,
    val coverUrl: String,
    val type: MediaType,
    val status: String,
    val description: String,
    val sourceId: String,
    val author: String = "",
    val genres: List<String> = emptyList(),
    /** Alternative titles when the source provides them (used by smart search). */
    val altTitles: List<String> = emptyList()
)
