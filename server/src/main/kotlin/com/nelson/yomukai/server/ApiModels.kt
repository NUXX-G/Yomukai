package com.nelson.yomukai.server

import com.nelson.yomukai.data.discovery.Alternative
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.data.metadata.ChapterIssue
import com.nelson.yomukai.data.metadata.TitleMetadata
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.source.MultiSelectFilter
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.domain.model.MediaItem

data class OptionDto(val value: String, val label: String)
data class FilterDto(val key: String, val label: String, val multi: Boolean, val options: List<OptionDto>, val default: String?)

data class SourceDto(
    val id: String, val name: String, val language: String, val types: List<String>,
    /** "manga", "novel" or "anime": which reader opens its chapters. */
    val kind: String,
    val experimental: Boolean, val enabled: Boolean, val filters: List<FilterDto>,
    /** Why the canary hid it, when it is broken; null while it works. */
    val degraded: String? = null
)

data class MediaDto(
    val id: String, val sourceId: String, val title: String, val cover: String, val type: String,
    val status: String, val description: String, val author: String, val genres: List<String>, val altTitles: List<String>
)

data class ChapterDto(
    val id: String, val title: String, val number: Float, val url: String, val date: String,
    /** Reading order, 0 = first chapter. */
    val index: Int,
    /** "Cap. 133" and the chapter's own name, if it has one. */
    val label: String, val name: String?
)

data class IssueDto(val kind: String, val text: String, val afterChapterId: String?)

data class ChaptersDto(
    val chapters: List<ChapterDto>, val issues: List<IssueDto>,
    val abandoned: Boolean, val lastUpdate: Long?
)

data class RelatedDto(
    val title: String, val names: List<String>, val cover: String, val type: String, val genres: List<String>,
    val relation: String?, val format: String, val tags: List<String>, val item: MediaDto?, val anilistId: Int? = null
)

data class MetadataDto(
    val score: Float?, val scoreSource: String, val malScore: Float?, val officialStatus: String?,
    val synopsis: String, val genres: List<String>, val inferredGenres: Boolean,
    val relations: List<RelatedDto>, val siteUrl: String,
    /** AniList's large cover through the proxy; the title page uses it when the source's cover is small. */
    val cover: String?
)

data class AlternativeDto(
    val item: MediaDto, val sourceName: String, val language: String, val confidence: String,
    val chapterCount: Int?, val lastChapter: Float?, val approximate: Boolean
)

data class VideoDto(val server: String, val quality: String, val url: String, val direct: Boolean, val hls: Boolean)

/** [doubt]: this card may be the same work as a neighbouring one, but it could not be confirmed. */
data class SearchGroupDto(val primary: MediaDto, val others: List<MediaDto>, val doubt: Boolean = false)
data class SearchUpdateDto(val groups: List<SearchGroupDto>, val pending: List<String>, val errors: Map<String, String>, val done: Boolean)

data class UserDto(val name: String, val admin: Boolean, val guest: Boolean, val expiresAt: Long?)

fun CatalogueSource.kind(): String = when (this) {
    is AnimeSource -> "anime"
    is NovelSource -> "novel"
    else -> "manga"
}

fun CatalogueSource.toDto(enabled: Boolean, degraded: String? = null) = SourceDto(
    id, name, language, supportedTypes.map { it.name }, kind(), isExperimental, enabled,
    filters.map { f ->
        FilterDto(
            f.key, f.label, f is MultiSelectFilter, f.options.map { OptionDto(it.value, it.label) },
            (f as? SelectFilter)?.default
        )
    },
    degraded
)

/** Converts with covers already pointing at the proxy. */
class Dtos(private val proxy: MediaProxy, private val headersOf: (String) -> Map<String, String>) {
    /** A sharper cover for a source page (AniList's, once the page is joined to a work); null keeps the source's. */
    var betterCover: (sourceId: String, mediaId: String) -> String? = { _, _ -> null }

    fun media(item: MediaItem) = MediaDto(
        item.id, item.sourceId, item.title,
        betterCover(item.sourceId, item.id)?.let { proxy.cover(it, emptyMap()) } ?: proxy.cover(item.coverUrl, headersOf(item.sourceId)), item.type.name,
        item.status, item.description, item.author, item.genres, item.altTitles
    )

    fun related(r: RelatedTitle) = RelatedDto(
        r.title, r.names, proxy.cover(r.coverUrl, r.item?.let { headersOf(it.sourceId) } ?: emptyMap()), r.type.name, r.genres,
        r.relation, r.format, r.tags, r.item?.let(::media), r.anilistId
    )

    fun metadata(m: TitleMetadata) = MetadataDto(
        m.score, m.scoreSource, m.malScore, m.officialStatus?.name, m.synopsis, m.genres, m.inferredGenres,
        m.relations.map(::related), m.siteUrl, m.cover.takeIf { it.startsWith("https://") }?.let { proxy.image(it, emptyMap()) }
    )

    fun alternative(a: Alternative) = AlternativeDto(
        media(a.item), a.sourceName, a.language, a.confidence.name, a.chapterCount, a.lastChapter, a.approximate
    )

    fun issue(issue: ChapterIssue, text: String, afterChapterId: String?) = IssueDto(
        when (issue) {
            is ChapterIssue.Gap -> "gap"
            is ChapterIssue.Duplicate -> "duplicate"
            is ChapterIssue.OutOfOrder -> "order"
        },
        text, afterChapterId
    )
}

/** Small LRU with expiry for answers that many people ask for (latest lists, details, chapters). */

/**
 * Sizes in-memory caches to the heap the JVM was given: a 1 GB container gets smaller caches than a big server, so
 * Yomukai stays inside its memory limit instead of growing until the kernel kills something.
 */
object MemoryBudget {
    /** 1.0 for a 768 MB heap; between 0.25 and 2. */
    val cacheScale: Double = scaleFor(Runtime.getRuntime().maxMemory())
    fun scaleFor(maxHeap: Long): Double = (maxHeap / (768.0 * 1024 * 1024)).coerceIn(0.25, 2.0)
    fun entries(base: Int): Int = (base * cacheScale).toInt().coerceAtLeast(16)
}

class TtlCache<K, V>(maxEntries: Int, private val ttlMs: Long) {
    private val maxEntries = MemoryBudget.entries(maxEntries)

    private class Entry<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>) = size > maxEntries
    }

    @Synchronized
    fun get(key: K, now: Long = System.currentTimeMillis()): V? =
        map[key]?.takeIf { now - it.at < ttlMs }?.value

    /** The value whatever its age, with its age in ms: for answering at once while a fresh one is fetched. */
    @Synchronized
    fun getAged(key: K, now: Long = System.currentTimeMillis()): Pair<V, Long>? = map[key]?.let { it.value to (now - it.at) }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, System.currentTimeMillis())
    }

    @Synchronized
    fun values(): List<V> = map.values.map { it.value }

    /** [compute]'s successful value, cached; failures are not kept, so they are retried next time. */
    suspend fun getOrLoad(key: K, compute: suspend () -> Result<V>): Result<V> {
        get(key)?.let { return Result.success(it) }
        return compute().onSuccess { put(key, it) }
    }
}
