package com.nelson.yomukai.data.discovery

import androidx.compose.runtime.Immutable
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.nelson.yomukai.data.network.NetworkHelper
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

/** A title recommended for another one; [names] find it in the user's sources. */
@Immutable
data class RelatedTitle(
    val title: String,
    val names: List<String>,
    val coverUrl: String,
    val type: MediaType,
    val genres: List<String> = emptyList(),
    /** Set when the title already comes from a source and can be opened directly. */
    val item: MediaItem? = null
)

/** What the random pick may be; a null [type] leaves that to chance too. */
enum class RandomKind(val label: String, val type: MediaType?) {
    ANY("Lo que sea", null), ANIME("Anime", MediaType.ANIME), MANGA("Manga", MediaType.MANGA),
    MANHWA("Manhwa", MediaType.MANHWA), NOVEL("Novela", MediaType.NOVEL)
}

/** What AniList knows about a title. */
@Immutable
data class AniListEntry(
    val names: List<String>,
    val related: List<RelatedTitle>,
    val tags: List<String>
)

/**
 * AniList's public GraphQL API: every known name of a title (the sources often use different ones)
 * and what its community recommends. Answers stay in memory for the session, because the API allows
 * about 30 requests a minute.
 */
class AniListClient(private val network: NetworkHelper) {
    private val cache = object : LinkedHashMap<String, AniListEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AniListEntry>) = size > MAX_CACHED
    }
    private val lock = Mutex()

    /** The entry whose names best fit [names], or null when AniList does not know the title. */
    suspend fun lookup(names: List<String>, type: MediaType): AniListEntry? {
        val title = names.firstOrNull() ?: return null
        val key = "${type.name}|${SmartSearch.duplicateKey(title)}"
        lock.withLock { cache[key] }?.let { return it }
        val found = parseEntries(query(LOOKUP, title, type)).firstOrNull { TitleMatcher.shareName(names, it.names) }
            ?: return null
        val entry = if (found.related.size >= ENOUGH) found else found.copy(related = (found.related + byTags(found, type)).distinctBy { it.title })
        lock.withLock { cache[key] = entry }
        return entry
    }

    /** A random well-known title. Asks how many there are, then one page at random among them. */
    suspend fun random(kind: RandomKind, genre: String?): RelatedTitle? {
        val type = kind.type ?: listOf(MediaType.ANIME, MediaType.MANGA).random()
        fun variables(page: Int) = JsonObject().apply {
            add("p", JsonPrimitive(page))
            add("t", JsonPrimitive(if (type == MediaType.ANIME) "ANIME" else "MANGA"))
            if (type == MediaType.NOVEL) add("f", JsonPrimitive("NOVEL"))
            else if (type != MediaType.ANIME) add("n", JsonPrimitive("NOVEL"))
            genre?.let { add("g", JsonPrimitive(it)) }
        }
        val total = parseJson(post(RANDOM, variables(1))).asJsonObject.obj("data")?.obj("Page")?.obj("pageInfo")?.str("total")?.toIntOrNull() ?: return null
        if (total <= 0) return null
        val page = (1..total.coerceAtMost(MAX_RANDOM_PAGE)).random()
        return parseJson(post(RANDOM, variables(page))).asJsonObject.obj("data")?.obj("Page")?.arr("media").objects().firstOrNull()?.let(::toRelated)
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
        val body = JsonObject().apply {
            add("query", JsonPrimitive(document))
            add("variables", variables)
        }
        return network.postJson(ENDPOINT, body.toString())
    }

    companion object {
        private const val ENDPOINT = "https://graphql.anilist.co"
        private const val MAX_CACHED = 64
        private const val ENOUGH = 8
        private const val MAX_ALIASES = 2
        private const val MIN_TAG_RANK = 60

        private const val FIELDS = "title{romaji english native} synonyms coverImage{large} type format countryOfOrigin genres"
        private val LOOKUP = """
            query(${'$'}s:String,${'$'}t:MediaType,${'$'}f:MediaFormat,${'$'}n:MediaFormat){Page(perPage:5){media(search:${'$'}s,type:${'$'}t,format:${'$'}f,format_not:${'$'}n,sort:SEARCH_MATCH){
            $FIELDS tags{name rank} recommendations(sort:RATING_DESC,perPage:24){nodes{rating mediaRecommendation{$FIELDS}}}}}}
        """.trimIndent()
        private const val MAX_RANDOM_PAGE = 5000
        private val RANDOM = """
            query(${'$'}p:Int,${'$'}t:MediaType,${'$'}f:MediaFormat,${'$'}n:MediaFormat,${'$'}g:String){Page(page:${'$'}p,perPage:1){pageInfo{total} media(type:${'$'}t,format:${'$'}f,format_not:${'$'}n,genre:${'$'}g,isAdult:false,sort:POPULARITY_DESC){$FIELDS}}}
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

        private fun toRelated(media: JsonObject): RelatedTitle? {
            val names = namesOf(media)
            val title = media.obj("title")
            val shown = title?.str("english") ?: title?.str("romaji") ?: names.firstOrNull() ?: return null
            return RelatedTitle(shown, names, media.obj("coverImage")?.str("large").orEmpty(), typeOf(media), media.arr("genres").strings())
        }

        /** Every entry of a lookup answer, best match first; unrated recommendations are dropped. */
        fun parseEntries(json: String): List<AniListEntry> =
            parseJson(json).asJsonObject.obj("data")?.obj("Page")?.arr("media").objects().map { media ->
                AniListEntry(
                    names = namesOf(media),
                    related = media.obj("recommendations")?.arr("nodes").objects()
                        .filter { (it.str("rating")?.toIntOrNull() ?: 0) > 0 }
                        .mapNotNull { it.obj("mediaRecommendation")?.let(::toRelated) },
                    tags = media.arr("tags").objects()
                        .filter { (it.str("rank")?.toIntOrNull() ?: 0) >= MIN_TAG_RANK }
                        .mapNotNull { it.str("name") }
                )
            }
    }
}
