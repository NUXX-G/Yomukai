package com.nelson.yomukai.data

import com.nelson.yomukai.data.network.ResilientDns
import com.nelson.yomukai.data.network.TorRouting
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class NetworkLogicTest {
    private val v4 = InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))
    private val v6 = InetAddress.getByName("2001:db8::1")

    private class CountingDns(private val answer: () -> List<InetAddress>) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            return answer()
        }
    }

    @Test
    fun webViewNeverLeaksAHostThatMustUseTor() {
        assertFalse(TorRouting.webViewFallbackAllowed(torRoutesApp = true, webViewOnTor = false, webViewReady = true))
        assertTrue(TorRouting.webViewFallbackAllowed(torRoutesApp = true, webViewOnTor = true, webViewReady = true))
        assertTrue(TorRouting.webViewFallbackAllowed(torRoutesApp = false, webViewOnTor = false, webViewReady = true))
        assertFalse("a wanted proxy that is not applied yet blocks it", TorRouting.webViewFallbackAllowed(false, false, webViewReady = false))
    }

    @Test
    fun ipv4IsTriedFirst() {
        assertEquals(listOf(v4, v6), ResilientDns.ipv4First(listOf(v6, v4)))
    }

    @Test
    fun answersAreRememberedUntilTheyExpire() {
        var now = 0L
        val secure = CountingDns { listOf(v4) }
        val dns = ResilientDns(secure, CountingDns { emptyList() }) { now }
        dns.lookup("a.com")
        dns.lookup("a.com")
        assertEquals(1, secure.calls)
        now += 6 * 60_000
        dns.lookup("a.com")
        assertEquals(2, secure.calls)
    }

    @Test
    fun aFailingSecureResolverIsSkippedForAWhile() {
        var now = 0L
        val secure = CountingDns { throw UnknownHostException("blocked") }
        val system = CountingDns { listOf(v4) }
        val dns = ResilientDns(secure, system) { now }
        assertEquals(listOf(v4), dns.lookup("a.com"))
        dns.lookup("b.com")
        assertEquals(1, secure.calls)
        assertEquals(2, system.calls)
        now += 61_000
        dns.lookup("c.com")
        assertEquals(2, secure.calls)
    }
}

class ServerFeaturesTest {
    @org.junit.Test
    fun parsesTheServerAnswerAndNeverThrows() {
        val f = com.nelson.yomukai.data.sync.ServerSync.parseFeatures(
            """{"flags":{"worker_jobs":true,"bandwidth_cap":false},"worker":{"online":true,"gpu":false},"available":{"tts":true,"ocr":false}}"""
        )
        org.junit.Assert.assertTrue(f.on("worker_jobs"))
        org.junit.Assert.assertFalse(f.on("bandwidth_cap"))
        org.junit.Assert.assertFalse(f.on("desconocido"))
        org.junit.Assert.assertTrue(f.workerOnline)
        org.junit.Assert.assertEquals(setOf("tts"), f.available)
        org.junit.Assert.assertEquals(com.nelson.yomukai.data.sync.ServerFeatures(), com.nelson.yomukai.data.sync.ServerSync.parseFeatures("<html>"))
        org.junit.Assert.assertEquals(
            mapOf("asura" to "Capítulos: Sin capítulos"),
            com.nelson.yomukai.data.sync.ServerSync.parseDegraded("""[{"id":"asura","degraded":"Capítulos: Sin capítulos"},{"id":"mangadex","degraded":null}]""")
        )
        org.junit.Assert.assertEquals(emptyMap<String, String>(), com.nelson.yomukai.data.sync.ServerSync.parseDegraded("{}"))
    }
}
