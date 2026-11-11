package com.nelson.yomukai.data.discovery

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** F5: the week's airing schedule and what the community rates this season, from real AniList answers. */
class AniListSeasonTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private class Capture(private val answer: String) : HttpFetcher {
        val queries = mutableListOf<String>()
        override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean) = error("no")
        override suspend fun postString(url: String, headers: Map<String, String>) = error("no")
        override suspend fun postJson(url: String, body: String): String {
            queries += JsonParser.parseString(body).asJsonObject.get("query").asString
            return answer
        }
        override suspend fun checkImage(url: String, headers: Map<String, String>) = error("no")
    }

    @Test
    fun airingIsOneRequestWithItsOwnEpisodeAndTime() = runBlocking {
        val http = Capture(fixture("anilist_airing.json"))
        val week = AniListDiscover(http).airing(1_794_400_000, 1_795_000_000)
        val q = http.queries.single()
        assertTrue(q.contains("airingAt_greater:1794400000") && q.contains("airingAt_lesser:1795000000"))
        assertTrue(q.contains("p4:Page(page:4"))
        // Adult titles never; titles almost nobody follows (137 and 19 people) left out.
        assertEquals(listOf(172192, 206401, 188034), week.map { it.anilistId })
        val first = week.first()
        assertEquals(6, first.nextEpisode)
        assertEquals(1_794_411_900L, first.nextAiringAt)
        assertEquals(35094, first.popularity)
        assertTrue(first.cover.startsWith("https://s4.anilist.co/"))
        assertTrue(first.genres.isNotEmpty())
    }

    @Test
    fun communityHasTheBestRatedAndTheTrendingOfTheSeason() = runBlocking {
        val http = Capture(fixture("anilist_season_community.json"))
        val rows = AniListDiscover(http).seasonCommunity(LocalDate.of(2026, 11, 11))
        val q = http.queries.single()
        assertTrue(q.contains("season:FALL,seasonYear:2026"))
        assertTrue(q.contains("sort:SCORE_DESC,popularity_greater:${AniListDiscover.MIN_RATED_POPULARITY}"))
        assertTrue(q.contains("isAdult:false"))
        val top = rows["top"]!!
        assertEquals(4, top.size)
        assertEquals(88, top.first().score)
        assertTrue(top.zipWithNext().all { (a, b) -> (a.score ?: 0) >= (b.score ?: 0) })
        assertEquals(4, rows["trending"]!!.size)
    }

    @Test
    fun picksAreLookedUpByIdOnlyWithNumbers() = runBlocking {
        val http = Capture("""{"data":{"list":{"media":[]}}}""")
        val api = AniListDiscover(http)
        assertTrue(api.byIds(emptyList()).isEmpty())
        assertTrue(http.queries.isEmpty())
        api.byIds(listOf(5, 5, -1, 7))
        assertTrue(http.queries.single().contains("id_in:[5,7]"))
        assertFalse(http.queries.single().contains("-1"))
    }
}
