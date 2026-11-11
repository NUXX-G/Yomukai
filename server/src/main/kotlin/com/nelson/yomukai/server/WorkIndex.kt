package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nelson.yomukai.data.discovery.TitleMatcher
import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.work.WorkKey
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * The works index: every title of every source, gathered into works and kept by language, built ahead by the server so
 * that lists, search and opening a work are database reads, never a live search across sources.
 *
 * - Crawling: the first time each source's whole listing, then every [LATEST_EVERY_MS] its latest pages (new chapters
 *   and new titles). Gentle: a pause between pages, a few pages per [tick], one source at a time.
 * - Grouping: a title joins a work at once by any of its names (and format family: a manhwa and its novel stay apart);
 *   later, in the background, AniList confirms it and the work becomes "al:ID", merging names that were apart.
 * - Languages: a work shows in a language when one of its sources is in it; the sources of that language make its list.
 */
class WorkIndex(private val s: Services, private val clock: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger("WorkIndex")
    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type

    data class Card(
        val key: String, val title: String, val cover: String, val type: String, val langs: List<String>,
        val sources: Int, val updatedAt: Long, val status: String, val coverSource: String = "",
        /** What its anime sources are heard in (AudioTrack names), F3.1. */
        val audios: List<String> = emptyList()
    )

    data class SourceState(val sourceId: String, val items: Int, val fullDone: Boolean, val nextPage: Int, val latestAt: Long, val error: String?)

    // ---------- Store ----------------------------------------------------------------------------------------------

    /** Keeps [items] of a source (in [lang]); [fresh]: they come from its latest updates (new chapters). New ones count. */
    suspend fun record(sourceId: String, lang: String, items: List<MediaItem>, fresh: Boolean): Int = s.db.tx {
        CoverHosts.learn(items)
        val now = clock()
        var added = 0
        for (it in items) {
            if (it.id.isBlank() || it.title.isBlank()) continue
            val known = queryOne("SELECT 1 FROM index_items WHERE source_id = ? AND media_id = ?", sourceId, it.id) { 1 } != null
            if (!known) added++
            update(
                """INSERT INTO index_items(source_id, media_id, lang, type, title, alt_titles, cover, status, genres, seen_at, updated_at, adult, audio)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(source_id, media_id) DO UPDATE SET title = excluded.title, alt_titles = excluded.alt_titles,
                     cover = CASE WHEN excluded.cover <> '' THEN excluded.cover ELSE cover END, status = excluded.status,
                     genres = excluded.genres, seen_at = excluded.seen_at, updated_at = CASE WHEN ? THEN excluded.updated_at ELSE updated_at END,
                     adult = MAX(adult, excluded.adult), audio = excluded.audio""",
                sourceId, it.id.take(MAX_ID), lang, it.type.name, it.title.take(MAX_TITLE), gson.toJson(it.altTitles.take(MAX_NAMES)),
                it.coverUrl.take(MAX_URL), it.status.take(MAX_SHORT), gson.toJson(it.genres.take(MAX_NAMES)), now, now,
                // A title that says it is sexual content is marked so, whatever its genres (and stays marked).
                if (com.nelson.yomukai.data.metadata.AdultHints.isExplicit(it.title)) 1 else 0,
                com.nelson.yomukai.domain.model.AudioTrack.of(it, s.sources.get(sourceId)?.language ?: lang).name, fresh
            )
        }
        added
    }

    /** Titles not in a work yet join one by name: an existing work with any of their names, else a new one. */
    suspend fun groupPending(limit: Int = GROUP_BATCH): Int {
        val pending = s.db.tx {
            query("SELECT source_id, media_id, lang, type, title, alt_titles, cover FROM index_items WHERE work_key IS NULL LIMIT ?", limit) {
                Pending(getString(1), getString(2), getString(3), getString(4), getString(5), getString(6), getString(7))
            }
        }
        for (p in pending) {
            val type = runCatching { MediaType.valueOf(p.type) }.getOrDefault(MediaType.MANGA)
            // What a site says are other names is only a hint (scraped data can lie): they may lead a title to a work
            // that already exists, but only its own title claims a name. AniList confirms afterwards.
            val primary = WorkKey.of(type, p.title)
            val alternatives = runCatching { gson.fromJson<List<String>>(p.altTitles, listType) }.getOrDefault(emptyList())
                .filter { it.isNotBlank() && it.length <= MAX_ALT_LENGTH }.take(MAX_ALT_NAMES).map { WorkKey.of(type, it) }
            s.db.tx {
                val existing = (listOf(primary) + alternatives).distinct()
                    .firstNotNullOfOrNull { k -> queryOne("SELECT work_key FROM index_names WHERE name_key = ?", k) { getString(1) } }
                val key = existing ?: primary
                update("INSERT OR IGNORE INTO index_names(name_key, work_key) VALUES(?,?)", primary, key)
                update("UPDATE index_items SET work_key = ? WHERE source_id = ? AND media_id = ?", key, p.sourceId, p.mediaId)
                update(
                    "INSERT OR IGNORE INTO works(key, type, title, cover, updated_at) VALUES(?,?,?,?,?)",
                    key, type.name, p.title.take(MAX_TITLE), p.cover.take(MAX_URL), clock()
                )
                update(
                    """INSERT INTO work_sources(source_id, media_id, work_key, confidence, added_at) VALUES(?,?,?,?,?)
                       ON CONFLICT(source_id, media_id) DO UPDATE SET work_key = excluded.work_key WHERE work_sources.confidence <> 'manual'""",
                    p.sourceId, p.mediaId, key, "name", clock()
                )
            }
            refreshSearch(s.db.tx { queryOne("SELECT work_key FROM index_items WHERE source_id = ? AND media_id = ?", p.sourceId, p.mediaId) { getString(1) } } ?: continue)
        }
        return pending.size
    }

    private data class Pending(val sourceId: String, val mediaId: String, val lang: String, val type: String, val title: String, val altTitles: String, val cover: String)

    /**
     * One work known only by name is looked up in AniList: found, it becomes "al:ID" (with AniList's cover, banner and
     * synopsis), and any other work with the same id is merged into it. Returns false when there was none to check.
     */
    suspend fun confirmNext(): Boolean {
        val row = s.db.tx {
            queryOne(
                """SELECT w.key, w.type FROM works w WHERE w.key LIKE 't:%' AND w.anilist_checked_at IS NULL
                   AND EXISTS(SELECT 1 FROM index_items i WHERE i.work_key = w.key) ORDER BY w.updated_at DESC LIMIT 1"""
            ) { getString(1) to getString(2) }
        } ?: return false
        val (key, typeName) = row
        val type = runCatching { MediaType.valueOf(typeName) }.getOrDefault(MediaType.MANGA)
        s.db.tx { update("UPDATE works SET anilist_checked_at = ? WHERE key = ?", clock(), key) }
        val names = s.db.tx {
            query("SELECT title, alt_titles FROM index_items WHERE work_key = ? LIMIT 6", key) { getString(1) to getString(2) }
        }.flatMap { (t, alts) -> listOf(t) + runCatching { gson.fromJson<List<String>>(alts, listType) }.getOrDefault(emptyList()) }
            .filter { it.isNotBlank() }.distinct().take(MAX_LOOKUP_NAMES)
        val entry = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { runCatching { s.aniList.lookup(names, type) }.getOrNull() }
        if (entry == null || entry.id <= 0) {
            // AniList does not know it: Kitsu's large poster and wide banner instead of the site's thumbnail.
            kitsuImages(names, type)?.let { img ->
                s.db.tx {
                    update(
                        "UPDATE works SET cover = CASE WHEN ? <> '' THEN ? ELSE cover END, banner = CASE WHEN banner = '' THEN ? ELSE banner END WHERE key = ?",
                        img.poster, img.poster, img.banner, key
                    )
                }
            }
            return true
        }
        confirmAs(key, type, names, entry)
        return true
    }

    /** A title AniList shows (Home rows, calendar, explorer) that the index has not confirmed yet: confirmed first. */
    private data class Wanted(val anilistId: Int, val type: MediaType, val names: List<String>)

    private val wanted = java.util.concurrent.ConcurrentLinkedDeque<Wanted>()
    private val wantedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** O1: queue an AniList title shown somewhere, so its provisional work becomes "al:ID" in minutes, not days. */
    fun want(anilistId: Int, type: MediaType, names: List<String>) {
        if (anilistId <= 0 || wanted.size >= MAX_WANTED || !wantedIds.add(anilistId)) return
        wanted.add(Wanted(anilistId, type, names.filter { it.isNotBlank() }.distinct().take(MAX_LOOKUP_NAMES * 2)))
    }

    /**
     * One wanted title: if a provisional work has one of its names, it is confirmed with that AniList id (one AniList
     * request, by id: no guessing by name). Returns false when there was none waiting.
     */
    suspend fun adoptNext(): Boolean {
        val w = wanted.pollFirst() ?: return false
        if (byAniList(w.anilistId) != null) return true
        val key = match(w.type, w.names, null, "es")?.takeIf { it.startsWith("t:") } ?: return true
        val entry = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { runCatching { s.aniList.byId(w.anilistId, w.type) }.getOrNull() } ?: return true
        s.db.tx { update("UPDATE works SET anilist_checked_at = ? WHERE key = ?", clock(), key) }
        confirmAs(key, w.type, w.names, entry)
        return true
    }

    /** Makes the provisional work [key] the AniList work [entry] ("al:ID"), merging it with any work already there. */
    private suspend fun confirmAs(key: String, type: MediaType, names: List<String>, entry: com.nelson.yomukai.data.discovery.AniListEntry) {
        val newKey = WorkKey.of(type, entry.names.firstOrNull() ?: names.first(), entry.id)
        // The kind comes from AniList's metadata, the site only guessed it: a Korean work a manga site lists is a
        // manhwa (F1.7). Never across families: a novel lookup stays a novel, an anime an anime.
        val kind = entry.mediaType?.takeIf { TitleMatcher.sameFamily(it, type) } ?: type
        s.db.tx {
            update(
                """INSERT INTO works(key, anilist_id, mal_id, type, title, alt_titles, cover, banner, synopsis, genres, score, status, episodes, chapters, updated_at, anilist_checked_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(key) DO UPDATE SET type = excluded.type, kind_checked = 1, cover = CASE WHEN excluded.cover <> '' THEN excluded.cover ELSE cover END,
                     banner = CASE WHEN excluded.banner <> '' THEN excluded.banner ELSE banner END, anilist_checked_at = excluded.anilist_checked_at""",
                newKey, entry.id, entry.malId, kind.name, (entry.names.firstOrNull() ?: names.first()).take(MAX_TITLE), gson.toJson(entry.names.take(MAX_NAMES)),
                entry.cover, entry.banner, entry.description.take(MAX_SYNOPSIS), gson.toJson(entry.genres), entry.score, entry.status, entry.episodes, entry.chapters,
                clock(), clock()
            )
            update("UPDATE index_items SET work_key = ? WHERE work_key = ?", newKey, key)
            update("UPDATE index_names SET work_key = ? WHERE work_key = ?", newKey, key)
            update("UPDATE work_sources SET work_key = ? WHERE work_key = ? AND confidence <> 'manual'", newKey, key)
            update("UPDATE library SET work_key = ? WHERE work_key = ?", newKey, key)
            update("UPDATE history SET work_key = ? WHERE work_key = ?", newKey, key)
            update("DELETE FROM works WHERE key = ?", key)
            update("DELETE FROM works_fts WHERE work_key = ?", key)
        }
        refreshSearch(newKey)
        // AniList has no wide banner for many works: Kitsu's.
        if (entry.banner.isBlank()) kitsuImages(names, type)?.banner?.takeIf { it.isNotBlank() }?.let { banner ->
            s.db.tx { update("UPDATE works SET banner = ? WHERE key = ? AND banner = ''", banner, newKey) }
        }
    }

    private suspend fun kitsuImages(names: List<String>, type: MediaType) =
        withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { runCatching { s.kitsu.images(names, type) }.getOrNull() }

    /** The search rows of [key]: one per language, with every name its sources and AniList give it. */
    private suspend fun refreshSearch(key: String) = s.db.tx {
        val byLang = query("SELECT lang, title, alt_titles FROM index_items WHERE work_key = ?", key) { Triple(getString(1), getString(2), getString(3)) }
            .groupBy({ it.first }, { listOf(it.second) + runCatching { gson.fromJson<List<String>>(it.third, listType) }.getOrDefault(emptyList()) })
        val workNames = queryOne("SELECT title, alt_titles FROM works WHERE key = ?", key) { getString(1) to getString(2) }
            ?.let { (t, a) -> listOf(t) + runCatching { gson.fromJson<List<String>>(a, listType) }.getOrDefault(emptyList()) }.orEmpty()
        update("DELETE FROM works_fts WHERE work_key = ?", key)
        byLang.forEach { (lang, lists) ->
            val text = (lists.flatten() + workNames).filter { it.isNotBlank() }.distinct().joinToString(" | ").take(MAX_SEARCH_TEXT)
            update("INSERT INTO works_fts(work_key, lang, names) VALUES(?,?,?)", key, lang, text)
        }
    }

    // ---------- Crawling -------------------------------------------------------------------------------------------

    /** One page of [source]: its whole listing while the first crawl lasts, else its latest updates. */
    suspend fun crawlPage(source: CatalogueSource, page: Int, full: Boolean): List<MediaItem> {
        val items = (if (full) source.browse(page) else source.fetchLatestUpdates(page)).getOrThrow()
        record(source.id, langOf(source), items, fresh = !full)
        return items
    }

    private fun langOf(source: CatalogueSource) = source.language.lowercase().take(5)

    /** Called every minute: a few pages of crawling, grouping what came in and some AniList confirmations. */
    suspend fun tick() {
        if (!s.features.isOn("work_index")) return
        // Several sources at once (each one is a different site, so none gets more than its own gentle pace).
        coroutineScope {
            dueSources().take(PARALLEL_SOURCES).map { source ->
                async {
                    runCatching { crawlSome(source) }.onSuccess { failures.remove(source.id) }.onFailure {
                        if (it is CancellationException) throw it
                        s.db.tx { update("UPDATE index_state SET error = ? WHERE source_id = ?", it.message?.take(MAX_SHORT), source.id) }
                        // A broken site waits (longer each time) and leaves its turn to the others.
                        val n = (failures[source.id]?.first ?: 0) + 1
                        failures[source.id] = n to clock() + (RETRY_BASE_MS shl (n - 1).coerceAtMost(RETRY_MAX_SHIFT))
                        log.debug("Índice {}: {}", source.id, it.message)
                    }
                }
            }.awaitAll()
        }
        groupPending()
        retypeSome()
        var confirmed = 0
        // What people are shown first (Home rows, calendar, explorer), then the rest by name.
        while (confirmed < CONFIRMS_PER_TICK && !s.aniList.resting && adoptNext()) {
            confirmed++
            delay(ANILIST_PAUSE_MS)
        }
        while (confirmed < CONFIRMS_PER_TICK && !s.aniList.resting && confirmNext()) {
            confirmed++
            delay(ANILIST_PAUSE_MS)
        }
    }

    /**
     * O1: lists and search only show identified works ([IDENTIFIED]). Off only in tests of grouping and search, which
     * work with names alone.
     */
    @Volatile var onlyIdentified = true

    private fun identifiedSql(): String = if (onlyIdentified) IDENTIFIED else "1 = 1"

    /**
     * Sources the canary has confirmed down (not a passing failure): a work only there is left out of every list and
     * search (O4), so nobody opens a work that cannot play. The library still shows it, with a notice.
     */
    private fun downSql(): String =
        s.sources.degraded.keys.filter { id -> id.all { it.isLetterOrDigit() || it == '_' || it == '-' } }.joinToString(",") { "'$it'" }.ifEmpty { "''" }

    /** Sources down now (the canary's, or failing the crawl): their covers are the last choice for a card. */
    private fun unhealthySql(): String {
        val now = clock()
        val ids = s.sources.degraded.keys + failures.filterValues { it.second > now }.keys
        return ids.filter { id -> id.all { it.isLetterOrDigit() || it == '_' || it == '-' } }.joinToString(",") { "'$it'" }.ifEmpty { "''" }
    }

    /** Sources that failed: how many times in a row, and when to try again. */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * Sources with work to do: first crawls unfinished first, then those whose latest updates are due (oldest first).
     * A source that keeps failing waits its turn (10 min, 20, 40… up to ~5 h), so it never blocks the healthy ones.
     */
    internal suspend fun dueSources(): List<CatalogueSource> {
        val now = clock()
        val candidates = s.sources.all.filter {
            it.id !in s.sources.hidden && !s.sources.degraded.containsKey(it.id) && !it.isExperimental && it.language != "MULTI" &&
                (failures[it.id]?.second ?: 0L) <= now
        }
        val states = s.db.tx { query("SELECT source_id, full_done, latest_at FROM index_state") { getString(1) to (getInt(2) to getLong(3)) } }.toMap()
        // The least indexed first: every source (anime too) starts soon, and the big catalogues go on afterwards.
        val counts = s.db.tx { query("SELECT source_id, items FROM index_state") { getString(1) to getInt(2) } }.toMap()
        val unfinished = candidates.filter { states[it.id]?.first != 1 }.sortedBy { counts[it.id] ?: 0 }
        val due = candidates.filter { states[it.id]?.first == 1 && clock() - (states[it.id]?.second ?: 0L) >= LATEST_EVERY_MS }
            .sortedBy { states[it.id]?.second ?: 0L }
        return unfinished + due
    }

    /** The source's lists of sexual content, up to [pages] pages each: their titles are recorded and marked adult. */
    internal suspend fun crawlAdult(source: CatalogueSource, pages: Int) {
        val lang = langOf(source)
        for (list in 0 until source.adultLists) {
            val seen = HashSet<String>()
            for (page in 1..pages) {
                val items = source.adultTitles(list, page).getOrNull().orEmpty()
                if (items.isEmpty() || items.all { !seen.add(it.id) }) break
                record(source.id, lang, items, fresh = false)
                s.db.tx {
                    items.forEach { update("UPDATE index_items SET adult = 1 WHERE source_id = ? AND media_id = ?", source.id, it.id.take(MAX_ID)) }
                }
                delay(PAGE_PAUSE_MS)
            }
        }
    }

    private suspend fun crawlSome(source: CatalogueSource) {
        s.db.tx { update("INSERT OR IGNORE INTO index_state(source_id) VALUES(?)", source.id) }
        val (nextPage, fullDone) = s.db.tx { queryOne("SELECT next_page, full_done FROM index_state WHERE source_id = ?", source.id) { getInt(1) to (getInt(2) == 1) } }!!
        if (!fullDone) {
            var page = nextPage
            var finished = false
            val seen = HashSet<String>()
            var done = 0
            while (done < PAGES_PER_TICK && !finished) {
                val items = crawlPage(source, page, full = true)
                // The end: an empty page, a page that repeats (some sites loop) or the cap.
                if (items.isEmpty() || items.all { !seen.add(it.id) } || page >= MAX_FULL_PAGES) {
                    finished = true
                } else {
                    page++
                    done++
                    delay(PAGE_PAUSE_MS)
                }
            }
            // Once the whole catalogue is in, the site's own lists of sexual content mark what it did not tag.
            if (finished) crawlAdult(source, MAX_ADULT_PAGES)
            s.db.tx {
                update(
                    "UPDATE index_state SET next_page = ?, full_done = ?, latest_at = CASE WHEN ? THEN ? ELSE latest_at END, error = NULL, " +
                        "items = (SELECT COUNT(*) FROM index_items WHERE source_id = ?) WHERE source_id = ?",
                    page, if (finished) 1 else 0, finished, clock(), source.id, source.id
                )
            }
            return
        }
        // New sexual content (the first pages of the site's adult lists), then the latest updates.
        crawlAdult(source, LATEST_ADULT_PAGES)
        // Latest updates: pages until one brings nothing new to this index (everything already seen as fresh).
        for (page in 1..LATEST_PAGES) {
            val before = s.db.tx { queryOne("SELECT COUNT(*) FROM index_items WHERE source_id = ?", source.id) { getInt(1) } } ?: 0
            val items = crawlPage(source, page, full = false)
            val after = s.db.tx { queryOne("SELECT COUNT(*) FROM index_items WHERE source_id = ?", source.id) { getInt(1) } } ?: 0
            if (items.isEmpty() || (after == before && page > 1)) break
            delay(PAGE_PAUSE_MS)
        }
        s.db.tx {
            update(
                "UPDATE index_state SET latest_at = ?, error = NULL, items = (SELECT COUNT(*) FROM index_items WHERE source_id = ?) WHERE source_id = ?",
                clock(), source.id, source.id
            )
        }
    }

    suspend fun states(): List<SourceState> = s.db.tx {
        query("SELECT source_id, items, full_done, next_page, latest_at, error FROM index_state ORDER BY source_id") {
            SourceState(getString(1), getInt(2), getInt(3) == 1, getInt(4), getLong(5), getString(6))
        }
    }

    /** Admin: crawl everything again from the first page (keeps what is known). */
    suspend fun restart() = s.db.tx { update("UPDATE index_state SET next_page = 1, full_done = 0") }

    // ---------- Reading --------------------------------------------------------------------------------------------

    /** Works with a source in [lang] (multi-language sources count for every language), of [types] when given. */
    suspend fun list(
        lang: String, types: Set<String>?, sort: String, page: Int, hideAdult: Boolean = true, audio: String? = null,
        /** An AniList genre name (letters only); the work must carry it. */
        genre: String? = null
    ): List<Card> = s.db.tx {
        val genreFilter = genre?.filter { it.isLetter() || it == ' ' }?.takeIf { it.isNotBlank() }
        // Only a known audio name ever reaches the query (F3.1: castellano, latino, inglés, subtitulado sections).
        val audioFilter = audio?.takeIf { a -> com.nelson.yomukai.domain.model.AudioTrack.entries.any { it.name == a } }
        val typeFilter = types?.takeIf { it.isNotEmpty() }?.joinToString(",") { "'" + it.filter(Char::isLetter) + "'" }
        val order = when (sort) {
            "az" -> "COALESCE(local_title, w.title) COLLATE NOCASE ASC"
            "popular" -> "sources DESC, w.score DESC NULLS LAST, updated DESC"
            else -> "updated DESC"
        }
        query(
            """SELECT w.key, w.title, w.cover, w.type, w.score, w.status, MAX(i.updated_at) AS updated, COUNT(DISTINCT i.source_id) AS sources,
                 (SELECT i2.title FROM index_items i2 WHERE i2.work_key = w.key AND i2.lang = ? LIMIT 1) AS local_title,
                 (SELECT i3.source_id || char(31) || i3.cover FROM index_items i3 WHERE i3.work_key = w.key AND i3.cover <> '' ORDER BY (i3.source_id IN (${unhealthySql()})) ASC, i3.updated_at DESC LIMIT 1) AS item_cover,
                 (SELECT GROUP_CONCAT(DISTINCT i4.lang) FROM index_items i4 WHERE i4.work_key = w.key) AS langs,
                 (SELECT GROUP_CONCAT(DISTINCT i5.audio) FROM index_items i5 WHERE i5.work_key = w.key) AS audios
               FROM works w JOIN index_items i ON i.work_key = w.key AND i.lang = ? AND i.source_id NOT IN (${downSql()})
               WHERE ${identifiedSql()} ${if (typeFilter != null) "AND w.type IN ($typeFilter)" else ""} ${if (hideAdult) "AND $notAdult" else ""}
                 ${if (audioFilter != null) "AND i.audio = '$audioFilter'" else ""} ${if (genreFilter != null) "AND w.genres LIKE ?" else ""}
               GROUP BY w.key ORDER BY $order LIMIT ? OFFSET ?""",
            *listOfNotNull(lang, lang, genreFilter?.let { "%\"$it\"%" }, PAGE_SIZE, (page - 1).coerceAtLeast(0) * PAGE_SIZE).toTypedArray()
        ) { card() }
    }

    private fun java.sql.ResultSet.card() = Card(
        key = getString("key"),
        title = getString("local_title")?.takeIf { it.isNotBlank() } ?: getString("title"),
        cover = getString("cover")?.takeIf { it.isNotBlank() } ?: getString("item_cover").orEmpty().substringAfter('\u001F'),
        type = getString("type"), langs = getString("langs").orEmpty().split(',').filter { it.isNotBlank() },
        sources = getInt("sources"), updatedAt = getLong("updated"), status = getString("status").orEmpty(),
        coverSource = if (getString("cover").isNullOrBlank()) getString("item_cover").orEmpty().substringBefore('\u001F', "") else "",
        audios = getString("audios").orEmpty().split(',').filter { it.isNotBlank() }.distinct()
    )

    /** Works whose names match [query] (any word start, accents ignored), those with a source in [lang] first. */
    suspend fun search(query: String, lang: String, types: Set<String>?, hideAdult: Boolean = true): List<Card> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.take(MAX_TERMS)
        if (terms.isEmpty()) return emptyList()
        // Every word, as the start of a word of some name; with few answers, also any name with a word that starts
        // like one of them (a typo later in the word: «freiren» still finds «Frieren»). The score decides afterwards.
        val strict = terms.joinToString(" ") { "\"" + it.replace("\"", "") + "\"*" }
        val names = LinkedHashMap<String, String>()
        suspend fun candidates(match: String, limit: Int) = s.db.tx {
            query("SELECT work_key, names FROM works_fts WHERE works_fts MATCH ? ORDER BY (lang = ?) DESC, rank LIMIT ?", match, lang, limit) {
                getString(1) to getString(2)
            }
        }.forEach { (k, n) -> names[k] = (names[k]?.let { "$it | " }.orEmpty() + n) }
        candidates(strict, SEARCH_CANDIDATES)
        if (names.size < FUZZY_BELOW) {
            val loose = terms.filter { it.length > FUZZY_PREFIX + 1 }.joinToString(" OR ") { "\"" + it.take(FUZZY_PREFIX).replace("\"", "") + "\"*" }
            if (loose.isNotBlank()) candidates(loose, FUZZY_CANDIDATES)
        }
        // How well the query names each work: its best name (any source, any language, AniList's), 1.0 = exactly.
        val score = names.mapValues { (_, text) -> text.split(" | ").maxOfOrNull { nameScore(query, it) } ?: 0.0 }
        val keys = score.filterValues { it >= MIN_SEARCH_SCORE }.keys.toList()
        if (keys.isEmpty()) return emptyList()
        val cards = s.db.tx {
            query(
                """SELECT w.key, w.title, w.cover, w.type, w.score, w.status, MAX(i.updated_at) AS updated, COUNT(DISTINCT i.source_id) AS sources,
                     (SELECT i2.title FROM index_items i2 WHERE i2.work_key = w.key AND i2.lang = ? LIMIT 1) AS local_title,
                     (SELECT i3.source_id || char(31) || i3.cover FROM index_items i3 WHERE i3.work_key = w.key AND i3.cover <> '' ORDER BY (i3.source_id IN (${unhealthySql()})) ASC, i3.updated_at DESC LIMIT 1) AS item_cover,
                     (SELECT GROUP_CONCAT(DISTINCT i4.lang) FROM index_items i4 WHERE i4.work_key = w.key) AS langs,
                     (SELECT GROUP_CONCAT(DISTINCT i5.audio) FROM index_items i5 WHERE i5.work_key = w.key) AS audios
                   FROM works w JOIN index_items i ON i.work_key = w.key AND i.source_id NOT IN (${downSql()})
                   WHERE w.key IN (${keys.joinToString(",") { "?" }}) AND ${identifiedSql()} ${if (hideAdult) "AND $notAdult" else ""} GROUP BY w.key""",
                lang, *keys.toTypedArray()
            ) { card() }
        }.associateBy { it.key }
        // The kind first (before the limit, so «Anime» never comes back empty), then how well the name matches (exact
        // title or alternative name first: «One Piece» before «One Piece Academy»), then the work in more sources.
        val wanted = squash(query)
        return keys.mapNotNull { cards[it] }
            .filter { c -> types.isNullOrEmpty() || c.type in types }
            .sortedWith(
                compareBy<Card>(
                    { c -> if (squash(c.title) == wanted || (score[c.key] ?: 0.0) >= 1.0) 0 else 1 },
                    { c -> -(score[c.key] ?: 0.0) },
                    { -it.sources }
                )
            )
            .take(SEARCH_LIMIT)
    }

    private fun squash(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    /** How well [query] names [name], 0..1; written without spaces («onepiece») counts too. */
    private fun nameScore(query: String, name: String): Double {
        val q = squash(query)
        val n = squash(name)
        return when {
            n == q -> 1.0
            q.length >= MIN_SQUASHED && n.startsWith(q) -> SQUASHED_PREFIX_SCORE
            else -> SmartSearch.scoreTitle(query, name)
        }
    }

    /**
     * SQL: the work [w] has no sexual genre (adult, hentai, smut, ecchi… in any spelling the taxonomy knows) in any of
     * its sources nor in AniList's genres. Built from our own taxonomy, never from user input.
     */
    private val notAdult: String by lazy {
        val likes = UserPrefs.ADULT_GENRES.flatMap { com.nelson.yomukai.data.metadata.GenreTaxonomy.variants(it) }
            .filter { v -> v.none { it == '\'' || it == '%' || it == '_' || it == '"' } }.distinct()
            .joinToString(" OR ") { "{col} LIKE '%\"$it\"%'" }
        "NOT EXISTS(SELECT 1 FROM index_items a WHERE a.work_key = w.key AND (a.adult = 1 OR ${likes.replace("{col}", "a.genres")})) " +
            "AND NOT (${likes.replace("{col}", "COALESCE(w.genres, '')")})"
    }

    /** The AniList id of the work a source's page belongs to, when the index has confirmed it. */
    suspend fun aniListOf(sourceId: String, mediaId: String): Int? = s.db.tx {
        queryOne("SELECT work_key FROM index_items WHERE source_id = ? AND media_id = ?", sourceId, mediaId) { getString(1) }
    }?.takeIf { it.startsWith("al:") }?.removePrefix("al:")?.toIntOrNull()

    /** The work behind an AniList id, if the index has it. */
    suspend fun byAniList(id: Int): String? = s.db.tx {
        // Only a work some source has (works known only from metadata or a library have nowhere to open).
        queryOne("SELECT w.key FROM works w WHERE w.anilist_id = ? AND EXISTS(SELECT 1 FROM index_items i WHERE i.work_key = w.key) LIMIT 1", id) { getString(1) }
    }

    /** Of [ids], those a working source has, as AniList id → work key (one query, ids are plain numbers). */
    suspend fun present(ids: List<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        return s.db.tx {
            query(
                """SELECT w.anilist_id, w.key FROM works w WHERE w.anilist_id IN (${ids.joinToString(",") { "?" }})
                   AND EXISTS(SELECT 1 FROM index_items i WHERE i.work_key = w.key AND i.source_id NOT IN (${downSql()}))""",
                *ids.toTypedArray()
            ) { getInt(1) to getString(2) }
        }.toMap()
    }

    /**
     * The work of the index that is [names] (by AniList id first, else by any name, with the same normalised keys used
     * to group titles, so "Ao Ashi Season 2" never opens "Ao Ashi"), preferring one readable in [lang].
     */
    suspend fun match(type: MediaType, names: List<String>, anilistId: Int?, lang: String): String? {
        anilistId?.let { byAniList(it) }?.let { return it }
        val keys = names.filter { it.isNotBlank() && it.length <= MAX_ALT_LENGTH }.take(MAX_LOOKUP_NAMES * 2).map { WorkKey.of(type, it) }.distinct()
        val found = s.db.tx { keys.mapNotNull { k -> queryOne("SELECT work_key FROM index_names WHERE name_key = ?", k) { getString(1) } } }.distinct()
        return found.firstOrNull { sourcesOf(it, lang).isNotEmpty() } ?: found.firstOrNull()
    }

    /** A work's sources in [lang] (multi-language ones too), most recently updated first. */
    suspend fun sourcesOf(key: String, lang: String): List<Pair<String, String>> = s.db.tx {
        query(
            "SELECT source_id, media_id FROM index_items WHERE work_key = ? AND (lang = ? OR lang = 'multi') ORDER BY updated_at DESC",
            key, lang
        ) { getString(1) to getString(2) }
    }

    /** The work a source page is in: the index's own entry, or the work the page was joined to (same work). */
    suspend fun keyOfPage(sourceId: String, mediaId: String): String? = s.db.tx {
        queryOne("SELECT work_key FROM index_items WHERE source_id = ? AND media_id = ? AND work_key IS NOT NULL", sourceId, mediaId) { getString(1) }
            ?: queryOne(
                """SELECT ws.work_key FROM work_sources ws WHERE ws.source_id = ? AND ws.media_id = ?
                   AND EXISTS(SELECT 1 FROM index_items i WHERE i.work_key = ws.work_key)""",
                sourceId, mediaId
            ) { getString(1) }
    }

    /** The language of a source page in the index (null when unknown). */
    suspend fun langOfPage(sourceId: String, mediaId: String): String? = s.db.tx {
        queryOne("SELECT lang FROM index_items WHERE source_id = ? AND media_id = ?", sourceId, mediaId) { getString(1) }
    }

    /** What this page of an anime is heard in (AudioTrack name; ORIGINAL when the site does not say a dub). */
    suspend fun audioOfPage(sourceId: String, mediaId: String): String = s.db.tx {
        queryOne("SELECT audio FROM index_items WHERE source_id = ? AND media_id = ?", sourceId, mediaId) { getString(1) }
    } ?: "ORIGINAL"

    /**
     * Works confirmed before their kind came from AniList keep the site's guess (a Korean work listed by a manga
     * site as manga): 50 at a time, in one request, they get AniList's kind. False when none was left or AniList rests.
     */
    suspend fun retypeSome(): Boolean {
        val rows = s.db.tx {
            query(
                """SELECT w.key, w.anilist_id, w.type FROM works w WHERE w.anilist_id IS NOT NULL AND w.kind_checked = 0
                   AND EXISTS(SELECT 1 FROM index_items i WHERE i.work_key = w.key) LIMIT 50"""
            ) { Triple(getString(1), getInt(2), getString(3)) }
        }
        if (rows.isEmpty() || s.aniList.resting) return false
        val kinds = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { runCatching { s.aniList.kinds(rows.map { it.second }) }.getOrNull() } ?: return false
        s.db.tx {
            rows.forEach { (key, id, typeName) ->
                val now = runCatching { MediaType.valueOf(typeName) }.getOrDefault(MediaType.MANGA)
                val kind = kinds[id]?.takeIf { TitleMatcher.sameFamily(it, now) } ?: now
                update("UPDATE works SET type = ?, kind_checked = 1 WHERE key = ?", kind.name, key)
            }
        }
        return true
    }

    /** Languages a work is in. */
    suspend fun langsOf(key: String): List<String> = s.db.tx {
        query("SELECT DISTINCT lang FROM index_items WHERE work_key = ?", key) { getString(1) }
    }

    /**
     * O2: the versions of a work in sources that work, as language + audio (AudioTrack name; ORIGINAL = Japanese with
     * subtitles in that language). Subtitled first, then the dubs.
     */
    suspend fun versionsOf(key: String, first: String = "es"): List<Map<String, String>> = s.db.tx {
        query(
            "SELECT DISTINCT lang, COALESCE(audio, 'ORIGINAL') FROM index_items WHERE work_key = ? AND source_id NOT IN (${downSql()})",
            key
        ) { getString(1) to getString(2) }
    }.sortedWith(compareBy({ it.second != "ORIGINAL" }, { it.first != first }, { it.first }, { it.second }))
        .map { (lang, audio) -> mapOf("lang" to lang, "audio" to audio) }

    companion object {
        const val PAGE_SIZE = 40
        const val LATEST_EVERY_MS = 2 * 3_600_000L
        private const val PAGES_PER_TICK = 6
        private const val PARALLEL_SOURCES = 4
        private const val MAX_ADULT_PAGES = 200
        private const val LATEST_ADULT_PAGES = 2
        private const val RETRY_BASE_MS = 10 * 60_000L
        private const val RETRY_MAX_SHIFT = 5
        private const val PAGE_PAUSE_MS = 2_000L
        private const val MAX_FULL_PAGES = 300
        private const val LATEST_PAGES = 8
        private const val GROUP_BATCH = 500

        // AniList allows about 30 requests a minute: the index takes a third, people opening works the rest.
        private const val CONFIRMS_PER_TICK = 20
        private const val MAX_WANTED = 500
        private const val ANILIST_PAUSE_MS = 1_500L
        private const val LOOKUP_TIMEOUT_MS = 10_000L
        private const val MAX_LOOKUP_NAMES = 5
        private const val SEARCH_LIMIT = 40
        private const val SEARCH_CANDIDATES = 300
        private const val MAX_TERMS = 8
        private const val FUZZY_BELOW = 5
        private const val FUZZY_PREFIX = 2
        private const val MIN_SQUASHED = 5
        private const val SQUASHED_PREFIX_SCORE = 0.8
        private const val FUZZY_CANDIDATES = 2_000

        /** Below this a work only shares letters with the query: left out rather than shown as nonsense. */
        private const val MIN_SEARCH_SCORE = 0.5
        private const val MAX_ID = 2_000
        private const val MAX_TITLE = 300
        private const val MAX_URL = 1_000
        private const val MAX_SHORT = 200
        private const val MAX_NAMES = 30
        private const val MAX_SYNOPSIS = 5_000
        private const val MAX_SEARCH_TEXT = 4_000
        private const val MAX_ALT_NAMES = 10
        private const val MAX_ALT_LENGTH = 120
    }
}

