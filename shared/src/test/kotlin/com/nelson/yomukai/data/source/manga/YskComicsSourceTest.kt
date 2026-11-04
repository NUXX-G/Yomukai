package com.nelson.yomukai.data.source.manga

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class YskComicsSourceTest {
    @Test
    fun listsAndIssuesComeFromTheApi() {
        val list = JsonParser.parseString(
            """[{"id":1265,"image":"https://cdn/c.webp","full_name":"American Caper (2025)","slug":"american-caper-2025","writer":"Dan Houser","genres":[{"name":"Crime"}]},{"id":1,"full_name":"sin slug"}]"""
        )
        val items = YskComicsSource.parseList(list, "yskcomics")
        assertEquals(listOf("american-caper-2025"), items.map { it.id })
        assertEquals(listOf("Crime"), items.single().genres)

        val issues = JsonParser.parseString(
            """[{"name":"Batman (2025) 1#","slug":"batman-2025-1","rank":"1"},{"name":"Batman (2025) 2.5#","slug":"batman-2025-2-5","rank":"2.5"}]"""
        )
        val chapters = YskComicsSource.parseChapters(issues, "batman-2025", "https://api.ysk-comics.com/api/v1")
        assertEquals(listOf("#1", "#2.5"), chapters.map { it.title })
        assertEquals(listOf(1f, 2.5f), chapters.map { it.chapterNumber })
        assertEquals("https://api.ysk-comics.com/api/v1/chapters/batman-2025-1/images", chapters[0].url)
    }
}
