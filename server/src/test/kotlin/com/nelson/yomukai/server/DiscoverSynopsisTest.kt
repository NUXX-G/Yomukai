package com.nelson.yomukai.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Spanish synopsis comes from a scraped page: plain text only, never markup (F7.4). */
class DiscoverSynopsisTest {
    @Test
    fun scrapedSynopsisBecomesPlainShortText() {
        val raw = "<p>Maomao  trabaja\n en el <b>palacio</b>.</p><script>alert(1)</script>"
        val text = Discover.plainSynopsis(raw)
        assertFalse(text.contains("<"))
        assertEquals("Maomao trabaja en el palacio . alert(1)", text)
        assertEquals(1_200, Discover.plainSynopsis("a".repeat(5_000)).length)
    }
}