/** The catalogue by works: lists, search and a work in one language, all from the index (no live search). */
fun Route.workIndexRoutes(s: Services) {
    suspend fun io.ktor.server.application.ApplicationCall.indexOn(): Boolean {
        if (s.features.isOn("work_index")) return true
        respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador"))
        return false
    }
    fun io.ktor.server.application.ApplicationCall.lang() =
        request.queryParameters["lang"]?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: "es"
    fun io.ktor.server.application.ApplicationCall.types() =
        request.queryParameters["type"]?.split(',')?.map { it.trim().uppercase() }?.filter { t -> MediaType.entries.any { it.name == t } }?.toSet()
    fun WorkIndex.Card.shown() = copy(
        cover = if (cover.startsWith("http")) s.proxy.cover(cover, s.sources.get(coverSource)?.imageHeaders.orEmpty()) else cover
    )

    get("/obras") {
        if (!call.indexOn()) return@get
        val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE) ?: 1
        val sort = call.request.queryParameters["sort"]?.takeIf { it in setOf("updated", "popular", "az") } ?: "updated"
        call.respond(mapOf("works" to s.workIndex.list(
                call.lang(), call.types(), sort, page, hideAdult = !s.prefs(call.user).showAdult,
                audio = call.request.queryParameters["audio"]?.uppercase()
            ).map { it.shown() }, "page" to page))
    }
    get("/obras/buscar") {
        if (!call.indexOn()) return@get
        val q = call.request.queryParameters["q"]?.trim()?.take(MAX_QUERY).orEmpty()
        call.respond(mapOf("works" to s.workIndex.search(q, call.lang(), call.types(), hideAdult = !s.prefs(call.user).showAdult).map { it.shown() }))
    }
    get("/obras/anilist/{id}") {
        if (!call.indexOn()) return@get
        val key = call.parameters["id"]?.toIntOrNull()?.let { s.workIndex.byAniList(it) }
            ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Esa obra aún no está en el índice"))
        call.respond(mapOf("key" to key))
    }
    // Opening a title from AniList's rows (or anything with names): the work the index already has, or 404 at once.
    post("/obras/encontrar") {
        if (!call.indexOn()) return@post
        val body = com.google.gson.JsonParser.parseString(call.receiveText().take(MAX_BODY)).asJsonObject
        val type = body.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.let { t -> MediaType.entries.firstOrNull { it.name == t } } ?: MediaType.MANGA
        val names = body.getAsJsonArray("names")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.take(MAX_NAME) }.orEmpty()
        val title = body.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.take(MAX_NAME)
        val anilistId = body.get("anilistId")?.takeIf { it.isJsonPrimitive }?.asInt
        val key = s.workIndex.match(type, listOfNotNull(title) + names, anilistId, call.lang())
            ?: return@post call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Esa obra aún no está en el índice"))
        call.respond(mapOf("key" to key))
    }
    workOfPageRoute(s)
    workFichaRoute(s)
    presentRoute(s)
}

