package com.nelson.yomukai.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbClientTest {
    private val json = javaClass.getResource("/tmdb_search_multi.json")!!.readText()

    @Test
    fun picksTheJapaneseSeriesBackdrop() {
        // The German film matches the name exactly, but this is for anime: the Japanese series wins.
        assertEquals("https://image.tmdb.org/t/p/original/96RT2A47UdzWlUfvIERFyBsLhL2.jpg", TmdbClient.pick(json, "Frieren"))
    }

    @Test
    fun nothingUsableAndNoKey() {
        assertNull(TmdbClient.pick("""{"results":[{"media_type":"tv","backdrop_path":"/../x.jpg"}]}""", "x"))
        assertNull(TmdbClient.pick("not json", "x"))
        assertFalse(TmdbClient(object : com.nelson.yomukai.data.network.HttpFetcher {
            override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean) = error("no")
            override suspend fun postString(url: String, headers: Map<String, String>) = error("no")
            override suspend fun postJson(url: String, body: String) = error("no")
            override suspend fun checkImage(url: String, headers: Map<String, String>) = error("no")
        }, null).enabled)
    }
}
