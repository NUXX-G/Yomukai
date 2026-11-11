package com.nelson.yomukai.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F2.3: the list of blocked addresses is outside data: only addresses, and failures that look like a block. */
class LaLigaBlocksTest {
    @Test
    fun onlyAddressesSurviveFromTheList() {
        val list = "192.124.249.25\n104.16.93.114\r\n\n# comentario\n<html>no</html>\n999.1.1.1\n2606:4700::6810:5d72\n"
        assertEquals(setOf("192.124.249.25", "104.16.93.114", "2606:4700::6810:5d72"), LaLigaBlocks.parse(list))
        assertTrue(LaLigaBlocks.parse("").isEmpty())
    }

    @Test
    fun aHostIsHitWhenOneOfItsAddressesIsListed() {
        val blocked = setOf("104.16.93.114")
        assertTrue(LaLigaBlocks.hits(listOf("104.16.93.115", "104.16.93.114"), blocked))
        assertFalse(LaLigaBlocks.hits(listOf("8.8.8.8"), blocked))
    }

    @Test
    fun resetsAndForeignCertificatesLookLikeABlock() {
        assertTrue(LaLigaBlocks.looksBlocked(javax.net.ssl.SSLHandshakeException("PKIX path building failed")))
        assertTrue(LaLigaBlocks.looksBlocked(RuntimeException(java.net.SocketException("Connection reset"))))
        assertTrue(LaLigaBlocks.looksBlocked(java.net.ConnectException("refused")))
        assertFalse(LaLigaBlocks.looksBlocked(IllegalStateException("404")))
    }
}
