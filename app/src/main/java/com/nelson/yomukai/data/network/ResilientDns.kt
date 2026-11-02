package com.nelson.yomukai.data.network

import okhttp3.Dns
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS with three jobs that keep connections fast:
 *  - remembers answers, because a DNS-over-HTTPS lookup is a whole HTTPS request;
 *  - skips the [secure] resolver for a while after it fails, so a network that blocks it does not
 *    pay its timeout on every host, and falls back to [fallback];
 *  - puts IPv4 first: OkHttp tries addresses in order, and a broken IPv6 route would otherwise cost
 *    a full connect timeout.
 */
class ResilientDns(
    private val secure: Dns?,
    private val fallback: Dns = Dns.SYSTEM,
    private val clock: () -> Long = System::currentTimeMillis
) : Dns {
    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)
    private class Answer(val addresses: List<InetAddress>, val secure: Boolean)

    private val cache = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var secureDownUntil = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        val now = clock()
        cache[hostname]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        val answer = resolve(hostname, now)
        val addresses = ipv4First(answer.addresses)
        // Unprotected answers are kept briefly, so the secure resolver takes over as soon as it is back.
        cache[hostname] = Entry(addresses, now + if (answer.secure || secure == null) TTL_MS else FALLBACK_TTL_MS)
        return addresses
    }

    private fun resolve(hostname: String, now: Long): Answer {
        if (secure != null && now >= secureDownUntil) {
            try {
                val found = secure.lookup(hostname)
                if (found.isNotEmpty()) return Answer(found, true)
            } catch (e: IOException) {
                // A name that does not exist says nothing about the resolver's health.
                if (e.message?.contains("NXDOMAIN") != true) secureDownUntil = now + SECURE_RETRY_MS
            }
        }
        return Answer(fallback.lookup(hostname).ifEmpty { throw UnknownHostException(hostname) }, false)
    }

    companion object {
        private const val TTL_MS = 5 * 60_000L
        private const val FALLBACK_TTL_MS = 60_000L
        private const val SECURE_RETRY_MS = 60_000L

        fun ipv4First(addresses: List<InetAddress>): List<InetAddress> = addresses.sortedBy { it !is Inet4Address }
    }
}
