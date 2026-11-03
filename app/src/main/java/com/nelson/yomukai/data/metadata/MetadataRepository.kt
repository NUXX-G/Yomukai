package com.nelson.yomukai.data.metadata

import androidx.compose.runtime.Immutable
import com.nelson.yomukai.data.discovery.AniListClient
import com.nelson.yomukai.data.discovery.AniListEntry
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.data.discovery.TitleMatcher
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.PublicationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** What the app knows about a title beyond what its source says. */
@Immutable
data class TitleMetadata(
    /** Community score out of 10, or null when no database knows the title. */
    val score: Float? = null,
    /** "AniList" or "Kitsu". */
    val scoreSource: String = "",
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
    val siteUrl: String = ""
)

/** Kitsu's public API: only a score, used when AniList does not know the title or is out of reach. */
class KitsuClient(private val network: NetworkHelper) {
    suspend fun score(names: List<String>, type: MediaType): Float? {
        val title = names.firstOrNull { name -> name.none { it.code > 0x24F } } ?: return null
        val kind = if (type == MediaType.ANIME) "anime" else "manga"
        val url = "https://kitsu.io/api/edge/$kind?filter%5Btext%5D=${URLEncoder.encode(title, "UTF-8")}" +
            "&page%5Blimit%5D=3&fields%5B$kind%5D=canonicalTitle,averageRating,titles,abbreviatedTitles"
        return parseScore(network.getString(url, mapOf("Accept" to "application/vnd.api+json"), cloudflareFallback = false), names)
    }

    companion object {
        /** The score of the first result that shares a name with [names], out of 10. */
        fun parseScore(json: String, names: List<String>): Float? =
            parseJson(json).asJsonObject.arr("data").objects().firstNotNullOfOrNull { entry ->
                val attributes = entry.obj("attributes") ?: return@firstNotNullOfOrNull null
                val titles = attributes.obj("titles")?.entrySet()?.mapNotNull { (_, v) -> v.takeIf { it.isJsonPrimitive }?.asString }.orEmpty() +
                    listOfNotNull(attributes.str("canonicalTitle"))
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
class MetadataRepository(private val aniList: AniListClient, private val kitsu: KitsuClient) {
    private val cache = ConcurrentHashMap<String, TitleMetadata>()

    fun cached(media: MediaItem): TitleMetadata? = cache[SmartSearch.keyOf(media)]

    suspend fun load(media: MediaItem): TitleMetadata {
        cache[SmartSearch.keyOf(media)]?.let { return it }
        val names = (listOf(media.title) + media.altTitles).filter { it.isNotBlank() }.distinct()
        val entry = safely { withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { aniList.lookup(names, media.type) } }
        val kitsuScore = if (entry?.score == null) safely { withTimeoutOrNull(KITSU_TIMEOUT_MS) { kitsu.score(names + entry?.names.orEmpty(), media.type) } } else null
        val result = build(media, entry, kitsuScore)
        // A failed lookup is retried next time; a complete answer is kept.
        if (entry != null || kitsuScore != null) cache[SmartSearch.keyOf(media)] = result
        return result
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
        private const val LOOKUP_TIMEOUT_MS = 8_000L
        private const val KITSU_TIMEOUT_MS = 6_000L

        fun statusOf(aniListStatus: String): PublicationStatus? = when (aniListStatus) {
            "RELEASING" -> PublicationStatus.ONGOING
            "FINISHED" -> PublicationStatus.COMPLETED
            "HIATUS" -> PublicationStatus.HIATUS
            "CANCELLED" -> PublicationStatus.CANCELLED
            else -> null
        }

        /** Pure: joins the layers. */
        fun build(media: MediaItem, entry: AniListEntry?, kitsuScore: Float?): TitleMetadata {
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
                    kitsuScore != null -> "Kitsu"
                    else -> ""
                },
                officialStatus = entry?.status?.let(::statusOf),
                synopsis = entry?.description.orEmpty(),
                genres = genres,
                inferredGenres = inferred,
                relations = entry?.relations.orEmpty(),
                siteUrl = entry?.siteUrl.orEmpty()
            )
        }

        private const val MAX_OFFICIAL_TAGS = 4
        private const val MAX_GENRES = 10
    }
}
