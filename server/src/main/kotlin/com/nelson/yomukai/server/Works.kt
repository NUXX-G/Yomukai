package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nelson.yomukai.data.discovery.AniListClient
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.data.work.AdaptationPoint
import com.nelson.yomukai.data.work.ChapterMerge
import com.nelson.yomukai.data.work.EpisodeKind
import com.nelson.yomukai.data.work.FillerGuide
import com.nelson.yomukai.data.work.MergePrefs
import com.nelson.yomukai.data.work.ReleaseCadence
import com.nelson.yomukai.data.work.SourceChapters
import com.nelson.yomukai.data.work.WhereNext
import com.nelson.yomukai.data.work.WorkKey
import com.nelson.yomukai.data.work.WorkProgress
import com.nelson.yomukai.data.work.numberKey
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** A work as stored: what AniList (or the sources) say about it. */
data class WorkRow(
    val key: String, val anilistId: Int?, val malId: Int?, val type: String, val title: String, val altTitles: List<String>,
    val cover: String, val banner: String, val synopsis: String, val genres: List<String>, val score: Int?, val status: String,
    val episodes: Int?, val chapters: Int?, val nextEpisode: Int?, val nextAiringAt: Long?, val updatedAt: Long
)

data class WorkSourceRow(val sourceId: String, val mediaId: String, val confidence: String)

/**
 * The work model on the server: which source pages belong to which work, the work's metadata from AniList, the merged
 * chapter list with the user's choices and progress, "where to continue" mappings with their history, the calendar,
 * fillers and skip markers. Resolving a page asks AniList at most once per title (its cache and the database remember).
 */
