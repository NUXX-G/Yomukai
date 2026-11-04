package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpException
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.network.WebProtection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The site answered with an anti-bot page and no way around it was available. */
class SourceBlockedException(host: String, protection: String) : IOException(
    "$host bloqueó al servidor ($protection). Si usas Docker, activa FlareSolverr."
)

/** A site or a proxied link pointed at the server's own network. */
class BlockedAddressException(host: String) : IOException("Dirección no permitida: $host")

/**
 * Only public internet addresses may be reached. Anyone can make the server fetch a link (a
 * guest opening a chapter, a playlist naming its segments), so loopback, private, link-local and
 * multicast ranges are refused: the VPS, its Docker network and its cloud metadata stay out of reach.
 */
object PublicAddresses {
    fun isPublic(address: java.net.InetAddress): Boolean = !(
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress ||
            // IPv6 unique local (fc00::/7) and IPv4 carrier-grade NAT (100.64.0.0/10).
            (address is java.net.Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc) ||
            (address is java.net.Inet4Address && address.address[0].toInt() == 100 && (address.address[1].toInt() and 0xc0) == 64)
        )

    /** DNS that drops non-public answers. */
    val dns: okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            okhttp3.Dns.SYSTEM.lookup(hostname).filter(::isPublic).ifEmpty { throw BlockedAddressException(hostname) }
    }

    private val ipLiteral = Regex("""^[0-9.]+$|:""")

    /** Refuses an address written as an IP before any connection is even tried. */
    val literalGuard = okhttp3.Interceptor { chain ->
        val host = chain.request().url.host
        if (ipLiteral.containsMatchIn(host) && !isPublic(java.net.InetAddress.getByName(host))) throw BlockedAddressException(host)
        chain.proceed(chain.request())
    }

    /** Checks the address actually connected to, on every hop: IP literals and redirects never go through DNS. */
    val guard = okhttp3.Interceptor { chain ->
        val connected = chain.connection()?.socket()?.inetAddress
        if (connected != null && !isPublic(connected)) throw BlockedAddressException(chain.request().url.host)
        chain.proceed(chain.request())
    }
}

/** Keeps cookies per host in memory, so sessions earned once (Cloudflare clearance) are reused. */
private class MemoryCookieJar : CookieJar {
    private val byHost = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = byHost.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            list.removeAll { old -> cookies.any { it.name == old.name } }
            list += cookies
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return byHost.entries.filter { (host, _) -> url.host == host || url.host.endsWith(".$host") }
            .flatMap { (_, list) -> synchronized(list) { list.filter { it.expiresAt > now && it.matches(url) } } }
    }

    fun add(host: String, cookies: List<Cookie>) {
        val url = "https://$host/".toHttpUrlOrNull() ?: return
        saveFromResponse(url, cookies)
    }
}

/**
 * The server's network: one OkHttp client for scraping and one, without overall timeout, for
 * streaming media. Identical requests made moments apart share one answer, so ten people opening
 * the same title cost one request to the site.
 */
class ServerHttp(private val flareSolverrUrl: String?) : HttpFetcher {
    private val log = LoggerFactory.getLogger("YomukaiHttp")
    private val cookies = MemoryCookieJar()
    private val pool = ConnectionPool(64, 5, TimeUnit.MINUTES)
    private val dispatcher = Dispatcher().apply { maxRequests = 256; maxRequestsPerHost = 16 }

    /** Set by FlareSolverr: Cloudflare ties its clearance cookie to the browser's user agent. */
    private val agentByHost = ConcurrentHashMap<String, String>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(pool)
        .dispatcher(dispatcher)
        .cookieJar(cookies)
        .dns(PublicAddresses.dns)
        .addInterceptor(PublicAddresses.literalGuard)
        .addNetworkInterceptor(PublicAddresses.guard)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    /** For video and images: long reads, no overall limit. */
    val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** FlareSolverr lives in the server's own Docker network: the only client allowed to reach it. */
    private val solverClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()

    private class Recent(val body: String, val at: Long)

    private val recent = ConcurrentHashMap<String, Recent>()
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    /**
     * Hosts FlareSolverr may open: the sources' own sites. Its browser follows redirects and resolves
     * names on its own, out of reach of [PublicAddresses], so it is never pointed at anything else.
     */
    @Volatile var solverHosts: Set<String> = emptySet()

    private fun solverAllows(host: String): Boolean {
        val h = host.lowercase()
        return solverHosts.any { h == it || h.endsWith(".$it") }
    }

    fun userAgentFor(host: String): String = agentByHost[host] ?: WebProtection.USER_AGENT

