package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.manga.AsuraScansSource
import com.nelson.yomukai.data.source.manga.MangaDexSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueFiltersTest {
    @Test
    fun mangaDexDefaultsAndFilters() {
        val default = MangaDexSource.catalogueRequest(1, emptyMap(), 24)
        assertTrue("order[latestUploadedChapter]=desc" in default && "offset=0" in default)
        val filtered = MangaDexSource.catalogueRequest(
            3,
            mapOf(
                FilterKeys.SORT to setOf("popular"), FilterKeys.STATUS to setOf("ongoing", "hiatus"),
                FilterKeys.TYPE to setOf("ko")
            ),
            24
        )
        assertTrue("order[followedCount]=desc" in filtered && "offset=48" in filtered)
        assertTrue("status[]=ongoing" in filtered && "status[]=hiatus" in filtered && "originalLanguage[]=ko" in filtered)
        assertTrue("the Spanish source only lists works in Spanish", "availableTranslatedLanguage[]=es-la" in filtered && "availableTranslatedLanguage[]=en" !in filtered)
    }

    @Test
    fun asuraQuery() {
        assertEquals("/series?sort=latest&limit=24&page=2", AsuraScansSource.catalogueRequest(2, emptyMap(), 24))
        assertEquals(
            "/series?sort=title&limit=24&page=1&status=completed&type=manhua",
            AsuraScansSource.catalogueRequest(
                1, mapOf(FilterKeys.SORT to setOf("title"), FilterKeys.STATUS to setOf("completed"), FilterKeys.TYPE to setOf("manhua")), 24
            )
        )
    }
}
