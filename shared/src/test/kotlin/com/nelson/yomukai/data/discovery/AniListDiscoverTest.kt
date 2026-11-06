package com.nelson.yomukai.data.discovery

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AniListDiscoverTest {
    private val fixture = javaClass.classLoader!!.getResource("anilist_discover.json")!!.readText()

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
    fun parsesTheRealAnswer() {
        val data = JsonParser.parseString(fixture).asJsonObject.getAsJsonObject("data")
        val trending = AniListDiscover.parse(data.getAsJsonObject("trending"))
        assertEquals(3, trending.size)
        val first = trending.first()
        assertEquals(180136, first.anilistId)
        assertEquals("The Exiled Heavy Knight Knows How to Game the System", first.title)
        assertEquals(MediaType.ANIME, first.type)
        assertTrue(first.cover.startsWith("https://"))
        assertTrue(AniListDiscover.parse(data.getAsJsonObject("top")).all { it.type != MediaType.ANIME && it.type != MediaType.NOVEL })
    }

    @Test
    fun oneRequestPerPageWithEveryRowAsAnAlias() = runBlocking {
        val http = Capture(fixture)
        val rows = AniListDiscover(http).rows(DiscoverKind.ANIME, LocalDate.of(2026, 11, 6))
        assertEquals(1, http.queries.size)
        val q = http.queries.single()
        assertTrue(q.contains("season:FALL,seasonYear:2026"))
        assertTrue(q.contains("isAdult:false"))
        assertTrue(q.contains("action:Page") && q.contains("genre:\"Action\""))
        assertEquals(3, rows["trending"]!!.size)
    }

    @Test
    fun explorerFiltersAreCheckedBeforeGoingIntoTheQuery() = runBlocking {
        val http = Capture(fixture)
        AniListDiscover(http).browse(DiscoverKind.NOVEL, DiscoverFilter(genre = "Romance\"){evil}", year = 2020, status = "BORRAR", format = "NOVEL", sort = "SCORE_DESC"))
        val q = http.queries.single()
        assertFalse("injected genre dropped", q.contains("evil"))
        assertFalse(q.contains("BORRAR"))
        assertTrue(q.contains("format:NOVEL") && q.contains("sort:SCORE_DESC") && q.contains("startDate_greater:20200000"))
    }

    @Test
    fun seasons() {
        assertEquals("WINTER" to 2027, AniListDiscover.seasonOf(LocalDate.of(2026, 12, 20)))
        assertEquals("FALL" to 2026, AniListDiscover.seasonOf(LocalDate.of(2026, 11, 6)))
    }
}
