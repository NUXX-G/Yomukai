package com.nelson.yomukai.data.source

import androidx.compose.runtime.Immutable

@Immutable
data class FilterOption(val value: String, val label: String)

/**
 * A filter a source declares for its catalogue. Values chosen by the user travel as
 * [FilterValues]: filter key -> selected option values.
 */
sealed interface SourceFilter {
    val key: String
    val label: String
    val options: List<FilterOption>
}

/** Exactly one option is active; [default] is used until the user picks another. */
@Immutable
data class SelectFilter(
    override val key: String,
    override val label: String,
    override val options: List<FilterOption>,
    val default: String = options.first().value
) : SourceFilter

/** Any number of options may be active (none = no restriction). */
@Immutable
data class MultiSelectFilter(
    override val key: String,
    override val label: String,
    override val options: List<FilterOption>
) : SourceFilter

typealias FilterValues = Map<String, Set<String>>

/** Conventional filter keys shared by sources so the UI can treat them uniformly. */
object FilterKeys {
    const val SORT = "sort"
    const val STATUS = "status"
    const val GENRE = "genre"
    const val TYPE = "type"
    const val LANGUAGE = "lang"
}

object CommonSort {
    const val LATEST = "latest"
    const val POPULAR = "popular"
    const val TITLE = "title"
    const val RATING = "rating"

    val latestPopularTitle = SelectFilter(
        FilterKeys.SORT, "Orden",
        listOf(
            FilterOption(LATEST, "Recientes"),
            FilterOption(POPULAR, "Populares"),
            FilterOption(TITLE, "A-Z")
        )
    )
}

/** First selected value of a single-choice filter, falling back to the filter's default. */
fun FilterValues.single(filter: SelectFilter): String = this[filter.key]?.firstOrNull() ?: filter.default

fun FilterValues.selected(key: String): Set<String> = this[key].orEmpty()