private const val MAX_SOURCE_ID = 60
private const val MAX_MEDIA_ID = 2_000

private fun io.ktor.server.application.ApplicationCall.indexLang() =
    request.queryParameters["lang"]?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: "es"

private fun Route.workOfPageRoute(s: Services) {
    // The work a source page belongs to and its languages, so any page (opened from the library, a search or
    // "Continuar") can switch to the other language, not only the ones opened from the index (F1.5).
    get("/obras/de") {
        if (!s.features.isOn("work_index")) return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador"))
        val sourceId = call.request.queryParameters["s"]?.take(MAX_SOURCE_ID).orEmpty()
        val mediaId = call.request.queryParameters["id"]?.take(MAX_MEDIA_ID).orEmpty()
        val key = s.workIndex.keyOfPage(sourceId, mediaId)
            ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Esa obra aún no está en el índice"))
        // Opened from the library or a search: the page's own audio is the version the user is in (O2).
        s.audioChoice.put(call.user.id, s.workIndex.audioOfPage(sourceId, mediaId))
        call.respond(
            mapOf(
                "key" to key, "lang" to s.workIndex.langOfPage(sourceId, mediaId), "langs" to s.workIndex.langsOf(key),
                "versions" to s.workIndex.versionsOf(key, call.indexLang()), "audio" to s.workIndex.audioOfPage(sourceId, mediaId)
            )
        )
    }
}

