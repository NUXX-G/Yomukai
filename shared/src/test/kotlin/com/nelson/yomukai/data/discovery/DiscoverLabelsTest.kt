package com.nelson.yomukai.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverLabelsTest {
    @Test
    fun namesAndScoresAreSpanish() {
        assertEquals("Serie", DiscoverLabels.format("TV"))
        assertEquals("", DiscoverLabels.format("RARE"))
        assertEquals("Ciencia ficción", DiscoverLabels.genre("Sci-Fi"))
        assertEquals("Isekai", DiscoverLabels.genre("Isekai"))
        assertEquals("8,4", DiscoverLabels.score(84))
        assertEquals("", DiscoverLabels.score(null))
    }
}
