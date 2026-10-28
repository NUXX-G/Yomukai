package com.nelson.yomukai.ui.reader

import androidx.compose.runtime.Immutable
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.repository.PageData

/** Flat list entry of the continuous image reader (webtoon, paged and comic modes). */
sealed interface ReaderItem {
    val key: String

    /** Separator shown before the first page of a chapter ("Capítulo X"). */
    @Immutable
    data class Header(val chapter: ChapterEntity) : ReaderItem {
        override val key get() = "h:${chapter.chapterId}"
    }

    @Immutable
    data class Page(val chapterId: String, val index: Int, val total: Int, val data: PageData) : ReaderItem {
        override val key get() = "p:$chapterId:$index"
    }
}

object ReaderItems {
    /** Concatenates the loaded chapters; [withHeaders] adds a separator before each one. */
    fun build(segments: List<Segment<List<PageData>>>, withHeaders: Boolean): List<ReaderItem> =
        buildList {
            for (segment in segments) {
                if (withHeaders) add(ReaderItem.Header(segment.chapter))
                segment.content.forEachIndexed { i, page ->
                    add(ReaderItem.Page(segment.chapter.chapterId, i, segment.content.size, page))
                }
            }
        }

    fun indexOf(items: List<ReaderItem>, chapterId: String, page: Int): Int =
        items.indexOfFirst { it is ReaderItem.Page && it.chapterId == chapterId && it.index == page }
            .let { if (it >= 0) it else items.indexOfFirst { item -> item is ReaderItem.Page && item.chapterId == chapterId } }
            .coerceAtLeast(0)

    /** Chapter and page shown at [index]; headers count as page 0 of their chapter. */
    fun locate(items: List<ReaderItem>, index: Int): Pair<String, Int>? =
        when (val item = items.getOrNull(index)) {
            is ReaderItem.Page -> item.chapterId to item.index
            is ReaderItem.Header -> item.chapter.chapterId to 0
            null -> null
        }

    /** True when fewer than [threshold] pages remain after [index] in the whole list. */
    fun nearEnd(items: List<ReaderItem>, index: Int, threshold: Int = 3): Boolean =
        items.isNotEmpty() && index >= items.lastIndex - threshold
}
