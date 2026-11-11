package com.nelson.yomukai.data.network

/**
 * During football matches Spanish ISPs block Cloudflare addresses on LaLiga's request, and sites that have nothing to
 * do with football stop loading for a few hours (F2.3). hayahora.futbol publishes the addresses blocked right now
 * (https://hayahora.futbol/estado/blocked-any.txt, one per line): its observations, not an official list.
 */
object LaLigaBlocks {
    const val LIST_URL = "https://hayahora.futbol/estado/blocked-any.txt"

    /** What the list is read with: their data, unchecked, so only addresses survive and never too many. */
    fun parse(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { IPV4.matches(it) && it.split('.').all { p -> p.toInt() in 0..MAX_OCTET } || (IPV6.matches(it) && ':' in it) }
            .take(MAX_ADDRESSES).toSet()

    /** Whether any of a host's [addresses] is blocked now. */
    fun hits(addresses: Collection<String>, blocked: Set<String>): Boolean = addresses.any { it in blocked }

    /** A connection failure that looks like a block: the operator resets it or answers with its own certificate. */
    fun looksBlocked(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(MAX_CAUSES).any {
        it is javax.net.ssl.SSLException || it is java.net.ConnectException || it is java.net.SocketTimeoutException ||
            it is java.net.NoRouteToHostException || it.message.orEmpty().contains("PKIX") || it.message.orEmpty().contains("reset", ignoreCase = true)
    }

    const val NOTICE = "Esta fuente va lenta o no carga por el bloqueo de LaLiga; pasando por el servidor."

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6 = Regex("""[0-9a-fA-F:]{2,39}""")
    private const val MAX_ADDRESSES = 5_000
    private const val MAX_OCTET = 255
    private const val MAX_CAUSES = 8
}
