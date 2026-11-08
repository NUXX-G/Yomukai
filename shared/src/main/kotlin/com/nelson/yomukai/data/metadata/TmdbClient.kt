package com.nelson.yomukai.data.metadata

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import java.net.URLEncoder

/**
 * The Movie Database, only for its wide backdrops (1920 px and more): the title page and home hero of an anime look
 * right on big screens and TVs. Optional: without TMDB_API_KEY nothing is asked and AniList's banner stays.
 */
class TmdbClient(private val network: HttpFetcher, private val apiKey: String?) {
    val enabled: Boolean get() = !apiKey.isNullOrBlank()

    /** The original-size backdrop of the first series or film found for [names], or null. */
    suspend fun backdrop(names: List<String>): String? {
        if (!enabled) return null
        for (name in names.filter { it.isNotBlank() }.distinct().take(MAX_NAMES)) {
            val json = runCatching { network.getString(searchUrl(name, apiKey!!), emptyMap(), false) }.getOrNull() ?: continue
            pick(json, name)?.let { return it }
        }
        return null
    }

    companion object {
        private const val MAX_NAMES = 3
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/original"

        fun searchUrl(query: String, key: String) =
            "https://api.themoviedb.org/3/search/multi?include_adult=false&query=${URLEncoder.encode(query, "UTF-8")}&api_key=${URLEncoder.encode(key, "UTF-8")}"

        /**
         * The best backdrop in a /search/multi answer: series and films with one, Japanese productions first (this is
         * for anime), then an exact name match, then TMDB's own order.
         */
        fun pick(json: String, name: String): String? {
            val results = runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonArray("results") }.getOrNull() ?: return null
            fun JsonObject.s(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val wanted = name.trim().lowercase()
            return results.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                .filter { it.s("media_type") in setOf("tv", "movie") && it.s("backdrop_path").startsWith("/") }
                .withIndex()
                .sortedWith(
                    compareBy(
                        { (_, r) -> if (r.s("original_language") == "ja") 0 else 1 },
                        { (_, r) -> if (listOf(r.s("name"), r.s("title"), r.s("original_name"), r.s("original_title")).any { it.lowercase() == wanted }) 0 else 1 },
                        { (i, _) -> i }
                    )
                )
                .firstOrNull()?.value?.s("backdrop_path")?.takeIf { it.matches(SAFE_PATH) }?.let { IMAGE_BASE + it }
        }

        private val SAFE_PATH = Regex("/[A-Za-z0-9_-]+[.](jpg|jpeg|png|webp)")
    }
}
