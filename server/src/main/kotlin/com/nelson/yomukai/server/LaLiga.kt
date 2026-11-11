package com.nelson.yomukai.server

import com.nelson.yomukai.data.network.LaLigaBlocks
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * LaLiga's blocks in Spain (F2.3): the addresses blocked right now (hayahora.futbol, every 10 min), whether this server
 * had to go out through its proxy lately, and what clients are told. Nothing about users is ever sent anywhere.
 */
object LaLigaState {
    private val log = LoggerFactory.getLogger("LaLiga")

    @Volatile var blocked: Set<String> = emptySet()
        private set

    @Volatile var updatedAt: Long = 0
        private set

    @Volatile private var lastProxy: Long = 0

    /**
     * Blocks going on now: a match blocks dozens of Cloudflare addresses at once (one or two stay listed all week, so
     * those alone are not a match), or this server had to use its way out in the last half hour.
     */
    val active: Boolean get() = blocked.size >= MATCH_BLOCKS || System.currentTimeMillis() - lastProxy < RECENT_MS

    fun usedProxy() { lastProxy = System.currentTimeMillis() }

    fun set(list: Set<String>, at: Long = System.currentTimeMillis()) { blocked = list; updatedAt = at }

    /** One look at the public list; failures leave the last answer as it was. */
    suspend fun refresh(http: ServerHttp) {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                http.client.newCall(Request.Builder().url(LaLigaBlocks.LIST_URL).header("User-Agent", "Yomukai").build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.byteStream()?.use { it.readNBytes(MAX_LIST_BYTES) }?.toString(Charsets.UTF_8) else null
                }
            }.onFailure { log.debug("Lista de bloqueos: {}", it.message) }.getOrNull()
        } ?: return
        set(LaLigaBlocks.parse(text))
    }

    const val REFRESH_MS = 10 * 60_000L
    const val MATCH_BLOCKS = 10
    private const val RECENT_MS = 30 * 60_000L
    private const val MAX_LIST_BYTES = 256 * 1024
}

/** The admin's way out (OUTBOUND_PROXY) for sites this server cannot reach directly. */
object OutboundProxy {
    /** «socks5://tor:9050» or «http://proxy:3128»; anything else is ignored. */
    fun parse(raw: String?): Proxy? {
        val uri = raw?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        val type = when (uri.scheme?.lowercase()) { "socks5", "socks" -> Proxy.Type.SOCKS; "http" -> Proxy.Type.HTTP; else -> return null }
        val host = uri.host ?: return null
        if (uri.port !in 1..MAX_PORT) return null
        return Proxy(type, InetSocketAddress.createUnresolved(host, uri.port))
    }

    /** A name that resolves (here) only to public addresses: the proxy is never asked for anything at home. */
    fun publicHost(host: String): Boolean =
        runCatching { java.net.InetAddress.getAllByName(host).toList() }.getOrNull()?.let { all -> all.isNotEmpty() && all.all(PublicAddresses::isPublic) } == true

    private const val MAX_PORT = 65_535
}

/**
 * The app reaches sources itself; when the user's connection cannot (a block), it asks through here (F2.3). Only signed
 * in accounts, only GET, only the sites of this server's sources and their image hosts, only public addresses (the
 * shared client checks every address it connects to), bounded size, and counted like any request to a source.
 */
fun Route.laLigaRoutes(s: Services) {
    get("/bloqueos") {
        call.respond(mapOf("active" to LaLigaState.active, "blocked" to LaLigaState.blocked.size, "updatedAt" to LaLigaState.updatedAt, "notice" to LaLigaBlocks.NOTICE))
    }
    get("/relay") {
        if (call.user.isGuest) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Solo para cuentas"))
        val url = call.request.queryParameters["u"].orEmpty()
        if (!Relay.allowedUrl(url, s)) {
            return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Esa dirección no es de ninguna fuente"))
        }
        if (!Relay.withinRate(call.user.id)) return@get call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Demasiadas peticiones"))
        val request = Request.Builder().url(url).apply {
            Relay.FORWARDED.forEach { h -> call.request.headers["X-Relay-$h"]?.take(Relay.MAX_HEADER)?.let { header(h, it) } }
        }.build()
        val answer = withContext(Dispatchers.IO) {
            runCatching {
                s.http.client.newCall(request).execute().use { r ->
                    val body = r.body?.byteStream()?.use { it.readNBytes(Relay.MAX_BYTES + 1) } ?: ByteArray(0)
                    Triple(r.code, r.header("Content-Type") ?: "application/octet-stream", body)
                }
            }.getOrNull()
        } ?: return@get call.respond(HttpStatusCode.BadGateway, mapOf("error" to "La fuente tampoco responde desde el servidor"))
        if (answer.third.size > Relay.MAX_BYTES) return@get call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Demasiado grande"))
        // Never rendered as a page of this site: whatever the source answers is only data for the app.
        call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=600")
        call.response.header("X-Relay-Status", answer.first.toString())
        call.response.header("X-Relay-Type", answer.second.take(Relay.MAX_HEADER))
        // Pages go out as plain bytes (their real type is in X-Relay-Type for the app), never as HTML of this site.
        val type = if (answer.second.contains("html", true)) ContentType.Application.OctetStream
        else runCatching { ContentType.parse(answer.second) }.getOrDefault(ContentType.Application.OctetStream)
        call.respondBytes(answer.third, type, HttpStatusCode.fromValue(answer.first.coerceIn(MIN_STATUS, MAX_STATUS)))
    }
}

private const val MIN_STATUS = 200
private const val MAX_STATUS = 599

object Relay {
    val FORWARDED = listOf("Referer", "Origin", "Accept", "Range")
    const val MAX_HEADER = 500
    const val MAX_BYTES = 25 * 1024 * 1024
    private const val PER_MINUTE = 600
    private val counts = ConcurrentHashMap<Long, Pair<Long, Int>>()

    /** An http(s) address of one of this server's sources (see [allowed]). */
    fun allowedUrl(url: String, s: Services): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme in setOf("https", "http") && allowed(host, s)
    }

    /** The domains of this server's sources (with the ones they moved to) and the image hosts learnt from them. */
    fun allowed(host: String, s: Services): Boolean {
        val domain = com.nelson.yomukai.data.network.TorRouting.registrableDomain(host)
        val sites = s.sources.all.mapNotNull { runCatching { URI(it.baseUrl).host }.getOrNull() } + s.http.hostAliases.values
        // A source's own site with its subdomains; an image host learnt from it only exactly (shared CDNs serve anyone).
        return sites.any { com.nelson.yomukai.data.network.TorRouting.registrableDomain(it) == domain } || host in CoverHosts.known()
    }

    fun withinRate(user: Long, now: Long = System.currentTimeMillis()): Boolean {
        val minute = now / 60_000
        val (at, n) = counts.compute(user) { _, old -> if (old == null || old.first != minute) minute to 1 else old.first to old.second + 1 }!!
        return at == minute && n <= PER_MINUTE
    }
}
