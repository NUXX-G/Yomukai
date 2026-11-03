package com.nelson.yomukai.data.discovery

import com.nelson.yomukai.data.metadata.GenreTaxonomy
import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.domain.model.MediaItem

/** Which genres hide a title. Pure, so it is unit tested. */
object GenreRules {
    /** True when any of [genres] is one of [excluded], in any language or spelling the taxonomy knows. */
    fun isExcluded(genres: Collection<String>, excluded: Set<String>): Boolean {
        if (excluded.isEmpty()) return false
        val banned = excluded.flatMap(GenreTaxonomy::variants).toSet()
        return genres.any { SmartSearch.normalize(it) in banned }
    }
}

/** The user's genre exclusions as discovery sees them; each platform keeps them its own way. */
interface TitleFilter {
    /** [items] without the ones already known to be excluded for [source]. Does no network work. */
    suspend fun known(source: CatalogueSource, items: List<MediaItem>): List<MediaItem>

    /** Whether a recommendation may be shown, by its genres, tags and type. */
    fun allows(related: RelatedTitle): Boolean

    /** Lets everything through: for code that has no user behind it. */
    object None : TitleFilter {
        override suspend fun known(source: CatalogueSource, items: List<MediaItem>) = items
        override fun allows(related: RelatedTitle) = true
    }
}