private fun Route.presentRoute(s: Services) {
    // Pedido 3 / O1: of AniList's ids (Home rows, calendar, related, explorer), which are in the user's sources and as
    // which work, so a row only shows what can be opened, and opens it at once (never «Buscando en tus fuentes…»).
    post("/obras/presentes") {
        if (!s.features.isOn("work_index")) return@post call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador"))
        val body = com.google.gson.JsonParser.parseString(call.receiveText().take(MAX_BODY)).asJsonObject
        val ids = body.getAsJsonArray("ids")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()?.takeIf { it > 0 } }
            .orEmpty().distinct().take(MAX_PRESENT_IDS)
        call.respond(mapOf("present" to s.workIndex.present(ids)))
    }
}

private fun Route.workFichaRoute(s: Services) {
    // A work in one language: its sources there, the languages it has, and its chapters joined (a slow source does
    // not hold the page: it waits for each one only a little).
    get("/obras/ficha/{key...}") {
        if (!s.features.isOn("work_index")) return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador"))
        val key = call.parameters.getAll("key").orEmpty().joinToString("/").take(MAX_KEY)
        val work = s.works.get(key) ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Esa obra no existe"))
        val langs = s.workIndex.langsOf(key)
        val lang = call.indexLang().takeIf { it in langs } ?: langs.firstOrNull() ?: call.indexLang()
        // The source the user already follows it in (library or progress) opens first: switching language and back
        // returns to the same page, not to whichever source updated last.
        val places = s.workIndex.sourcesOf(key, lang).filter { s.sources.get(it.first) != null }.let { all ->
            val mine = s.db.tx {
                all.filter { (sid, mid) ->
                    queryOne(
                        """SELECT 1 WHERE EXISTS(SELECT 1 FROM library WHERE user_id = ? AND source_id = ? AND media_id = ? AND deleted = 0)
                           OR EXISTS(SELECT 1 FROM progress WHERE user_id = ? AND source_id = ? AND media_id = ?)""",
                        call.user.id, sid, mid, call.user.id, sid, mid
                    ) { true } == true
                }
            }
            val audio = call.request.queryParameters["audio"]?.uppercase()?.takeIf { a -> com.nelson.yomukai.domain.model.AudioTrack.entries.any { it.name == a } }
            s.audioChoice.put(call.user.id, audio ?: "ORIGINAL")
            val audios = s.db.tx {
                all.associateWith { (sid, mid) ->
                    queryOne("SELECT audio FROM index_items WHERE source_id = ? AND media_id = ? AND audio IS NOT NULL LIMIT 1", sid, mid) { getString(1) }
                }
            }
            val ordered = orderWorkPlaces(all, mine.toSet(), audios, audio, s.sources.degraded.keys)
            // From a dub's section only that dub opens (O3): never a subtitled one in its place.
            if (audio == null || audio == "ORIGINAL") ordered
            else ordered.filter { audios[it] == audio && it.first !in s.sources.degraded.keys }
        }
        val only = places.map { it.first }.toSet()
        // ?light=1: only where it is (the clients open the work right away and load its chapters there).
        val light = call.request.queryParameters["light"] == "1"
        val chapters = if (light) null else s.works.chapters(call.user, key, listOf(lang.uppercase()), places.map { it.first }, only = only, waitMs = FICHA_WAIT_MS)
        call.respond(
            mapOf(
                "work" to s.works.dto(work, s.works.sourcesOf(key)) { s.proxy.cover(it, emptyMap()) }, "lang" to lang, "langs" to langs,
                "versions" to s.workIndex.versionsOf(key, lang),
                "sources" to places.map { (sid, mid) -> mapOf("sourceId" to sid, "mediaId" to mid, "name" to (s.sources.get(sid)?.name ?: sid)) },
                "chapters" to chapters
            )
        )
    }
}

