package com.nelson.yomukai.data.work

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher

/** Where an episode's opening and ending are, in seconds (the same shape the home worker's audio fingerprints give). */
data class SkipMarks(val openingStart: Double?, val openingEnd: Double?, val endingStart: Double?, val endingEnd: Double?) {
    val isEmpty: Boolean get() = openingEnd == null && endingStart == null
}

/**
 * AniSkip (api.aniskip.com, MIT): opening/ending times shared by its community, by MyAnimeList id and episode. Used when
 * the home worker has not measured an episode yet, and by the app on its own; without either, the player keeps "+85 s".
 */
object AniSkip {
    fun url(malId: Int, episode: Int) = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types=op&types=ed&episodeLength=0"

    /** Reads AniSkip's answer; null when it knows nothing about the episode. Times outside 0..4 h are refused. */
    fun parse(json: String): SkipMarks? = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.get("found")?.asBoolean != true) return null
        var marks = SkipMarks(null, null, null, null)
        root.getAsJsonArray("results")?.forEach { e ->
            val o = e.asJsonObject
            val interval = o.getAsJsonObject("interval") ?: return@forEach
            val start = interval.get("startTime")?.asDouble ?: return@forEach
            val end = interval.get("endTime")?.asDouble ?: return@forEach
            if (start < 0 || end <= start || end > MAX_SECONDS) return@forEach
            marks = when (o.get("skipType")?.asString) {
                "op" -> marks.copy(openingStart = start, openingEnd = end)
                "ed" -> marks.copy(endingStart = start, endingEnd = end)
                else -> marks
            }
        }
        marks.takeUnless { it.isEmpty }
    }.getOrNull()

    suspend fun fetch(network: HttpFetcher, malId: Int, episode: Int): SkipMarks? {
        if (malId <= 0 || episode <= 0) return null
        return runCatching { parse(network.getString(url(malId, episode), mapOf("Accept" to "application/json"), cloudflareFallback = false)) }.getOrNull()
    }

    private const val MAX_SECONDS = 4 * 3600.0
}
