package com.nelson.yomukai.data.discovery

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.nelson.yomukai.data.network.HttpException
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.data.source.strings
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** A title recommended for another one; [names] find it in the user's sources. */
data class RelatedTitle(
    val title: String,
    val names: List<String>,
    val coverUrl: String,
    val type: MediaType,
    val genres: List<String> = emptyList(),
    /** Set when the title already comes from a source and can be opened directly. */
    val item: MediaItem? = null,
    /** How it relates to the title it was found for ("Secuela", "Adaptación"...), for related works. */
    val relation: String? = null,
    /** Kind of release shown under the card ("Anime", "Película", "Novela ligera"...). */
    val format: String = "",
    /** AniList's tags, which hold what its genres do not (Boys' Love, Gore...). */
    val tags: List<String> = emptyList()
)

/** What the random pick may be; a null [type] leaves that to chance too. */
enum class RandomKind(val label: String, val type: MediaType?) {
    ANY("Lo que sea", null), ANIME("Anime", MediaType.ANIME), MANGA("Manga", MediaType.MANGA),
    MANHWA("Manhwa", MediaType.MANHWA), NOVEL("Novela", MediaType.NOVEL)
}

/** What AniList knows about a title. */
data class AniListEntry(
    val names: List<String>,
    val related: List<RelatedTitle>,
    val tags: List<String>,
    val id: Int = 0,
    /** Community score from 0 to 100, when there are enough ratings. */
    val score: Int? = null,
    /** RELEASING, FINISHED, HIATUS, CANCELLED or NOT_YET_RELEASED. */
    val status: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val siteUrl: String = "",
    /** AniList's biggest cover (about 460 px wide), sharper than what most anime sites give. */
    val cover: String = "",
    /** Prequels, sequels, adaptations and spin-offs, in any format. */
    val relations: List<RelatedTitle> = emptyList(),
    val malId: Int? = null,
    /** Wide banner for title pages and the home hero. */
    val banner: String = "",
    val episodes: Int? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
    /** Next episode to air and when (epoch seconds), while an anime is airing. */
    val nextEpisode: Int? = null,
    val nextAiringAt: Long? = null
)

/**
 * AniList's public GraphQL API: every known name of a title (the sources often use different ones)
 * and what its community recommends. Answers stay in memory for the session, because the API allows
 * about 30 requests a minute.
 */
