package com.nelson.yomukai.data.network

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** Decides which hosts skip Tor (sources the user excluded) and builds the SOCKS routing. Pure, so it is unit tested. */
object TorRouting {
    /** `cdn.example.com` -> `example.com`; IP addresses and single labels are returned unchanged. */
    fun registrableDomain(host: String): String {
        val h = host.lowercase().trim('.')
        if (h.isEmpty() || h.all { it.isDigit() || it == '.' || it == ':' }) return h
        val labels = h.split('.')
        return if (labels.size <= 2) h else labels.takeLast(2).joinToString(".")
    }

    fun domainsOf(urls: Collection<String>): Set<String> =
        urls.mapNotNull { runCatching { URI(it).host ?: it }.getOrNull() }
            .filter { it.isNotBlank() }
            .map(::registrableDomain)
            .toSet()

    /** True when [host] belongs to one of the [directDomains] (the domain itself or any subdomain). */
    fun bypasses(host: String?, directDomains: Set<String>): Boolean {
        val h = host?.lowercase()?.trim('.') ?: return false
        return directDomains.any { h == it || h.endsWith(".$it") }
    }

    /** Loopback addresses never go through a public exit. */
    fun isLoopback(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "localhost" || h == "127.0.0.1" || h == "::1"
    }
}

/** Sends everything through the Tor SOCKS port except hosts of excluded sources, which go direct. */
class TorProxySelector(private val socksPort: Int, private val directDomains: Set<String>) : ProxySelector() {
    private val tor = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host
        return if (TorRouting.bypasses(host, directDomains) || TorRouting.isLoopback(host)) {
            listOf(Proxy.NO_PROXY)
        } else {
            listOf(tor)
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
}
