package com.nelson.yomukai.data

import com.nelson.yomukai.data.network.TorManager
import com.nelson.yomukai.data.network.TorProxySelector
import com.nelson.yomukai.data.network.TorRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy
import java.net.URI

class TorRoutingTest {
    @Test
    fun registrableDomains() {
        assertEquals("example.com", TorRouting.registrableDomain("cdn.api.example.com"))
        assertEquals("marmota.me", TorRouting.registrableDomain("marmota.me"))
        assertEquals("127.0.0.1", TorRouting.registrableDomain("127.0.0.1"))
        assertEquals(setOf("asurascans.com", "imagesolymp.xyz"), TorRouting.domainsOf(listOf("https://api.asurascans.com/api", "https://media.imagesolymp.xyz/x")))
    }

    @Test
    fun bypassMatchesDomainAndSubdomainsOnly() {
        val direct = setOf("marmota.me")
        assertTrue(TorRouting.bypasses("marmota.me", direct))
        assertTrue(TorRouting.bypasses("cdn.marmota.me", direct))
        assertFalse(TorRouting.bypasses("notmarmota.me", direct))
        assertFalse(TorRouting.bypasses(null, direct))
    }

    @Test
    fun selectorUsesTorUnlessExcluded() {
        val selector = TorProxySelector(9050, setOf("marmota.me"))
        val viaTor = selector.select(URI("https://mangadex.org/x")).single()
        assertEquals(Proxy.Type.SOCKS, viaTor.type())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("https://marmota.me/x")).single())
        assertEquals(Proxy.Type.SOCKS, selector.select(URI("http://abc.onion/")).single().type())
    }

    @Test
    fun bootstrapParsing() {
        assertEquals(85, TorManager.parseBootstrap("NOTICE BOOTSTRAP PROGRESS=85 TAG=loading_descriptors SUMMARY=\"Loading\""))
        assertEquals(100, TorManager.parseBootstrap("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        assertNull(TorManager.parseBootstrap(null))
        assertNull(TorManager.parseBootstrap("garbage"))
    }
}
