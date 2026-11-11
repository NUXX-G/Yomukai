package com.nelson.yomukai.server

import com.nelson.yomukai.data.discovery.AniListDiscover
import com.nelson.yomukai.data.discovery.DiscoverEntry
import com.nelson.yomukai.data.discovery.DiscoverFilter
import com.nelson.yomukai.data.discovery.DiscoverKind
import com.nelson.yomukai.data.discovery.MatchConfidence
import com.nelson.yomukai.data.discovery.RelatedTitle
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/** An AniList title for the web and the app, with where to open it when one of the sources has it. */
data class DiscoverItemDto(
    val anilistId: Int, val title: String, val names: List<String>, val cover: String, val banner: String, val color: String,
    val genres: List<String>, val score: Int?, val format: String, val type: String, val synopsis: String, val status: String,
    val season: String, val year: Int?, val episodes: Int?, val chapters: Int?, val nextEpisode: Int?, val nextAiringAt: Long?,
    val workKey: String?, val sourceId: String?, val mediaId: String?, val popularity: Int? = null
) {
    val available: Boolean get() = sourceId != null
}

data class DiscoverRowDto(val id: String, val title: String, val items: List<DiscoverItemDto>)

/**
 * Home and section pages built from AniList: rows cached 6 hours per kind (one request each), marked with the source
 * page to open when the title is already known to be in a source. A slow background walk looks for the top titles in
 * the sources (one every 20 s), so the hero and the rows fill up with titles that can actually be watched or read.
 */