    private fun Request.Builder.browserHeaders(url: String, headers: Map<String, String>): Request.Builder {
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        header("User-Agent", headers["User-Agent"] ?: userAgentFor(host))
        header("Accept", headers["Accept"] ?: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        header("Accept-Language", headers["Accept-Language"] ?: "es-ES,es;q=0.9,en;q=0.8")
        headers.filterKeys { it !in setOf("User-Agent", "Accept", "Accept-Language") }.forEach { (k, v) -> header(k, v) }
        return this
    }

    override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean): String =
        withContext(Dispatchers.IO) {
            val key = url + "|" + headers.hashCode()
            locks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES].withLock {
                recent[key]?.takeIf { System.currentTimeMillis() - it.at < RECENT_MS }?.let { return@withContext it.body }
                val body = fetch(url, headers, cloudflareFallback)
                if (body.length < RECENT_MAX_CHARS) recent[key] = Recent(body, System.currentTimeMillis())
                if (recent.size > RECENT_MAX_ENTRIES) trimRecent()
                body
            }
        }

    private fun trimRecent() {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { now - it.value.at > RECENT_MS }
    }

    private suspend fun fetch(url: String, headers: Map<String, String>, cloudflareFallback: Boolean): String {
        val request = Request.Builder().url(url).browserHeaders(url, headers).build()
        val (code, body, mitigated) = client.newCall(request).await().use { response ->
            Triple(response.code, response.body?.string().orEmpty(), response.header("cf-mitigated"))
        }
        val protection = WebProtection.detectProtection(code, body, mitigated)
        if (protection != null) {
            if (cloudflareFallback && protection == "Cloudflare" && flareSolverrUrl != null && solverAllows(request.url.host)) {
                return solve(url)
            }
            throw SourceBlockedException(request.url.host, protection)
        }
        if (code !in 200..299) throw HttpException(code, url)
        return body
    }

    /** Lets FlareSolverr's real browser pass the challenge, then keeps its cookies for the next requests. */
    private suspend fun solve(url: String): String {
        val endpoint = flareSolverrUrl ?: throw IOException("FlareSolverr no configurado")
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        if (!solverAllows(host)) throw SourceBlockedException(host, "Cloudflare")
        // The name must still point at the internet right now (no rebinding to the server's network).
        PublicAddresses.dns.lookup(host)
        val payload = JsonObject().apply {
            addProperty("cmd", "request.get")
            addProperty("url", url)
            addProperty("maxTimeout", 60_000)
        }
        val request = Request.Builder().url("$endpoint/v1")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val answer = solverClient.newCall(request).execute().use { it.body?.string().orEmpty() }
        val solution = runCatching { JsonParser.parseString(answer).asJsonObject.getAsJsonObject("solution") }.getOrNull()
            ?: throw IOException("FlareSolverr no pudo abrir $url")
        // A redirect may have taken the browser elsewhere: only an answer from an allowed site counts.
        val finalHost = solution.get("url")?.asString?.toHttpUrlOrNull()?.host ?: host
        if (!solverAllows(finalHost)) throw BlockedAddressException(finalHost)
        solution.get("userAgent")?.asString?.let { agentByHost[host] = it }
        val solved = solution.getAsJsonArray("cookies")?.mapNotNull { element ->
            val o = element.asJsonObject
            Cookie.Builder()
                .name(o.get("name")?.asString ?: return@mapNotNull null)
                .value(o.get("value")?.asString ?: return@mapNotNull null)
                .domain((o.get("domain")?.asString ?: host).trimStart('.'))
                .path(o.get("path")?.asString ?: "/")
                .expiresAt(System.currentTimeMillis() + COOKIE_LIFETIME_MS)
                .build()
        }.orEmpty()
        cookies.add(host, solved)
        log.info("FlareSolverr resolvió {} ({} cookies)", host, solved.size)
        return WebProtection.unwrapPlainText(solution.get("response")?.asString.orEmpty())
    }

    override suspend fun postString(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).browserHeaders(url, headers).build()
        client.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            WebProtection.detectProtection(response.code, body, response.header("cf-mitigated"))
                ?.let { throw SourceBlockedException(request.url.host, it) }
            if (!response.isSuccessful) throw HttpException(response.code, url)
            body
        }
    }

    override suspend fun postJson(url: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("User-Agent", WebProtection.USER_AGENT)
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, url)
            response.body?.string().orEmpty()
        }
    }

    override suspend fun checkImage(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).browserHeaders(url, headers).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, url)
            val type = response.header("Content-Type").orEmpty()
            if (!type.startsWith("image/")) throw IOException("No es una imagen: $type")
            type
        }
    }

    private companion object {
        const val LOCK_STRIPES = 64
        const val RECENT_MS = 20_000L
        const val RECENT_MAX_CHARS = 4 * 1024 * 1024
        const val RECENT_MAX_ENTRIES = 512
        const val COOKIE_LIFETIME_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * Runs the call so that cancelling the coroutine (a timeout, a closed page) also cancels the request.
 * A blocking execute() ignores timeouts set with withTimeout and holds on until OkHttp's own limits.
 */
suspend fun okhttp3.Call.await(): okhttp3.Response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    enqueue(object : okhttp3.Callback {
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
