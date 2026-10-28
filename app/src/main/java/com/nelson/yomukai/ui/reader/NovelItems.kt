package com.nelson.yomukai.ui.reader

import androidx.compose.runtime.Immutable
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.search.SmartSearch

/** Flat list entry of the continuous novel reader. */
sealed interface NovelItem {
    val key: String

    @Immutable
    data class Title(val chapter: ChapterEntity) : NovelItem {
        override val key get() = "t:${chapter.chapterId}"
    }

    @Immutable
    data class Paragraph(val chapterId: String, val index: Int, val total: Int, val text: String) : NovelItem {
        override val key get() = "p:$chapterId:$index"
    }
}

object NovelItems {
    fun build(segments: List<Segment<List<String>>>): List<NovelItem> = buildList {
        for (segment in segments) {
            add(NovelItem.Title(segment.chapter))
            segment.content.forEachIndexed { i, text -> add(NovelItem.Paragraph(segment.chapter.chapterId, i, segment.content.size, text)) }
        }
    }

    fun indexOf(items: List<NovelItem>, chapterId: String, paragraph: Int): Int =
        items.indexOfFirst { it is NovelItem.Paragraph && it.chapterId == chapterId && it.index == paragraph }
            .let { if (it >= 0) it else items.indexOfFirst { item -> item is NovelItem.Title && item.chapter.chapterId == chapterId } }
            .coerceAtLeast(0)

    /** Chapter and paragraph at [index]; a chapter title counts as paragraph 0. */
    fun locate(items: List<NovelItem>, index: Int): Pair<String, Int>? = when (val item = items.getOrNull(index)) {
        is NovelItem.Paragraph -> item.chapterId to item.index
        is NovelItem.Title -> item.chapter.chapterId to 0
        null -> null
    }

    /** Flat indices of the paragraphs of [chapterId] containing [query] (accent/case-insensitive). */
    fun search(items: List<NovelItem>, chapterId: String?, query: String): List<Int> {
        val q = SmartSearch.normalize(query)
        if (q.length < 2 || chapterId == null) return emptyList()
        return items.mapIndexedNotNull { i, item ->
            if (item is NovelItem.Paragraph && item.chapterId == chapterId && SmartSearch.normalize(item.text).contains(q)) i else null
        }
    }
}

/** Splits [text] into pieces the speech engine accepts, preferring sentence ends. */
fun chunkForSpeech(text: String, maxLength: Int = 3500): List<String> {
    if (text.length <= maxLength) return listOf(text)
    val chunks = mutableListOf<String>()
    var rest = text
    while (rest.length > maxLength) {
        val window = rest.take(maxLength)
        val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "), window.lastIndexOf(", "))
            .let { if (it < maxLength / 2) window.lastIndexOf(' ').takeIf { s -> s > 0 } ?: (maxLength - 1) else it }
        chunks += rest.take(cut + 1).trim()
        rest = rest.drop(cut + 1).trim()
    }
    if (rest.isNotEmpty()) chunks += rest
    return chunks
}
