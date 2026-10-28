package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.manga.AsuraScansSource
import com.nelson.yomukai.data.source.manga.MangaDexSource
import com.nelson.yomukai.data.source.manga.MarmotaComicsSource
import com.nelson.yomukai.data.source.novel.TuNovelaLigeraSource
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
                FilterKeys.TYPE to setOf("ko"), FilterKeys.LANGUAGE to setOf("es")
            ),
            24
        )
        assertTrue("order[followedCount]=desc" in filtered && "offset=48" in filtered)
        assertTrue("status[]=ongoing" in filtered && "status[]=hiatus" in filtered && "originalLanguage[]=ko" in filtered)
        assertTrue("availableTranslatedLanguage[]=es-la" in filtered && "availableTranslatedLanguage[]=en" !in filtered)
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

    @Test
    fun madaraUrls() {
        assertEquals(
            "https://marmota.me/?s=&post_type=wp-manga&m_orderby=views&paged=2&status%5B%5D=end&genre%5B%5D=dc-comics",
            MarmotaComicsSource.catalogueUrl(
                2, mapOf(FilterKeys.SORT to setOf("views"), FilterKeys.STATUS to setOf("end"), FilterKeys.GENRE to setOf("dc-comics"))
            )
        )
        assertEquals("https://tunovelaligera.com/novelas/?m_orderby=latest", TuNovelaLigeraSource.catalogueUrl(1, emptyMap()))
        assertEquals(
            "https://tunovelaligera.com/novelas/page/3/?m_orderby=alphabet",
            TuNovelaLigeraSource.catalogueUrl(3, mapOf(FilterKeys.SORT to setOf("alphabet")))
        )
    }
}
