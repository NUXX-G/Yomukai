package com.nelson.yomukai.data.source.manga

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class MegaBananaSourceTest {
    @Test
    fun catalogKeepsOnlyReadableComicsWithCleanTitles() {
        val json = JsonParser.parseString(
            """{"items":[
              {"id":6890,"title":"Leer Providence de Alan Moore Comic Online en Español","thumbnail":"https://x/c.webp","excerpt":"Terror…","readerReady":true},
              {"id":242,"title":"Batman: Soul of the Dragon es la MEJOR película","thumbnail":"","excerpt":"","readerReady":false}
            ]}"""
        ).asJsonObject
        val items = MegaBananaSource.parseCatalog(json, "megabanana")
        assertEquals(listOf("6890"), items.map { it.id })
        assertEquals("Providence de Alan Moore", items.single().title)
        assertEquals("Terror", items.single().description)
    }

    @Test
    fun partsBecomeChaptersInReadingOrder() {
        val json = JsonParser.parseString(
            """{"postId":6890,"chapters":[{"number":"1","title":"Capítulo 1 al 4"},{"number":"5","title":"Capítulo 5 al 7"},{"number":"8","title":""}]}"""
        ).asJsonObject
        val chapters = MegaBananaSource.parseChapters(json, "6890", "https://megabanana.mx/wp-json/megabanana/v1")
        assertEquals(listOf(1f, 5f, 8f), chapters.map { it.chapterNumber })
        assertEquals("https://megabanana.mx/wp-json/megabanana/v1/reader/6890?chapter=5", chapters[1].url)
        assertEquals("Capítulo 8", chapters[2].title)
    }

    @Test
    fun titlesLoseTheSiteWording() {
        assertEquals("Batman Reptilian", MegaBananaSource.cleanTitle("Leer Batman Reptilian Comic Online en Español"))
        assertEquals("Spider Geddon Saga Completa", MegaBananaSource.cleanTitle("Leer Spider Geddon Saga Completa Comic Online en Español"))
    }
}
