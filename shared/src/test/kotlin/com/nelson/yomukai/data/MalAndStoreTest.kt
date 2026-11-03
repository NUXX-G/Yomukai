package com.nelson.yomukai.data

import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.data.metadata.MalClient
import com.nelson.yomukai.data.metadata.MetadataStore
import com.nelson.yomukai.data.metadata.TitleMetadata
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.PublicationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The JSON here follows the documented shapes of MAL's API v2 and Jikan v4 (neither answers from the dev network). */
class MalAndStoreTest {
    private val official = """
        {"data":[
          {"node":{"id":1,"title":"Ore dake Level Up na Ken","mean":8.1,"alternative_titles":{"synonyms":[],"en":"Solo Leveling: Ragnarok","ja":""}}},
          {"node":{"id":2,"title":"Na Honjaman Level Up","mean":8.6,"alternative_titles":{"synonyms":["Only I Level Up"],"en":"Solo Leveling","ja":"나 혼자만 레벨업"}}}
        ]}
    """.trimIndent()

    private val jikan = """
        {"data":[
          {"title":"Na Honjaman Level Up","title_english":"Solo Leveling","title_japanese":"나 혼자만 레벨업","title_synonyms":["Only I Level Up"],
           "titles":[{"type":"Default","title":"Na Honjaman Level Up"}],"score":8.63}
        ]}
    """.trimIndent()

    @Test
    fun officialApiScoreOfTheSameWork() {
        assertEquals(8.6f, MalClient.parseOfficial(official, listOf("Solo Leveling"))!!, 0.001f)
        assertNull(MalClient.parseOfficial(official, listOf("Berserk")))
    }

    @Test
    fun realMalAnswerTellsTheManhwaFromTheNovel() {
        val json = checkNotNull(javaClass.classLoader?.getResource("mal_manga.json")).readText()
        assertEquals(8.54f, MalClient.parseOfficial(json, listOf("Solo Leveling"), MediaType.MANHWA)!!, 0.001f)
        assertEquals(8.22f, MalClient.parseOfficial(json, listOf("Solo Leveling"), MediaType.NOVEL)!!, 0.001f)
        assertNull(MalClient.parseOfficial(json, listOf("Omniscient Reader"), MediaType.MANHWA))
    }

    @Test
    fun mangaUpdatesTellsTheManhwaFromTheNovel() {
        val json = checkNotNull(javaClass.classLoader?.getResource("mangaupdates_search.json")).readText()
        assertEquals(8.47f, com.nelson.yomukai.data.metadata.MangaUpdatesClient.parse(json, listOf("Solo Leveling"), MediaType.MANHWA)!!, 0.001f)
        assertEquals(7.31f, com.nelson.yomukai.data.metadata.MangaUpdatesClient.parse(json, listOf("Solo Leveling"), MediaType.NOVEL)!!, 0.001f)
        assertNull(com.nelson.yomukai.data.metadata.MangaUpdatesClient.parse(json, listOf("Berserk"), MediaType.MANGA))
    }

    @Test
    fun openLibraryNeedsEnoughRatingsAndTheSameAuthor() {
        val json = checkNotNull(javaClass.classLoader?.getResource("openlibrary_search.json")).readText()
        assertEquals(8.94f, com.nelson.yomukai.data.metadata.OpenLibraryClient.parse(json, listOf("Watchmen"), "Alan Moore")!!, 0.01f)
        assertNull("another author's Watchmen", com.nelson.yomukai.data.metadata.OpenLibraryClient.parse(json, listOf("Watchmen"), "Ben Bova"))
    }

    @Test
    fun searchNameKeepsLatinNamesAndDropsSymbols() {
        assertEquals("Magia Record: Mahou Shoujo Madoka Magica Gaiden", MalClient.searchName(listOf("Magia Record: Mahou Shoujo Madoka☆Magica Gaiden")))
        assertEquals("Solo Leveling", MalClient.searchName(listOf("나 혼자만 레벨업", "Solo Leveling")))
        assertNull(MalClient.searchName(listOf("俺だけレベルアップな件")))
    }

    @Test
    fun jikanScoreOfTheSameWork() {
        assertEquals(8.63f, MalClient.parseJikan(jikan, listOf("Only I Level Up"))!!, 0.001f)
        assertNull(MalClient.parseJikan(jikan, listOf("Tower of God")))
    }

    @Test
    fun storedMetadataComesBackTheSame() {
        val metadata = TitleMetadata(
            score = 8.4f, scoreSource = "AniList", malScore = 8.6f, officialStatus = PublicationStatus.COMPLETED,
            synopsis = "Línea uno\n\"citada\"", genres = listOf("Acción", "Fantasía"), inferredGenres = true,
            relations = listOf(RelatedTitle("Solo Leveling", listOf("Ore dake Level Up na Ken"), "https://x/c.png", MediaType.ANIME, listOf("Action"), relation = "Adaptación", format = "Anime", tags = listOf("Gore"))),
            siteUrl = "https://anilist.co/manga/105398"
        )
        val text = MetadataStore.encodeAll(mapOf("s|1" to (123L to metadata)))
        val back = MetadataStore.decodeAll(text)
        assertEquals(123L, back["s|1"]!!.first)
        assertEquals(metadata, back["s|1"]!!.second)
    }
}