class Works(
    private val db: Database,
    private val catalog: Catalog,
    private val aniList: AniListClient,
    private val sources: SourceManager,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val log = LoggerFactory.getLogger("Works")
    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type
    private val known = ConcurrentHashMap<String, String>()
    /** Work key → AniList's large cover, for works AniList knows: sources often only give 200-300 px thumbnails. */
    private val covers = ConcurrentHashMap<String, String>()

    /** AniList's cover for a source page already joined to a work, when there is one. */
    fun betterCover(sourceId: String, mediaId: String): String? = known[WorkKey.itemKey(sourceId, mediaId)]?.let(covers::get)

    /** Item key ("source|media") → work key, for what is already resolved (used to group search and lists). */
    fun knownKeys(items: List<MediaItem>): Map<String, String> = items.mapNotNull { i -> known[WorkKey.itemKey(i)]?.let { WorkKey.itemKey(i) to it } }.toMap()

    suspend fun loadKnown() {
        db.tx { query("SELECT source_id, media_id, work_key FROM work_sources") { WorkKey.itemKey(getString(1), getString(2)) to getString(3) } }.forEach { (k, v) -> known[k] = v }
        db.tx { query("SELECT key, cover FROM works WHERE anilist_id IS NOT NULL") { getString(1) to getString(2) } }
            .forEach { (k, c) -> if (isAniListCover(c)) covers[k] = c }
    }

    /** The work of a source page, resolved (and stored) the first time it is asked. */
    suspend fun resolve(sourceId: String, mediaId: String): WorkRow {
        db.tx { queryOne("SELECT work_key FROM work_sources WHERE source_id = ? AND media_id = ?", sourceId, mediaId) { getString(1) } }
            ?.let { key -> get(key)?.let { return it } }
        val item = catalog.media(sourceId, mediaId).getOrThrow()
        val entry = withTimeoutOrNull(8_000) { runCatching { aniList.lookup(listOf(item.title) + item.altTitles, item.type) }.getOrNull() }
        val key = WorkKey.of(item.type, item.title, entry?.id, entry?.malId)
        val now = clock()
        val row = WorkRow(
            key, entry?.id?.takeIf { it > 0 }, entry?.malId, item.type.name, entry?.names?.firstOrNull() ?: item.title,
            ((entry?.names ?: emptyList()) + item.altTitles + item.title).distinct().take(30),
            entry?.cover?.ifBlank { null } ?: item.coverUrl, entry?.banner.orEmpty(), entry?.description?.ifBlank { null } ?: item.description,
            (entry?.genres ?: emptyList()).ifEmpty { item.genres }, entry?.score, entry?.status.orEmpty(),
            entry?.episodes, entry?.chapters, entry?.nextEpisode, entry?.nextAiringAt, now
        )
        save(row)
        link(key, sourceId, mediaId, if (entry != null) "id" else "name")
        return get(key) ?: row
    }

    private fun isAniListCover(url: String) = url.startsWith("https://s4.anilist.co/") || url.startsWith("https://img.anili.st/")

    private suspend fun save(r: WorkRow) = db.tx {
        if (r.anilistId != null && isAniListCover(r.cover)) covers[r.key] = r.cover
        update(
            """INSERT INTO works(key, anilist_id, mal_id, type, title, alt_titles, cover, banner, synopsis, genres, score, status, episodes, chapters,
               next_episode, next_airing_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(key) DO UPDATE SET anilist_id = COALESCE(excluded.anilist_id, anilist_id), mal_id = COALESCE(excluded.mal_id, mal_id),
               title = excluded.title, alt_titles = excluded.alt_titles, cover = excluded.cover, banner = CASE WHEN excluded.banner <> '' THEN excluded.banner ELSE banner END,
               synopsis = excluded.synopsis, genres = excluded.genres, score = COALESCE(excluded.score, score), status = excluded.status,
               episodes = COALESCE(excluded.episodes, episodes), chapters = COALESCE(excluded.chapters, chapters), next_episode = excluded.next_episode,
               next_airing_at = excluded.next_airing_at, updated_at = excluded.updated_at""",
            r.key, r.anilistId, r.malId, r.type, r.title.take(300), gson.toJson(r.altTitles), r.cover.take(1_000), r.banner.take(1_000),
            r.synopsis.take(5_000), gson.toJson(r.genres.take(30)), r.score, r.status.take(30), r.episodes, r.chapters, r.nextEpisode, r.nextAiringAt, r.updatedAt
        )
    }

    /** Joins a source page to a work ("id" = by database id, "name" = by name, "manual" = a user said so). */
    suspend fun link(key: String, sourceId: String, mediaId: String, confidence: String) {
        db.tx {
            update(
                """INSERT INTO work_sources(source_id, media_id, work_key, confidence, added_at) VALUES(?,?,?,?,?)
                   ON CONFLICT(source_id, media_id) DO UPDATE SET work_key = excluded.work_key, confidence = excluded.confidence""",
                sourceId, mediaId.take(2_000), key, confidence, clock()
            )
            update("UPDATE library SET work_key = ? WHERE source_id = ? AND media_id = ?", key, sourceId, mediaId)
            update("UPDATE history SET work_key = ? WHERE source_id = ? AND media_id = ?", key, sourceId, mediaId)
        }
        known[WorkKey.itemKey(sourceId, mediaId)] = key
    }

    suspend fun get(key: String): WorkRow? = db.tx { queryOne("SELECT * FROM works WHERE key = ?", key) { work() } }

    suspend fun sourcesOf(key: String): List<WorkSourceRow> = db.tx {
        query("SELECT source_id, media_id, confidence FROM work_sources WHERE work_key = ? ORDER BY added_at", key) { WorkSourceRow(getString(1), getString(2), getString(3)) }
    }

    private fun java.sql.ResultSet.work() = WorkRow(
        getString("key"), longOrNull("anilist_id")?.toInt(), longOrNull("mal_id")?.toInt(), getString("type"), getString("title"),
        runCatching { gson.fromJson<List<String>>(getString("alt_titles"), listType) }.getOrDefault(emptyList()),
        getString("cover"), getString("banner"), getString("synopsis"),
        runCatching { gson.fromJson<List<String>>(getString("genres"), listType) }.getOrDefault(emptyList()),
        longOrNull("score")?.toInt(), getString("status"), longOrNull("episodes")?.toInt(), longOrNull("chapters")?.toInt(),
        longOrNull("next_episode")?.toInt(), longOrNull("next_airing_at"), getLong("updated_at")
    )

    /**
     * Gives library and history rows without a work their work, a few per call (AniList allows ~30 requests a minute).
     * Run every couple of minutes; nothing is lost while it catches up, the old (source, title) keys keep working.
     */
    suspend fun backfill(max: Int = 6): Int {
        val pending = db.tx {
            query(
                """SELECT DISTINCT source_id, media_id FROM (SELECT source_id, media_id FROM library WHERE work_key IS NULL AND deleted = 0
                   UNION SELECT source_id, media_id FROM history WHERE work_key IS NULL AND deleted = 0) LIMIT ?""", max
            ) { getString(1) to getString(2) }
        }
        var done = 0
        for ((s, m) in pending) {
            if (sources.get(s) == null) continue
            runCatching { resolve(s, m) }.onSuccess { done++ }.onFailure { log.debug("Sin obra para {} {}: {}", s, m, it.message) }
        }
        return done
    }

    // ---------- Chapters of the work ----------

    data class MergedChapterDto(
        val number: Float, val key: String, val title: String, val sourceId: String, val chapterId: String, val url: String,
        val read: Boolean, val options: List<Map<String, String>>
    )

    data class WorkChaptersDto(val chapters: List<MergedChapterDto>, val gaps: List<List<Int>>, val next: String?, val sourcesFailed: List<String>)

    /** Every source's list of the work, merged with the user's choices and read state. Slow sources do not block (15 s). */
    suspend fun chapters(user: User, key: String, languages: List<String>, sourceOrder: List<String>): WorkChaptersDto = coroutineScope {
        val links = sourcesOf(key).filter { sources.get(it.sourceId) != null }
        val failed = mutableListOf<String>()
        val lists = links.map { link ->
            async {
                val r = withTimeoutOrNull(15_000) { catalog.chapterList(link.sourceId, link.mediaId) }
                r?.getOrNull()?.let { SourceChapters(link.sourceId, sources.get(link.sourceId)?.language ?: "", it) } ?: run {
                    synchronized(failed) { failed += link.sourceId }
                    null
                }
            }
        }.awaitAll().filterNotNull()
        val overrides = db.tx { query("SELECT number_key, source_id FROM work_choices WHERE user_id = ? AND work_key = ?", user.id, key) { getString(1) to getString(2) } }.toMap()
        val merged = ChapterMerge.merge(lists, MergePrefs(languages, sourceOrder, overrides))
        val read = db.tx {
            links.flatMap { l ->
                query("SELECT chapter_id FROM progress WHERE user_id = ? AND source_id = ? AND media_id = ? AND read = 1", user.id, l.sourceId, l.mediaId) {
                    l.sourceId to getString(1)
                }
            }
        }.toSet()
        val readKeys = WorkProgress.readNumbers(lists, read)
        WorkChaptersDto(
            merged.chapters.map { c ->
                MergedChapterDto(
                    c.number, c.key, c.title, c.chosen.sourceId, c.chosen.chapter.id, c.chosen.chapter.url, c.key in readKeys,
                    c.options.map { mapOf("sourceId" to it.sourceId, "language" to it.language, "chapterId" to it.chapter.id) }
                )
            },
            merged.gaps.map { listOf(it.first, it.last) },
            WorkProgress.next(merged, readKeys)?.key,
            failed
        )
    }

    suspend fun choose(user: User, key: String, numberKey: String, sourceId: String?) = db.tx {
        if (sourceId == null) update("DELETE FROM work_choices WHERE user_id = ? AND work_key = ? AND number_key = ?", user.id, key, numberKey)
        else update(
            "INSERT INTO work_choices(user_id, work_key, number_key, source_id) VALUES(?,?,?,?) ON CONFLICT(user_id, work_key, number_key) DO UPDATE SET source_id = excluded.source_id",
            user.id, key, numberKey.take(20), sourceId.take(60)
        )
    }

    // ---------- Where to continue ----------

    suspend fun points(key: String): List<AdaptationPoint> = db.tx {
        query("SELECT * FROM adaptation_points WHERE anime_key = ? OR target_key = ?", key, key) {
            AdaptationPoint(getString("anime_key"), getInt("until_episode"), getString("target_key"), getDouble("chapter").takeUnless { wasNull() }?.toFloat(), longOrNull("volume")?.toInt())
        }
    }

    /** A user's correction: kept, and the previous value goes to the history so nothing is lost. */
    suspend fun setPoint(user: User, p: AdaptationPoint) {
        require(p.untilEpisode in 1..10_000) { "Episodio no válido" }
        require(p.chapter.let { it == null || it in 0f..100_000f }) { "Capítulo no válido" }
        require(p.volume.let { it == null || it in 0..1_000 }) { "Volumen no válido" }
        require(p.chapter != null || p.volume != null) { "Indica capítulo o volumen" }
        val now = clock()
        db.tx {
            val old = queryOne("SELECT chapter, volume FROM adaptation_points WHERE anime_key = ? AND until_episode = ? AND target_key = ?", p.animeKey, p.untilEpisode, p.targetKey) {
                (getDouble(1).takeUnless { wasNull() }?.toString() ?: "-") + "/" + (longOrNull("volume")?.toString() ?: "-")
            }
            update(
                """INSERT INTO adaptation_points(anime_key, until_episode, target_key, chapter, volume, updated_by, updated_at) VALUES(?,?,?,?,?,?,?)
                   ON CONFLICT(anime_key, until_episode, target_key) DO UPDATE SET chapter = excluded.chapter, volume = excluded.volume,
                   updated_by = excluded.updated_by, updated_at = excluded.updated_at""",
                p.animeKey, p.untilEpisode, p.targetKey, p.chapter?.toDouble(), p.volume, user.id, now
            )
            update(
                "INSERT INTO adaptation_history(anime_key, until_episode, target_key, before, after, changed_by, changed_at) VALUES(?,?,?,?,?,?,?)",
                p.animeKey, p.untilEpisode, p.targetKey, old ?: "", "${p.chapter ?: "-"}/${p.volume ?: "-"}", user.name, now
            )
        }
    }

    suspend fun history(key: String): List<Map<String, Any?>> = db.tx {
        query("SELECT * FROM adaptation_history WHERE anime_key = ? OR target_key = ? ORDER BY id DESC LIMIT 50", key, key) {
            mapOf("animeKey" to getString("anime_key"), "untilEpisode" to getInt("until_episode"), "targetKey" to getString("target_key"),
                "before" to getString("before"), "after" to getString("after"), "by" to getString("changed_by"), "at" to getLong("changed_at"))
        }
    }

    suspend fun continuation(key: String, episode: Int?) = mapOf(
        "next" to (episode?.let { WhereNext.afterAnime(points(key), key, it) } ?: emptyList()),
        "animatedUpTo" to WhereNext.animatedUpTo(points(key), key),
        "points" to points(key)
    )

    // ---------- Calendar ----------

    data class CalendarEntry(val workKey: String, val title: String, val cover: String, val type: String, val date: String, val label: String, val estimated: Boolean)

    /**
     * What is expected in the next [days] for the user's library: anime from AniList's airing schedule, manga and novels
     * from the rhythm their sources actually publish at.
     */
    suspend fun calendar(user: User, days: Int = 7, today: LocalDate = LocalDate.now()): List<CalendarEntry> {
        val rows = db.tx {
            query("SELECT DISTINCT work_key, source_id, media_id, title, cover, type FROM library WHERE user_id = ? AND deleted = 0", user.id) {
                LibraryRef(getString(1), getString(2), getString(3), getString(4), getString(5), getString(6))
            }
        }
        val until = today.plusDays(days.toLong())
        return rows.take(200).distinctBy { it.workKey ?: WorkKey.itemKey(it.sourceId, it.mediaId) }
            .mapNotNull { r -> if (r.type == MediaType.ANIME.name) nextEpisode(r, today, until) else nextChapter(r, today, until) }
            .sortedBy { it.date }
    }

    private class LibraryRef(val workKey: String?, val sourceId: String, val mediaId: String, val title: String, val cover: String, val type: String)

    /** Anime: AniList's airing schedule. */
    private suspend fun nextEpisode(r: LibraryRef, today: LocalDate, until: LocalDate): CalendarEntry? {
        val work = r.workKey?.let { get(it) } ?: return null
        val at = work.nextAiringAt ?: return null
        val day = java.time.Instant.ofEpochSecond(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (day.isBefore(today) || day.isAfter(until)) return null
        return CalendarEntry(work.key, work.title, work.cover, r.type, day.toString(), "Episodio ${work.nextEpisode ?: "?"}", false)
    }

    /** Manga and novels: the rhythm the source has actually published at, only when it is regular. */
    private suspend fun nextChapter(r: LibraryRef, today: LocalDate, until: LocalDate): CalendarEntry? {
        val list = catalog.cachedChapters(r.sourceId, r.mediaId) ?: return null
        val cadence = ReleaseCadence.estimate(list.mapNotNull { ReleaseCadence.parseDate(it.dateUploaded, today) })?.takeIf { it.regular } ?: return null
        var next = cadence.nextDay
        while (next.isBefore(today)) next = next.plusDays(Math.round(cadence.everyDays).coerceAtLeast(1))
        if (next.isAfter(until)) return null
        val work = r.workKey?.let { get(it) }
        val label = "Nuevo capítulo (cada ${Math.round(cadence.everyDays)} días)"
        return CalendarEntry(r.workKey ?: WorkKey.itemKey(r.sourceId, r.mediaId), work?.title ?: r.title, work?.cover ?: r.cover, r.type, next.toString(), label, true)
    }

    // ---------- Fillers ----------

    /** Episode number → "filler" / "mixed" for an anime, from AnimeFillerList, cached a week (empty when unknown). */
    suspend fun fillers(http: com.nelson.yomukai.data.network.HttpFetcher, title: String): Map<Int, String> {
        val slug = FillerGuide.slug(title).take(100)
        if (slug.isBlank()) return emptyMap()
        val cached = db.tx { queryOne("SELECT data, fetched_at FROM fillers WHERE slug = ?", slug) { getString(1) to getLong(2) } }
        if (cached != null && clock() - cached.second < 7 * Auth.DAY_MS) return parseFillers(cached.first)
        val parsed = runCatching { FillerGuide.parse(http.getString("https://www.animefillerlist.com/shows/$slug", emptyMap(), cloudflareFallback = false)) }.getOrDefault(emptyMap())
        val data = parsed.filterValues { it != EpisodeKind.CANON }.mapKeys { it.key.toString() }.mapValues { it.value.name.lowercase() }
        db.tx {
            update(
                "INSERT INTO fillers(slug, data, fetched_at) VALUES(?,?,?) ON CONFLICT(slug) DO UPDATE SET data = excluded.data, fetched_at = excluded.fetched_at",
                slug, gson.toJson(data), clock()
            )
        }
        return data.mapKeys { it.key.toInt() }
    }

    private fun parseFillers(json: String): Map<Int, String> = runCatching {
        JsonParser.parseString(json).asJsonObject.entrySet().associate { it.key.toInt() to it.value.asString }
    }.getOrDefault(emptyMap())

    fun dto(w: WorkRow, links: List<WorkSourceRow>, image: (String) -> String = { it }): JsonObject = JsonParser.parseString(
        gson.toJson(w.copy(cover = w.cover.takeIf { it.startsWith("http") }?.let(image) ?: w.cover, banner = w.banner.takeIf { it.startsWith("http") }?.let(image).orEmpty()))
    ).asJsonObject.apply {
        val list = links.map {
            mapOf("sourceId" to it.sourceId, "mediaId" to it.mediaId, "confidence" to it.confidence, "name" to (sources.get(it.sourceId)?.name ?: it.sourceId))
        }
        add("sources", JsonParser.parseString(gson.toJson(list)))
    }

    companion object {
        fun numberOf(text: String): String? = text.toFloatOrNull()?.let(::numberKey)
    }
}