/**
 * Which source of a work opens first (O2, O4): with ?audio= (a dub's section) the sources with that audio; otherwise
 * the one the user already follows, then the subtitled ones (original audio: by default a work opens in Japanese
 * with subtitles in the user's language), then the dubs. Sources that are down always go last.
 */
internal fun orderWorkPlaces(
    all: List<Pair<String, String>>,
    mine: Set<Pair<String, String>>,
    audios: Map<Pair<String, String>, String?>,
    wanted: String?,
    down: Set<String>,
): List<Pair<String, String>> = all.sortedWith(
    compareBy<Pair<String, String>> { it.first in down }
        .thenBy { wanted != null && (audios[it] ?: "ORIGINAL") != wanted }
        .thenBy { it !in mine }
        .thenBy { wanted == null && (audios[it] ?: "ORIGINAL") != "ORIGINAL" }
)

/**
 * O1: one work, one card, and only works that are known. Anime, manga, manhwa and manhua show once AniList has
 * confirmed them ("al:ID"): until then two names of one work could be two cards, and a title AniList does not know
 * (a fan work, a mislabeled upload) stays out. Western comics and novels, which AniList barely covers, still show.
 */
internal const val IDENTIFIED = "(w.key LIKE 'al:%' OR w.type IN ('COMIC', 'NOVEL'))"

private const val MAX_PAGE = 500
private const val MAX_BODY = 8_000
private const val MAX_NAME = 200
private const val MAX_QUERY = 100
private const val MAX_KEY = 300
private const val MAX_PRESENT_IDS = 200
private const val FICHA_WAIT_MS = 8_000L

/** Admin: how far the index has gone in each source, and starting it over. */
fun Route.workIndexAdminRoutes(s: Services) {
    get("/obras/estado") {
        val works = s.db.tx { queryOne("SELECT COUNT(*) FROM works WHERE EXISTS(SELECT 1 FROM index_items i WHERE i.work_key = works.key)") { getInt(1) } } ?: 0
        val confirmed = s.db.tx { queryOne("SELECT COUNT(DISTINCT work_key) FROM index_items WHERE work_key LIKE 'al:%'") { getInt(1) } } ?: 0
        call.respond(mapOf("works" to works, "confirmed" to confirmed, "sources" to s.workIndex.states()))
    }
    post("/obras/reiniciar") {
        s.workIndex.restart()
        call.respond(mapOf("ok" to true))
    }
}
