package com.nelson.yomukai.data.metadata

import com.nelson.yomukai.data.discovery.TitleMatcher
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.data.source.strings
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

/**
 * MyAnimeList's community score. With a Client ID it asks MAL's official API (public data only, no
 * user login); without one it asks Jikan, which serves the same public data without a key. Jikan
 * allows about three requests a second, so calls go one at a time.
 */
class MalClient(private val network: NetworkHelper, private val clientId: String) {
    private val gate = Mutex()

    suspend fun score(names: List<String>, type: MediaType): Float? {
        val title = names.firstOrNull { name -> name.isNotBlank() && name.none { it.code > 0x24F } } ?: return null
        val kind = if (type == MediaType.ANIME) "anime" else "manga"
        val query = URLEncoder.encode(title.take(MAX_QUERY), "UTF-8")
        return gate.withLock {
            if (clientId.isNotBlank()) {
                val json = network.getString(
                    "https://api.myanimelist.net/v2/$kind?q=$query&limit=5&fields=mean,alternative_titles",
                    mapOf("X-MAL-CLIENT-ID" to clientId),
                    cloudflareFallback = false
                )
                parseOfficial(json, names)
            } else {
                val json = network.getString("https://api.jikan.moe/v4/$kind?q=$query&limit=5", cloudflareFallback = false)
                parseJikan(json, names)
            }
        }
    }

    companion object {
        private const val MAX_QUERY = 64

        /** Score of the first result sharing a name with [names], from MAL's official API. */
        fun parseOfficial(json: String, names: List<String>): Float? =
            parseJson(json).asJsonObject.arr("data").objects().mapNotNull { it.obj("node") }.firstNotNullOfOrNull { node ->
                val alt = node.obj("alternative_titles")
                val titles = listOfNotNull(node.str("title"), alt?.str("en"), alt?.str("ja")) + alt?.arr("synonyms").strings()
                if (!TitleMatcher.shareName(names, titles)) null else node.str("mean")?.toFloatOrNull()
            }

        /** Score of the first result sharing a name with [names], from Jikan. */
        fun parseJikan(json: String, names: List<String>): Float? =
            parseJson(json).asJsonObject.arr("data").objects().firstNotNullOfOrNull { entry ->
                val titles = listOfNotNull(entry.str("title"), entry.str("title_english"), entry.str("title_japanese")) +
                    entry.arr("titles").objects().mapNotNull { it.str("title") } + entry.arr("title_synonyms").strings()
                if (!TitleMatcher.shareName(names, titles)) null else entry.str("score")?.toFloatOrNull()
            }
    }
}
