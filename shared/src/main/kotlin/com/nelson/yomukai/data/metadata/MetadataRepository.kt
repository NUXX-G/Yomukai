package com.nelson.yomukai.data.metadata

import com.nelson.yomukai.data.discovery.AniListClient
import com.nelson.yomukai.data.discovery.AniListEntry
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.data.discovery.TitleMatcher
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
import com.nelson.yomukai.domain.model.PublicationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** What the app knows about a title beyond what its source says. */
data class TitleMetadata(
    /** Community score out of 10, or null when no database knows the title. */
    val score: Float? = null,
    /** "AniList" or "Kitsu". */
    val scoreSource: String = "",
    /** MyAnimeList's score out of 10, shown next to the main one. */
    val malScore: Float? = null,
    /** Status from the database, more reliable than the sources' free text. */
    val officialStatus: PublicationStatus? = null,
    /** Clean official synopsis, usually in English. */
    val synopsis: String = "",
    /** Source genres and official ones, in Yomukai's names and without repeats. */
    val genres: List<String> = emptyList(),
    /** True when [genres] were guessed from the synopsis because nothing listed any. */
    val inferredGenres: Boolean = false,
    /** Prequels, sequels and adaptations in every format. */
    val relations: List<RelatedTitle> = emptyList(),
    val siteUrl: String = "",
    /** The database's large cover, for title pages whose source only has a small one. */
    val cover: String = "",
    /** AniList's wide banner for the top of the title page (may be missing in data saved by older versions). */
    val banner: String = "",
    /** MyAnimeList id, when AniList knows it (opening/ending times come by it). */
    val malId: Int? = null
) {
    /** True when some database answered; false when only the local layers (taxonomy, keywords) did. */
    val fromNetwork: Boolean
        get() = score != null || malScore != null || officialStatus != null || synopsis.isNotBlank() || relations.isNotEmpty()
}

/** Kitsu's public API: only a score, used when AniList does not know the title or is out of reach. */
class KitsuClient(private val network: HttpFetcher) {
    /** A wide banner and a large poster of the work, for works AniList has none for (or does not know). */
    data class Images(val banner: String, val poster: String)

    suspend fun images(names: List<String>, type: MediaType): Images? {
        val title = MalClient.searchName(names) ?: return null
        val kind = if (type == MediaType.ANIME) "anime" else "manga"
        val url = "$BASE/$kind?filter%5Btext%5D=${URLEncoder.encode(title, "UTF-8")}" +
            "&page%5Blimit%5D=5&fields%5B$kind%5D=canonicalTitle,titles,abbreviatedTitles,subtype,coverImage,posterImage"
        return parseImages(network.getString(url, mapOf("Accept" to "application/vnd.api+json"), cloudflareFallback = false), names, type)
    }

    suspend fun score(names: List<String>, type: MediaType): Float? {
        val title = MalClient.searchName(names) ?: return null
        val kind = if (type == MediaType.ANIME) "anime" else "manga"
        val url = "$BASE/$kind?filter%5Btext%5D=${URLEncoder.encode(title, "UTF-8")}" +
            "&page%5Blimit%5D=5&fields%5B$kind%5D=canonicalTitle,averageRating,titles,abbreviatedTitles,subtype"
        return parseScore(network.getString(url, mapOf("Accept" to "application/vnd.api+json"), cloudflareFallback = false), names, type)
    }

    companion object {
        /** Kitsu moved from kitsu.io to kitsu.app. */
        private const val BASE = "https://kitsu.app/api/edge"

        /** The banner (wide) and poster of the first result of the same kind sharing a name with [names]. */
        fun parseImages(json: String, names: List<String>, type: MediaType): Images? =
            parseJson(json).asJsonObject.arr("data").objects().firstNotNullOfOrNull { entry ->
                val attributes = entry.obj("attributes") ?: return@firstNotNullOfOrNull null
                if (!MalClient.sameKind(attributes.str("subtype"), type)) return@firstNotNullOfOrNull null
                val titles = attributes.obj("titles")?.entrySet()?.mapNotNull { (_, v) -> v.takeIf { it.isJsonPrimitive }?.asString }.orEmpty() +
                    listOfNotNull(attributes.str("canonicalTitle")) + attributes.arr("abbreviatedTitles").strings()
                if (!TitleMatcher.shareName(names, titles)) return@firstNotNullOfOrNull null
                fun best(o: com.google.gson.JsonObject?) = o?.let { it.str("original") ?: it.str("large") }.orEmpty()
                Images(best(attributes.obj("coverImage")), best(attributes.obj("posterImage"))).takeIf { it.banner.isNotBlank() || it.poster.isNotBlank() }
            }

        /**
         * The score of the first result of the same kind sharing a name with [names], out of 10.
         * Kitsu lists novels with manga ("Solo Leveling" is both), so its subtype must agree.
         */
        fun parseScore(json: String, names: List<String>, type: MediaType = MediaType.MANGA): Float? =
            parseJson(json).asJsonObject.arr("data").objects().firstNotNullOfOrNull { entry ->
                val attributes = entry.obj("attributes") ?: return@firstNotNullOfOrNull null
                if (!MalClient.sameKind(attributes.str("subtype"), type)) return@firstNotNullOfOrNull null
                val titles = attributes.obj("titles")?.entrySet()?.mapNotNull { (_, v) -> v.takeIf { it.isJsonPrimitive }?.asString }.orEmpty() +
                    listOfNotNull(attributes.str("canonicalTitle")) + attributes.arr("abbreviatedTitles").strings()
                if (!TitleMatcher.shareName(names, titles)) return@firstNotNullOfOrNull null
                attributes.str("averageRating")?.toFloatOrNull()?.div(10f)
            }
    }
}