class AniListClient(private val network: HttpFetcher) {
    private val cache = object : LinkedHashMap<String, AniListEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AniListEntry>) = size > MAX_CACHED
    }
    private val lock = Mutex()
    private val misses = HashSet<String>()

    /** While set, AniList said to slow down: nothing is sent, and callers use what they already have. */
    @Volatile
    private var quietUntil = 0L

    /** Random picks waiting to be shown, and how many pages each kind of list has. */
    private val pools = HashMap<String, ArrayDeque<RelatedTitle>>()
    private val shown = HashMap<String, MutableList<RelatedTitle>>()
    private val pages = HashMap<String, Int>()

    /** The entry whose names best fit [names], or null when AniList does not know the title. */
    suspend fun lookup(names: List<String>, type: MediaType): AniListEntry? {
        val title = names.firstOrNull() ?: return null
        val key = "${type.name}|${SmartSearch.duplicateKey(title)}"
        lock.withLock { cache[key] }?.let { return it }
        // A title AniList answered without a match is not asked again this session.
        if (lock.withLock { key in misses }) return null
        // AniList's search is fuzzy; an answer only counts when one of its names is one of ours.
        val queries = names.filter { it.isNotBlank() }.distinct()
            .sortedBy { name -> name.any { it.code > LATIN_LIMIT } }
            .take(MAX_LOOKUP_QUERIES)
        var found: AniListEntry? = null
        for (search in queries) {
            found = parseEntries(query(LOOKUP, search, type)).firstOrNull { TitleMatcher.shareName(names, it.names) }
            if (found != null) break
        }
        if (found == null) {
            lock.withLock { misses.add(key) }
            return null
        }
        val entry = if (found.related.size >= ENOUGH) found else found.copy(related = (found.related + byTags(found, type)).distinctBy { it.title })
        lock.withLock { cache[key] = entry }
        return entry
    }

    /**
     * A random well-known title. One request brings a whole page of them, so most picks need no
     * network and the rate limit is never at risk. A short list simply starts over; while AniList
     * cannot be reached, titles already seen are reused.
     */
    suspend fun random(kind: RandomKind, genre: String?): RelatedTitle? {
        val type = kind.type ?: listOf(MediaType.ANIME, MediaType.MANGA).random()
        val key = "${type.name}|$genre"
        lock.withLock { pools[key]?.removeFirstOrNull() }?.let { return remember(key, it) }
        val fresh = runCatching { randomPage(key, type, genre) }.getOrDefault(emptyList())
        if (fresh.isEmpty()) return lock.withLock { shown[key]?.randomOrNull() }
        val pick = lock.withLock {
            val pool = pools.getOrPut(key) { ArrayDeque() }
            pool.addAll(fresh.shuffled())
            pool.removeFirstOrNull()
        }
        return pick?.let { remember(key, it) }
    }

    private suspend fun remember(key: String, title: RelatedTitle): RelatedTitle {
        lock.withLock { shown.getOrPut(key) { mutableListOf() }.add(title) }
        return title
    }

    private suspend fun randomPage(key: String, type: MediaType, genre: String?): List<RelatedTitle> {
        fun variables(page: Int) = JsonObject().apply {
            add("p", JsonPrimitive(page))
            add("t", JsonPrimitive(if (type == MediaType.ANIME) "ANIME" else "MANGA"))
            if (type == MediaType.NOVEL) add("f", JsonPrimitive("NOVEL"))
            else if (type != MediaType.ANIME) add("n", JsonPrimitive("NOVEL"))
            if (type == MediaType.MANHWA) add("c", JsonPrimitive("KR"))
            else if (type == MediaType.MANGA) add("c", JsonPrimitive("JP"))
            genre?.let { add("g", JsonPrimitive(it)) }
        }
        fun page(answer: String) = parseJson(answer).asJsonObject.obj("data")?.obj("Page")
        val known = lock.withLock { pages[key] }
        if (known == null) {
            val first = page(post(RANDOM, variables(1)))
            val total = first?.obj("pageInfo")?.str("total")?.toIntOrNull() ?: return emptyList()
            val count = (total / RANDOM_PAGE_SIZE + 1).coerceAtMost(MAX_RANDOM_PAGE)
            lock.withLock { pages[key] = count }
            if (count > 1) return page(post(RANDOM, variables((1..count).random())))?.arr("media").objects().mapNotNull(::toRelated)
            return first.arr("media").objects().mapNotNull(::toRelated)
        }
        return page(post(RANDOM, variables((1..known).random())))?.arr("media").objects().mapNotNull(::toRelated)
    }

    /** Other names of what [query] most likely refers to; each site may only know one of them. */
    suspend fun otherNames(query: String): List<String> {
        val answers = listOf(MediaType.ANIME, MediaType.MANGA).mapNotNull { type ->
            runCatching { parseEntries(query(LOOKUP, query, type)).firstOrNull() }.getOrNull()
        }
        return answers.flatMap { it.names.take(MAX_ALIASES) }.distinct()
    }

    /** Community recommendations run short on lesser-known titles: popular ones sharing its main themes fill in. */
    private suspend fun byTags(entry: AniListEntry, type: MediaType): List<RelatedTitle> {
        val tags = entry.tags.take(2)
        if (tags.isEmpty()) return emptyList()
        val variables = JsonObject().apply {
            add("t", JsonPrimitive(if (type == MediaType.ANIME) "ANIME" else "MANGA"))
            add("g", JsonArray().apply { tags.forEach { add(it) } })
        }
        val answer = runCatching { post(BY_TAGS, variables) }.getOrNull() ?: return emptyList()
        return parseJson(answer).asJsonObject.obj("data")?.obj("Page")?.arr("media").objects()
            .mapNotNull(::toRelated)
            .filterNot { TitleMatcher.shareName(it.names, entry.names) }
    }

    private suspend fun query(document: String, search: String, type: MediaType): String {
        val variables = JsonObject().apply {
            add("s", JsonPrimitive(search))
            add("t", JsonPrimitive(if (type == MediaType.ANIME) "ANIME" else "MANGA"))
            if (type == MediaType.NOVEL) add("f", JsonPrimitive("NOVEL"))
            else if (type != MediaType.ANIME) add("n", JsonPrimitive("NOVEL"))
        }
        return post(document, variables)
    }

    private suspend fun post(document: String, variables: JsonObject): String {
        if (System.currentTimeMillis() < quietUntil) throw IOException("AniList pide esperar")
        val body = JsonObject().apply {
            add("query", JsonPrimitive(document))
            add("variables", variables)
        }
        return try {
            network.postJson(ENDPOINT, body.toString())
        } catch (e: HttpException) {
            if (e.code == 429) quietUntil = System.currentTimeMillis() + QUIET_MS
            throw e
        }
    }

    companion object {
        private const val ENDPOINT = "https://graphql.anilist.co"
        private const val MAX_CACHED = 64
        private const val ENOUGH = 8
        private const val MAX_ALIASES = 2
        private const val MIN_TAG_RANK = 60
        private const val MAX_LOOKUP_QUERIES = 2
        private const val LATIN_LIMIT = 0x24F

        private const val FIELDS = "title{romaji english native} synonyms coverImage{large} type format countryOfOrigin genres tags{name rank}"
        private val LOOKUP = """
            query(${'$'}s:String,${'$'}t:MediaType,${'$'}f:MediaFormat,${'$'}n:MediaFormat){Page(perPage:5){media(search:${'$'}s,type:${'$'}t,format:${'$'}f,format_not:${'$'}n,sort:SEARCH_MATCH){
            $FIELDS id idMal averageScore meanScore status description(asHtml:false) siteUrl poster:coverImage{extraLarge} bannerImage
            episodes chapters volumes nextAiringEpisode{episode airingAt}
            relations{edges{relationType node{$FIELDS}}}
            recommendations(sort:RATING_DESC,perPage:24){nodes{rating mediaRecommendation{$FIELDS}}}}}}
        """.trimIndent()
        private const val RANDOM_PAGE_SIZE = 25
        private const val MAX_RANDOM_PAGE = 100
        private const val QUIET_MS = 60_000L
        private val RANDOM = """
            query(${'$'}p:Int,${'$'}t:MediaType,${'$'}f:MediaFormat,${'$'}n:MediaFormat,${'$'}g:String,${'$'}c:CountryCode){Page(page:${'$'}p,perPage:$RANDOM_PAGE_SIZE){pageInfo{total} media(type:${'$'}t,format:${'$'}f,format_not:${'$'}n,genre:${'$'}g,countryOfOrigin:${'$'}c,isAdult:false,sort:POPULARITY_DESC){$FIELDS}}}
        """.trimIndent()
        private val BY_TAGS = """
            query(${'$'}t:MediaType,${'$'}g:[String]){Page(perPage:24){media(type:${'$'}t,tag_in:${'$'}g,sort:POPULARITY_DESC){$FIELDS}}}
        """.trimIndent()

        private fun namesOf(media: JsonObject): List<String> {
            val title = media.obj("title")
            return (listOfNotNull(title?.str("romaji"), title?.str("english"), title?.str("native")) + media.arr("synonyms").strings())
                .filter { it.isNotBlank() }.distinct()
        }

        private fun typeOf(media: JsonObject): MediaType = when {
            media.str("type") == "ANIME" -> MediaType.ANIME
            media.str("format") == "NOVEL" -> MediaType.NOVEL
            media.str("countryOfOrigin") == "KR" -> MediaType.MANHWA
            media.str("countryOfOrigin") == "CN" -> MediaType.MANHUA
            else -> MediaType.MANGA
        }

        private fun tagsOf(media: JsonObject): List<String> =
            media.arr("tags").objects().filter { (it.str("rank")?.toIntOrNull() ?: 0) >= MIN_TAG_RANK }.mapNotNull { it.str("name") }

        /** "TV" -> "Anime", "NOVEL" -> "Novela ligera"... */
        fun formatLabel(format: String?, type: MediaType): String = when (format) {
            "TV" -> "Anime"
            "TV_SHORT" -> "Anime corto"
            "MOVIE" -> "Película"
            "SPECIAL" -> "Especial"
            "OVA" -> "OVA"
            "ONA" -> "ONA"
            "MUSIC" -> "Música"
            "NOVEL" -> "Novela ligera"
            "ONE_SHOT" -> "One-shot"
            else -> type.displayName
        }

        /** AniList's relation type in Spanish; null for the ones that only add noise. */
        fun relationLabel(type: String?): String? = when (type) {
            "PREQUEL" -> "Precuela"
            "SEQUEL" -> "Secuela"
            "ADAPTATION" -> "Adaptación"
            "SOURCE" -> "Obra original"
            "PARENT" -> "Historia principal"
            "SIDE_STORY" -> "Historia paralela"
            "SPIN_OFF" -> "Spin-off"
            "ALTERNATIVE" -> "Versión alternativa"
            "SUMMARY" -> "Resumen"
            "COMPILATION" -> "Recopilación"
            "CONTAINS" -> "Incluye"
            else -> null
        }

        /** Order in which relations are shown: the main line of the story first. */
        private val RELATION_ORDER = listOf(
            "Precuela", "Secuela", "Obra original", "Adaptación", "Historia principal", "Historia paralela", "Spin-off", "Versión alternativa"
        )

        private fun relationRank(label: String?) = RELATION_ORDER.indexOf(label).let { if (it < 0) RELATION_ORDER.size else it }

        private fun toRelated(media: JsonObject): RelatedTitle? {
            val names = namesOf(media)
            val title = media.obj("title")
            val shown = title?.str("english") ?: title?.str("romaji") ?: names.firstOrNull() ?: return null
            val type = typeOf(media)
            return RelatedTitle(
                shown, names, media.obj("coverImage")?.str("large").orEmpty(), type, media.arr("genres").strings(),
                format = formatLabel(media.str("format"), type), tags = tagsOf(media)
            )
        }

        private val BREAK = Regex("(?i)<br\\s*/?>")
        private val TAG = Regex("<[^>]+>")
        private val CREDIT = Regex("(?is)\\(\\s*source:.*?\\)|\\[\\s*written by.*?]")
        private val NOTE = Regex("(?im)^\\s*note:.*$")
        private val BLANK_LINES = Regex("\\n{3,}")

        /** AniList descriptions carry HTML, source credits and notes: only the story is kept. */
        fun cleanDescription(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.replace(BREAK, "\n").replace(TAG, "").replace(CREDIT, "").replace(NOTE, "")
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
                .replace(BLANK_LINES, "\n\n")
                .trim()
        }

        /** Every entry of a lookup answer, best match first; unrated recommendations are dropped. */
        fun parseEntries(json: String): List<AniListEntry> =
            parseJson(json).asJsonObject.obj("data")?.obj("Page")?.arr("media").objects().map { media ->
                AniListEntry(
                    names = namesOf(media),
                    related = media.obj("recommendations")?.arr("nodes").objects()
                        .filter { (it.str("rating")?.toIntOrNull() ?: 0) > 0 }
                        .mapNotNull { it.obj("mediaRecommendation")?.let(::toRelated) },
                    tags = tagsOf(media),
                    id = media.str("id")?.toIntOrNull() ?: 0,
                    score = (media.str("averageScore") ?: media.str("meanScore"))?.toIntOrNull(),
                    status = media.str("status").orEmpty(),
                    description = cleanDescription(media.str("description")),
                    genres = media.arr("genres").strings(),
                    siteUrl = media.str("siteUrl").orEmpty(),
                    cover = media.obj("poster")?.str("extraLarge").orEmpty(),
                    relations = media.obj("relations")?.arr("edges").objects().mapNotNull { edge ->
                        val label = relationLabel(edge.str("relationType")) ?: return@mapNotNull null
                        edge.obj("node")?.let(::toRelated)?.copy(relation = label)
                    }.sortedBy { relationRank(it.relation) },
                    malId = media.str("idMal")?.toIntOrNull(),
                    banner = media.str("bannerImage").orEmpty(),
                    episodes = media.str("episodes")?.toIntOrNull(),
                    chapters = media.str("chapters")?.toIntOrNull(),
                    volumes = media.str("volumes")?.toIntOrNull(),
                    nextEpisode = media.obj("nextAiringEpisode")?.str("episode")?.toIntOrNull(),
                    nextAiringAt = media.obj("nextAiringEpisode")?.str("airingAt")?.toLongOrNull()
                )
            }
    }
}