class Discover(
    private val api: AniListDiscover,
    private val db: Database,
    private val catalog: Catalog,
    private val works: Works,
    /** AniList images go through our proxy: the page may only load images from its own site. */
    private val image: (String) -> String = { it }
) {
    private val log = LoggerFactory.getLogger("Discover")
    private val cache = ConcurrentHashMap<DiscoverKind, Pair<Long, Map<String, List<DiscoverEntry>>>>()
    private val pending = ConcurrentLinkedDeque<DiscoverEntry>()
    private val checked = ConcurrentHashMap<Int, Long>()

    /** AniList id → the synopsis in Spanish, from a Spanish source of the same work (AniList's is in English). */
    private val spanish = ConcurrentHashMap<Int, String>()
    private val wantSpanish = ConcurrentLinkedDeque<Pair<Int, String>>()

    suspend fun rows(kind: DiscoverKind, lang: String = "es"): List<DiscoverRowDto> {
        val now = System.currentTimeMillis()
        val cached = cache[kind]
        val data = if (cached != null && now - cached.first < TTL_MS) cached.second else {
            withTimeoutOrNull(12_000) { runCatching { api.rows(kind) }.getOrNull() }?.takeIf { it.isNotEmpty() }
                ?.also { fresh ->
                    cache[kind] = now to fresh
                    (fresh["trending"].orEmpty().take(10) + fresh["season"].orEmpty().take(8) + fresh["releasing"].orEmpty().take(6)).forEach(pending::add)
                } ?: cached?.second.orEmpty()
        }
        val order = listOf("trending", "season", "releasing", "popular", "top") + AniListDiscover.GENRE_ROWS.map { it.first }
        return order.mapNotNull { id -> data[id]?.let { DiscoverRowDto(id, TITLES[id] ?: id, decorate(it, lang)) } }
    }

    suspend fun browse(kind: DiscoverKind, filter: DiscoverFilter, lang: String = "es"): List<DiscoverItemDto> =
        decorate(withTimeoutOrNull(12_000) { runCatching { api.browse(kind, filter) }.getOrNull() }.orEmpty(), lang)

    /** Adds the source page for titles whose AniList id is already linked to a work with sources. */
    suspend fun decorate(entries: List<DiscoverEntry>, lang: String): List<DiscoverItemDto> {
        if (entries.isEmpty()) return emptyList()
        val ids = entries.map { it.anilistId }
        val marks = ids.joinToString(",") { "?" }
        val known = db.tx {
            query(
                """SELECT w.anilist_id, w.key, s.source_id, s.media_id FROM works w JOIN work_sources s ON s.work_key = w.key
                   WHERE w.anilist_id IN ($marks) ORDER BY s.added_at""", *ids.toTypedArray()
            ) { getInt(1) to Triple(getString(2), getString(3), getString(4)) }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.first() }
        return entries.map { e ->
            val k = known[e.anilistId]
            // In Spanish the synopsis of a Spanish source when there is one (found in the background, F7.4).
            val synopsis = if (lang == "es" && k != null) {
                spanish[e.anilistId] ?: e.synopsis.also { if (wantSpanish.none { it.first == e.anilistId } && wantSpanish.size < MAX_WANTED) wantSpanish.add(e.anilistId to k.first) }
            } else e.synopsis
            DiscoverItemDto(
                e.anilistId, e.title, e.names.take(8),
                e.cover.takeIf { it.isNotBlank() }?.let(image).orEmpty(),
                e.banner.takeIf { it.isNotBlank() }?.let(image).orEmpty(), e.color, e.genres, e.score, e.format, e.type.name, synopsis, e.status,
                e.season, e.year, e.episodes, e.chapters, e.nextEpisode, e.nextAiringAt, k?.first, k?.second, k?.third, e.popularity
            )
        }
    }

    /** A few Spanish synopses at a time, from the details of a Spanish source of the work (kept for the session). */
    suspend fun localizeSome() {
        repeat(SYNOPSES_PER_STEP) {
            val (id, key) = wantSpanish.pollFirst() ?: return
            if (spanish.containsKey(id)) return@repeat
            // Every page of the work (joined to it, or in the index under it), only those of a Spanish source.
            val pages = db.tx {
                query(
                    """SELECT source_id, media_id FROM work_sources WHERE work_key = ?
                       UNION SELECT source_id, media_id FROM index_items WHERE work_key = ? AND lang = 'es'""",
                    key, key
                ) { getString(1) to getString(2) }
            }.filter { (s, _) -> catalog.sources.get(s)?.language.equals("ES", ignoreCase = true) }.take(PAGES_PER_WORK)
            for ((s, m) in pages) {
                val text = withTimeoutOrNull(SYNOPSIS_WAIT_MS) { catalog.media(s, m).getOrNull() }?.description?.let(::plainSynopsis).orEmpty()
                if (text.length >= MIN_SYNOPSIS) { spanish[id] = text; break }
            }
        }
    }

    /** One step of the background walk: looks for a top title in the sources and links it to its work. */
    suspend fun resolveNext() {
        val entry = generateSequence { pending.pollFirst() }.firstOrNull { e ->
            val last = checked[e.anilistId]
            last == null || System.currentTimeMillis() - last > DAY_MS
        } ?: return
        checked[entry.anilistId] = System.currentTimeMillis()
        if (db.tx { queryOne("SELECT 1 FROM works w JOIN work_sources s ON s.work_key = w.key WHERE w.anilist_id = ?", entry.anilistId) { 1 } } != null) return
        val related = RelatedTitle(entry.title, entry.names, entry.cover, entry.type, entry.genres)
        val found = withTimeoutOrNull(60_000) { catalog.locate(SYSTEM, UserPrefs.DEFAULT, related).getOrNull() }.orEmpty()
        val best = found.firstOrNull { it.confidence == MatchConfidence.SAME_WORK.name } ?: return
        runCatching { works.resolve(best.item.sourceId, best.item.id) }
            .onSuccess { log.info("{} disponible en {}", entry.title, best.item.sourceId) }
    }

    companion object {
        private const val TTL_MS = 6 * 3_600_000L
        private const val MAX_WANTED = 200
        private const val PAGES_PER_WORK = 3
        private const val SYNOPSES_PER_STEP = 4
        private const val SYNOPSIS_WAIT_MS = 8_000L
        private const val MIN_SYNOPSIS = 40
        private const val MAX_SYNOPSIS = 1_200

        /** A site's synopsis as plain text: scraped data, so no tags, no runs of spaces, and not endless. */
        fun plainSynopsis(raw: String): String =
            raw.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_SYNOPSIS)
        private const val DAY_MS = 24 * 3_600_000L
        private val SYSTEM = User(0, "sistema", isAdmin = false, isGuest = false, createdAt = 0, expiresAt = null)
        val TITLES = mapOf(
            "trending" to "Tendencias", "season" to "En emisión esta temporada", "releasing" to "En publicación",
            "popular" to "Lo más popular", "top" to "Mejor valorados", "action" to "Acción", "romance" to "Romance",
            "fantasy" to "Fantasía", "comedy" to "Comedia"
        )

        fun kindOf(raw: String?): DiscoverKind? = when (raw) {
            "anime" -> DiscoverKind.ANIME
            "manga" -> DiscoverKind.MANGA
            "novelas", "novel" -> DiscoverKind.NOVEL
            else -> null
        }
    }
}

private fun discoverLang(raw: String?) = raw?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: "es"

fun Route.discoverRoutes(s: Services) {
    get("/discover/{kind}") {
        val kind = Discover.kindOf(call.parameters["kind"]) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Sección desconocida"))
        call.respond(mapOf("rows" to s.discover.rows(kind, discoverLang(call.request.queryParameters["lang"]))))
    }
    get("/discover/{kind}/browse") {
        val kind = Discover.kindOf(call.parameters["kind"]) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Sección desconocida"))
        val q = call.request.queryParameters
        val filter = DiscoverFilter(
            genre = q["genre"]?.take(30), year = q["year"]?.toIntOrNull(), status = q["status"]?.take(20), format = q["format"]?.take(20),
            country = q["country"]?.take(2), minScore = q["minScore"]?.toIntOrNull(), sort = q["sort"]?.take(30) ?: "TRENDING_DESC",
            page = q["page"]?.toIntOrNull() ?: 1
        )
        val items = s.discover.browse(kind, filter, discoverLang(q["lang"]))
        call.respond(mapOf("items" to if (q["available"] == "1") items.filter { it.available } else items))
    }
}
