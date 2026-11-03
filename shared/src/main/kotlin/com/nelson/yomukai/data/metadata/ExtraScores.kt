package com.nelson.yomukai.data.metadata

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.nelson.yomukai.data.discovery.TitleMatcher
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.data.source.strings
import com.nelson.yomukai.domain.model.MediaType
import java.net.URLEncoder

/**
 * MangaUpdates (Baka-Updates): manga, manhwa, manhua and Asian novels, web novels included, with
 * no account. Each entry says whether it is the novel or the comic ("Solo Leveling (Novel)").
 */
class MangaUpdatesClient(private val network: HttpFetcher) {
    suspend fun score(names: List<String>, type: MediaType): Float? {
        if (type == MediaType.ANIME || type == MediaType.COMIC) return null
        val title = MalClient.searchName(names) ?: return null
        val body = JsonObject().apply {
            add("search", JsonPrimitive(title))
            add("perpage", JsonPrimitive(PER_PAGE))
        }
        return parse(network.postJson(ENDPOINT, body.toString()), names, type)
    }

    companion object {
        private const val ENDPOINT = "https://api.mangaupdates.com/v1/series/search"
        private const val PER_PAGE = 8

        /** Rating of the first result of the same kind sharing a name with [names], out of 10. */
        fun parse(json: String, names: List<String>, type: MediaType): Float? =
            parseJson(json).asJsonObject.arr("results").objects().firstNotNullOfOrNull { hit ->
                val record = hit.obj("record") ?: return@firstNotNullOfOrNull null
                if (!MalClient.sameKind(record.str("type"), type)) return@firstNotNullOfOrNull null
                val titles = listOfNotNull(record.str("title"), hit.str("hit_title"))
                if (!TitleMatcher.shareName(names, titles)) return@firstNotNullOfOrNull null
                record.str("bayesian_rating")?.toFloatOrNull()?.takeIf { it > 0f }
            }
    }
}

/**
 * Open Library's readers' ratings (out of 5) for published works: Western comics and novels. Only
 * titles with a handful of ratings count, and a different author rules a result out.
 */
class OpenLibraryClient(private val network: HttpFetcher) {
    suspend fun score(names: List<String>, author: String, type: MediaType): Float? {
        if (type != MediaType.COMIC && type != MediaType.NOVEL) return null
        val title = MalClient.searchName(names) ?: return null
        val url = "https://openlibrary.org/search.json?q=${URLEncoder.encode(title, "UTF-8")}" +
            "&fields=title,ratings_average,ratings_count,author_name&limit=8"
        return parse(network.getString(url, cloudflareFallback = false), names, author)
    }

    companion object {
        private const val MIN_RATINGS = 5

        /** Out of 10: Open Library's 0-5 average, doubled. */
        fun parse(json: String, names: List<String>, author: String): Float? =
            parseJson(json).asJsonObject.arr("docs").objects().firstNotNullOfOrNull { doc ->
                val count = doc.str("ratings_count")?.toIntOrNull() ?: 0
                if (count < MIN_RATINGS) return@firstNotNullOfOrNull null
                if (!TitleMatcher.shareName(names, listOfNotNull(doc.str("title")))) return@firstNotNullOfOrNull null
                val authors = doc.arr("author_name").strings().joinToString(" ")
                if (TitleMatcher.authorsDisagree(author, authors)) return@firstNotNullOfOrNull null
                doc.str("ratings_average")?.toFloatOrNull()?.times(2f)
            }
    }
}
