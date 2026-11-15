package com.nelson.yomukai.server

import com.nelson.yomukai.data.discovery.DiscoverKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «En emisión esta temporada» only in the anime section, never in manga/manhwa or novels (W4). */
class DiscoverRowsTest {
    @Test
    fun seasonRowOnlyForAnime() {
        assertTrue("season" in Discover.rowOrder(DiscoverKind.ANIME))
        assertFalse("season" in Discover.rowOrder(DiscoverKind.MANGA))
        assertFalse("season" in Discover.rowOrder(DiscoverKind.NOVEL))
        assertTrue("releasing" in Discover.rowOrder(DiscoverKind.MANGA))
    }
}