/**
 * Builds [TitleMetadata] in three layers, all off the main thread and cached for the session:
 * 1. AniList (Kitsu for the score when AniList fails): official synopsis, genres, status, score, relations.
 * 2. [GenreTaxonomy]: every genre in Yomukai's own names.
 * 3. [KeywordTagger]: genres guessed from the synopsis when no one lists any.
 */
class MetadataRepository(
    private val aniList: AniListClient,
    private val kitsu: KitsuClient,
    private val mal: MalClient? = null,
    private val store: MetadataStore? = null,
    private val mangaUpdates: MangaUpdatesClient? = null,
    private val openLibrary: OpenLibraryClient? = null
) {
    private val cache = ConcurrentHashMap<String, TitleMetadata>()

    /** Titles no database knew, with when that was found out. */
    private val misses = ConcurrentHashMap<String, Pair<Long, TitleMetadata>>()

    fun cached(media: MediaItem): TitleMetadata? = cache[SmartSearch.keyOf(media)]

    /**
     * Memory first, then the copy on disk, then AniList and MAL at the same time (Kitsu only when both fail).
     * [anilistId]: the work is already known for sure (the works index): AniList is asked for it by id, not by name, so
     * a sequel whose name each site writes its own way still gets its relations; a copy saved for another work is
     * not used.
     */
    suspend fun load(media: MediaItem, anilistId: Int? = null): TitleMetadata = coroutineScope {
        val key = SmartSearch.keyOf(media)
        saved(key, media, anilistId)?.let { return@coroutineScope it }
        val names = (listOf(media.title) + media.altTitles).filter { it.isNotBlank() }.distinct()
        // Every database is asked at once; the slowest one decides how long this takes, not the sum.
        val malUsable = mal != null && System.currentTimeMillis() > malDownUntil
        val malScore = async { if (malUsable) malLookup(names, media.type) else null }
        val mangaUpdatesEarly = async { safely { withTimeoutOrNull(EXTRA_TIMEOUT_MS) { mangaUpdates?.score(names, media.type) } } }
        val kitsuEarly = async { safely { withTimeoutOrNull(KITSU_TIMEOUT_MS) { kitsu.score(names, media.type) } } }
        // AniList failing (asking to wait, unreachable) is not "AniList does not know it": that is not kept as a miss.
        var aniListFailed = false
        val entry = try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { anilistId?.let { aniList.byId(it, media.type) } ?: aniList.lookup(names, media.type) }
                .also { if (it == null && aniList.resting) aniListFailed = true }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            aniListFailed = true
            null
        }
        // With AniList's answer in hand, MAL gets only a short extra wait: its score is a bonus.
        val first = if (entry != null) withTimeoutOrNull(MAL_GRACE_MS) { malScore.await() }.also { if (it == null) malScore.cancel() } else malScore.await()
        // MAL may only know the title by the English or romaji name AniList gives.
        val malValue = first ?: entry?.names?.minus(names.toSet())?.takeIf { it.isNotEmpty() && malUsable && System.currentTimeMillis() > malDownUntil }?.let { other ->
            withTimeoutOrNull(MAL_GRACE_MS) { malLookup(other + names, media.type) }
        }
        // Without AniList or MAL, the other databases are asked in turn, each for what it covers best:
        // MangaUpdates (Asian comics and novels), Kitsu, then Open Library (Western comics, published novels).
        val fallback: Pair<Float, String>? = if (entry?.score == null && malValue == null) {
            val all = names + entry?.names.orEmpty()
            mangaUpdatesEarly.await()?.let { it to "MangaUpdates" }
                ?: kitsuEarly.await()?.let { it to "Kitsu" }
                ?: safely { withTimeoutOrNull(EXTRA_TIMEOUT_MS) { openLibrary?.score(all, media.author, media.type) } }?.let { it to "Open Library" }
        } else null
        val result = build(media, entry, fallback?.first, malValue, fallback?.second ?: "Kitsu")
        // A failed lookup is retried next time; a complete answer is kept.
        if (entry != null || fallback != null || malValue != null) {
            cache[key] = result
            store?.put(key, result)
        } else {
            // Nothing anywhere: not asked again for a while, so the title page does not wait each visit.
            if (!aniListFailed) misses[key] = System.currentTimeMillis() to result
        }
        mangaUpdatesEarly.cancel()
        kitsuEarly.cancel()
        result
    }

    /** What is already known for [media] (memory, disk, a recent miss), if it is for the right work. */
    private suspend fun saved(key: String, media: MediaItem, anilistId: Int?): TitleMetadata? {
        fun right(m: TitleMetadata) = anilistId == null || m.siteUrl.contains("/$anilistId/") || m.siteUrl.endsWith("/$anilistId")
        cache[key]?.takeIf(::right)?.let { return it }
        // Copies saved before covers were kept are asked again once (every AniList answer has a cover).
        store?.get(key)?.takeUnless { it.siteUrl.isNotBlank() && it.cover.isBlank() }?.takeIf(::right)?.let { saved ->
            // Guessed genres are worked out again: the rules may have changed since they were saved.
            val fresh = if (saved.inferredGenres) withLocalGenres(saved, media) else saved
            cache[key] = fresh
            return fresh
        }
        return misses[key]?.takeIf { anilistId == null && System.currentTimeMillis() - it.first < MISS_TTL_MS }?.second
    }

    /** When MAL (or Jikan, without a Client ID) stopped answering: it is left alone for a while. */
    @Volatile private var malDownUntil = 0L

    private suspend fun malLookup(names: List<String>, type: com.nelson.yomukai.domain.model.MediaType): Float? {
        val client = mal ?: return null
        val start = System.currentTimeMillis()
        val value = safely { withTimeoutOrNull(MAL_TIMEOUT_MS) { client.score(names, type) } }
        if (value == null && System.currentTimeMillis() - start >= MAL_TIMEOUT_MS - 200) malDownUntil = System.currentTimeMillis() + MAL_PAUSE_MS
        return value
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    companion object {
        private const val LOOKUP_TIMEOUT_MS = 7_000L
        private const val KITSU_TIMEOUT_MS = 5_000L
        private const val MAL_TIMEOUT_MS = 5_000L
        private const val EXTRA_TIMEOUT_MS = 5_000L
        private const val MISS_TTL_MS = 30 * 60_000L
        private const val MAL_GRACE_MS = 1_500L
        private const val MAL_PAUSE_MS = 10 * 60_000L

        /** [saved] with its guessed genres worked out again from the title's synopsis. */
        fun withLocalGenres(saved: TitleMetadata, media: MediaItem): TitleMetadata {
            val genres = KeywordTagger.infer(media.description.ifBlank { saved.synopsis })
            return saved.copy(genres = genres, inferredGenres = genres.isNotEmpty())
        }

        fun statusOf(aniListStatus: String): PublicationStatus? = when (aniListStatus) {
            "RELEASING" -> PublicationStatus.ONGOING
            "FINISHED" -> PublicationStatus.COMPLETED
            "HIATUS" -> PublicationStatus.HIATUS
            "CANCELLED" -> PublicationStatus.CANCELLED
            else -> null
        }

        /** Pure: joins the layers. */
        fun build(
            media: MediaItem, entry: AniListEntry?, kitsuScore: Float?, malScore: Float? = null, fallbackSource: String = "Kitsu"
        ): TitleMetadata {
            // AniList tags are many and in English: only the ones the taxonomy knows become genres.
            val official = entry?.genres.orEmpty() + entry?.tags.orEmpty().filter(GenreTaxonomy::isKnown).take(MAX_OFFICIAL_TAGS)
            var genres = GenreTaxonomy.merge(media.genres, official).take(MAX_GENRES)
            var inferred = false
            if (genres.isEmpty()) {
                genres = KeywordTagger.infer(media.description.ifBlank { entry?.description.orEmpty() })
                inferred = genres.isNotEmpty()
            }
            val score = entry?.score?.let { it / 10f } ?: kitsuScore
            return TitleMetadata(
                score = score,
                scoreSource = when {
                    entry?.score != null -> "AniList"
                    kitsuScore != null -> fallbackSource
                    else -> ""
                },
                malScore = malScore,
                officialStatus = entry?.status?.let(::statusOf),
                synopsis = entry?.description.orEmpty(),
                genres = genres,
                inferredGenres = inferred,
                relations = entry?.relations.orEmpty(),
                siteUrl = entry?.siteUrl.orEmpty(),
                cover = entry?.cover.orEmpty(),
                banner = entry?.banner.orEmpty(),
                malId = entry?.malId
            )
        }

        private const val MAX_OFFICIAL_TAGS = 4
        private const val MAX_GENRES = 10
    }
}
