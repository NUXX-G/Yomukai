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
        val title = searchName(names) ?: return null
        val kind = if (type == MediaType.ANIME) "anime" else "manga"
        val query = URLEncoder.encode(title.take(MAX_QUERY), "UTF-8")
        return gate.withLock {
            if (clientId.isNotBlank()) {
                val json = network.getString(
                    "https://api.myanimelist.net/v2/$kind?q=$query&limit=5&fields=mean,alternative_titles,media_type",
                    mapOf("X-MAL-CLIENT-ID" to clientId),
                    cloudflareFallback = false
                )
                parseOfficial(json, names, type)
            } else {
                val json = network.getString("https://api.jikan.moe/v4/$kind?q=$query&limit=5", cloudflareFallback = false)
                parseJikan(json, names, type)
            }
        }
    }

    companion object {
        private const val MAX_QUERY = 64
        private const val LATIN_LIMIT = 0x24F

        /**
         * The name to search MAL with: the first one written mostly in Latin script, with stray
         * symbols ("Madoka☆Magica") turned into spaces. Names in Japanese or Korean script are skipped.
         */
        fun searchName(names: List<String>): String? = names.firstNotNullOfOrNull { name ->
            val latin = name.count { it.code <= LATIN_LIMIT && it.isLetterOrDigit() }
            val letters = name.count { it.isLetterOrDigit() }
            if (letters == 0 || latin < letters * 0.8) return@firstNotNullOfOrNull null
            name.map { if (it.code > LATIN_LIMIT) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim()
                .takeIf { it.length >= 3 }
        }

        /**
         * MAL lists light novels with manga, often under the very same name ("Solo Leveling" is both):
         * a novel only matches a novel, and the rest only match the rest. A missing type says nothing.
         */
        fun sameKind(malType: String?, type: MediaType): Boolean {
            if (malType.isNullOrBlank() || type == MediaType.ANIME) return true
            val novel = malType.lowercase().replace(' ', '_') in setOf("novel", "light_novel")
            return novel == (type == MediaType.NOVEL)
        }

        /** Score of the first result of the same kind sharing a name with [names], from MAL's official API. */
        fun parseOfficial(json: String, names: List<String>, type: MediaType = MediaType.MANGA): Float? =
            parseJson(json).asJsonObject.arr("data").objects().mapNotNull { it.obj("node") }.firstNotNullOfOrNull { node ->
                val alt = node.obj("alternative_titles")
                val titles = listOfNotNull(node.str("title"), alt?.str("en"), alt?.str("ja")) + alt?.arr("synonyms").strings()
                if (!sameKind(node.str("media_type"), type) || !TitleMatcher.shareName(names, titles)) null
                else node.str("mean")?.toFloatOrNull()
            }

        /** Score of the first result of the same kind sharing a name with [names], from Jikan. */
        fun parseJikan(json: String, names: List<String>, type: MediaType = MediaType.MANGA): Float? =
            parseJson(json).asJsonObject.arr("data").objects().firstNotNullOfOrNull { entry ->
                val titles = listOfNotNull(entry.str("title"), entry.str("title_english"), entry.str("title_japanese")) +
                    entry.arr("titles").objects().mapNotNull { it.str("title") } + entry.arr("title_synonyms").strings()
                if (!sameKind(entry.str("type"), type) || !TitleMatcher.shareName(names, titles)) null
                else entry.str("score")?.toFloatOrNull()
            }
    }
}
