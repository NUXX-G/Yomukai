package com.nelson.yomukai.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedPageTest {
    @Test
    fun onlySignedHttpsPlayersAreFramedAndNothingLeaksIntoTheHtml() {
        val proxy = MediaProxy(ServerHttp(null), ByteArray(32) { 7 }, kotlin.io.path.createTempDirectory().toFile(), 2)
        val link = proxy.embed("https://upbolt.to/e/abc")!!
        val key = link.substringAfter("&k=")
        assertTrue(proxy.embedSigned("https://upbolt.to/e/abc", key))
        assertFalse("another address with the same key", proxy.embedSigned("https://evil.example/", key))
        assertNull("only https", proxy.embed("http://upbolt.to/e/abc"))
        val html = embedHtml("https://x.to/e/1\"><script>alert(1)</script>", "/obra/a/b")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("sandbox=\"allow-scripts allow-same-origin allow-presentation\""))
        assertFalse("never pop-ups nor moving the tab", html.contains("allow-popups") || html.contains("allow-top-navigation"))
        // Going back only ever stays on this site.
        assertEquals("/obra/a/b?x=1", safeBack("/obra/a/b?x=1"))
        listOf("//evil.example", "/\t/evil.example", "/ /evil.example", "https://evil.example", "/\\evil", "javascript:alert(1)", null)
            .forEach { assertEquals("/", safeBack(it)) }
    }

    @Test
    fun siguienteLeadsToTheNextEpisodeOfThisSiteOnly() {
        val next = "/ver/animeflv/https%3A%2F%2Fa%2F/https%3A%2F%2Fa%2Fep-2%2F"
        assertTrue(embedHtml("https://x.to/e/1", "/obra/a/b", next).contains("href=\"$next\">Siguiente"))
        assertFalse("without a next episode there is no button", embedHtml("https://x.to/e/1", "/obra/a/b").contains("Siguiente"))
    }
}
