package com.nelson.yomukai.data.network

import android.content.Context
import android.util.Log
import android.util.LruCache
import android.webkit.CookieManager
import com.nelson.yomukai.data.prefs.NetworkSettings
import com.nelson.yomukai.data.prefs.ProxyType
import com.nelson.yomukai.data.prefs.TorMode
import com.nelson.yomukai.data.source.CloudflareResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class HttpException(val code: Int, url: String) : IOException("HTTP $code en $url")

/** The site answered with a firewall/anti-bot page instead of content. */
class SourceBlockedException(host: String, protection: String) : IOException(
    "$host bloqueó la conexión ($protection). Abre el sitio en el navegador una vez, " +
        "o prueba con DNS seguro o un proxy en Ajustes › Red."
)

/** Tor is on but not connected yet: the request is refused and must not be retried any other way. */
class TorNotReadyException(message: String) : IOException(message)

/** The connection never reached the site (DNS or IP blocking by the ISP, or the site is down). */
class SourceUnreachableException(host: String, cause: Throwable) : IOException(
    "No se pudo conectar con $host. Puede estar caído o bloqueado por tu operador; " +
        "activa DNS seguro o configura un proxy en Ajustes › Red.",
    cause
)

/**
 * Single OkHttp stack shared by every scraper, the download manager and Coil, so cookies
 * obtained by solving a Cloudflare challenge in a WebView are reused everywhere. The clients
 * are rebuilt whenever the user changes DNS-over-HTTPS or proxy settings.
 *
 * [client] carries images, downloads and video: no rate limit and no total time limit, because a
 * reader needs many pages at once and a video takes as long as it takes. Page and API requests go
 * through [scrapeClient], which is spaced out per host and has a hard deadline.
 */
class NetworkHelper(
    private val context: Context,
    private val settings: StateFlow<NetworkSettings>,
    private val tor: StateFlow<TorState>,
    scope: CoroutineScope
) {
    private val cache = Cache(File(context.cacheDir, "http_cache"), 25L * 1024 * 1024)
    private val cookieJar = WebViewCookieJar()
    private val connectionPool = ConnectionPool(16, 2, TimeUnit.MINUTES)

    /** Requests already answered a moment ago: details and chapters often read the very same page. */
    private val recent = object : LruCache<String, Recent>(RECENT_MAX_CHARS) {
        override fun sizeOf(key: String, value: Recent) = value.body.length.coerceAtLeast(1)
    }
    private val sameRequestLocks = List(LOCK_STRIPES) { Mutex() }

    /** Rendering in a WebView is expensive: a few at a time, one per site. */
    private val webViewSlots = Semaphore(MAX_WEBVIEWS)
    private val webViewHostLocks = ConcurrentHashMap<String, Mutex>()

    /** True once the process-wide WebView proxy is really sending traffic through Tor; set by the container. */
    @Volatile
    var webViewOnTor: () -> Boolean = { false }

    /** False while the WebView must not load anything yet (a wanted Tor proxy is not applied); set by the container. */
    @Volatile
    var webViewReady: () -> Boolean = { true }

    /**
     * Resolves source ids to the domains that must skip Tor; set once the SourceManager exists.
     * Declared before [client]: the first [buildClient] call reads it when Tor was left enabled.
     */
    @Volatile
    var domainsOfSources: (Set<String>) -> Set<String> = { emptySet() }

    @Volatile
    var client: OkHttpClient = buildClient(settings.value, tor.value)
        private set

    @Volatile
    private var scrapeClient: OkHttpClient = scrapeClientFor(client, torRouted(settings.value))

    @Volatile
    private var imageClient: OkHttpClient = imageClientFor(client)

    /** Streaming and video downloads: gives up quickly on a dead connection attempt and tries again. */
    @Volatile
    var videoClient: OkHttpClient = videoClientFor(client, torRouted(settings.value))
        private set

    /** Stable factory for long-lived consumers (Media3) that always delegates to the current client. */
    val callFactory = Call.Factory { request -> client.newCall(request) }

    val videoCallFactory = Call.Factory { request -> videoClient.newCall(request) }

    /** Same for the image loader, which must never store a web page as if it were the image. */
    val imageCallFactory = Call.Factory { request -> imageClient.newCall(request) }

    init {
        scope.launch {
            combine(settings, tor.distinctUntilChangedBy { Triple(it.phase, it.socksPort, it.mode) }) { s, t -> s to t }.collect { (s, t) ->
                client = buildClient(s, t)
                scrapeClient = scrapeClientFor(client, torRouted(s))
                imageClient = imageClientFor(client)
                videoClient = videoClientFor(client, torRouted(s))
            }
        }
    }

    private fun videoClientFor(base: OkHttpClient, viaTor: Boolean): OkHttpClient =
        if (viaTor) base // circuits take long to build: a short connect timeout would only fail them
        else base.newBuilder()
            .connectTimeout(VIDEO_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(ConnectRetryInterceptor(VIDEO_CONNECT_ATTEMPTS))
            .build()

    private fun imageClientFor(base: OkHttpClient): OkHttpClient =
        base.newBuilder().addInterceptor(RejectWebPagesInterceptor()).build()

    private fun torRouted(settings: NetworkSettings) = settings.torMode != TorMode.OFF && settings.torRoutesApp

    private fun scrapeClientFor(base: OkHttpClient, viaTor: Boolean): OkHttpClient =
        base.newBuilder()
            .cache(cache)
            .callTimeout(if (viaTor) TOR_CALL_TIMEOUT_S else CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(RateLimitInterceptor(minIntervalMs = 200))
            .build()

    private fun buildClient(settings: NetworkSettings, torState: TorState): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(if (torRouted(settings)) TOR_CONNECT_TIMEOUT_S else CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(if (torRouted(settings)) TOR_READ_TIMEOUT_S else READ_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(cookieJar)
            .connectionPool(connectionPool)
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 12 })
            .addInterceptor(BrowserHeadersInterceptor())

        if (torRouted(settings)) {
            val direct = domainsOfSources(settings.torExcludedSources)
            if (torState.isConnected) {
                builder.proxySelector(TorProxySelector(torState.socksPort, direct))
            } else {
                // Fail closed: never fall back to a clear connection while Tor is not ready.
                builder.addInterceptor(TorGuardInterceptor(direct, torState))
            }
            builder.dns(ResilientDns(if (settings.dnsOverHttps) doh() else null))
        } else if (settings.proxyType != ProxyType.NONE && settings.proxyHost.isNotBlank()) {
            val type = if (settings.proxyType == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
            // Unresolved address: with SOCKS the proxy (e.g. Tor) resolves hostnames itself.
            builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(settings.proxyHost, settings.proxyPort)))
        } else {
            builder.dns(ResilientDns(if (settings.dnsOverHttps) doh() else null))
        }
        return builder.addInterceptor(RetryInterceptor(maxRetries = 1)).build()
    }

    /** Cloudflare DoH, bootstrapped by IP so it works even if system DNS is filtered. */
    private fun doh(): Dns = DnsOverHttps.Builder()
        .client(
            OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(3, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
        )
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
            InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))
        )
        .build()

    /** The WebView has its own connection, so with Tor on it may only be used when it goes through Tor too. */
    private fun webViewAllowed(): Boolean =
        TorRouting.webViewFallbackAllowed(torRouted(settings.value), webViewOnTor(), webViewReady())

    /**
     * GETs [url] as text. When the response is an anti-bot page (or the request fails outright)
     * and [cloudflareFallback] is set, the page is rendered in a headless WebView instead; the
     * cookies it earns are then visible to OkHttp for following requests.
     *
     * Identical requests made at the same time, or a moment apart, share one response.
     */
    suspend fun getString(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cloudflareFallback: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val key = url + "|" + headers.hashCode()
        sameRequestLocks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES].withLock {
            recent.get(key)?.takeIf { System.currentTimeMillis() - it.at < RECENT_MS }?.let { return@withContext it.body }
            fetchString(url, headers, cloudflareFallback).also { recent.put(key, Recent(it, System.currentTimeMillis())) }
        }
    }

    private suspend fun fetchString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean): String {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val host = request.url.host

        val failure: IOException = try {
            scrapeClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val protection = detectProtection(response.code, body, response.header("cf-mitigated"))
                when {
                    protection == null && response.isSuccessful -> return body
                    protection != null -> SourceBlockedException(host, protection)
                    else -> HttpException(response.code, url)
                }
            }
        } catch (e: TorNotReadyException) {
            throw e
        } catch (e: UnknownHostException) {
            throw SourceUnreachableException(host, e) // no address: a browser would not find it either
        } catch (e: SocketTimeoutException) {
            throw SourceUnreachableException(host, e) // the site is not answering: do not wait all over again
        } catch (e: SSLException) {
            SourceUnreachableException(host, e)
        } catch (e: IOException) {
            e
        }

        if (cloudflareFallback && failure !is HttpException && webViewAllowed()) {
            renderInWebView(url, host)?.let { return it }
        }
        throw failure
    }

    private suspend fun renderInWebView(url: String, host: String): String? =
        webViewHostLocks.getOrPut(host) { Mutex() }.withLock {
            webViewSlots.withPermit {
                Log.d(TAG, "Resolviendo $url vía WebView")
                val html = CloudflareResolver(context).resolveHtmlAndCookies(url)?.first.orEmpty()
                html.takeIf { it.length > 200 && detectProtection(200, it) == null }?.let(::unwrapPlainText)
            }
        }

    /** Fetches [url] the way the image loader does; returns a short description or throws when it is not an image. */
    suspend fun checkImage(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, url)
            val type = response.header("Content-Type").orEmpty()
            if (!type.startsWith("image/")) throw IOException("No es una imagen: ${type.ifBlank { "sin tipo" }} en ${response.request.url.host}")
            type
        }
    }

    /** POSTs a JSON [body] and returns the answer as text. */
    suspend fun postJson(url: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()
        scrapeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, url)
            response.body?.string().orEmpty()
        }
    }

    suspend fun postString(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .post(ByteArray(0).toRequestBody(null))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        scrapeClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            detectProtection(response.code, body, response.header("cf-mitigated"))?.let { throw SourceBlockedException(request.url.host, it) }
            if (!response.isSuccessful) throw HttpException(response.code, url)
            body
        }
    }

    companion object {
        const val TAG = "YomukaiNetwork"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 20L
        private const val CALL_TIMEOUT_S = 30L
        private const val TOR_CONNECT_TIMEOUT_S = 30L
        private const val TOR_READ_TIMEOUT_S = 45L
        private const val TOR_CALL_TIMEOUT_S = 75L
        private const val VIDEO_CONNECT_TIMEOUT_S = 3L
        private const val VIDEO_CONNECT_ATTEMPTS = 6
        private const val RECENT_MS = 15_000L
        private const val RECENT_MAX_CHARS = 8 * 1024 * 1024
        private const val LOCK_STRIPES = 64
        private const val MAX_WEBVIEWS = 2

        private class Recent(val body: String, val at: Long)

        /** A challenge in progress. The `challenge-platform` script alone proves nothing: Cloudflare adds it to normal pages too. */
        fun isCloudflareChallenge(html: String): Boolean {
            val head = html.take(6000)
            return "cf-browser-verification" in head || "_cf_chl_opt" in head ||
                "<title>Just a moment" in head || "<title>Un momento" in head
        }

        /** A WebView shows JSON or plain text wrapped in `<pre>`; the callers want the original text. */
        fun unwrapPlainText(html: String): String {
            val body = Jsoup.parse(html).body()
            val only = body.children().singleOrNull()
            return if (only?.tagName() == "pre" && body.ownText().isBlank()) only.wholeText() else html
        }

        /** Recognizes the block pages of common anti-bot/firewall products; null when the page looks real. */
        fun detectProtection(code: Int, body: String, cfMitigated: String? = null): String? {
            val head = body.take(6000)
            return when {
                cfMitigated != null || isCloudflareChallenge(body) -> "Cloudflare"
                "Attention Required! | Cloudflare" in head || "Sorry, you have been blocked" in head -> "Cloudflare"
                "Tu acceso a este sitio ha sido limitado" in head ||
                    "Your access to this site has been limited" in head -> "Wordfence"
                "DDoS-Guard" in head && code in setOf(403, 503) -> "DDoS-Guard"
                code == 403 && body.length < 2000 && "<html" in body.lowercase() -> "acceso denegado"
                else -> null
            }
        }
    }
}

/** Sends the headers a real mobile Chrome sends; several sites reject bare clients. */
private class BrowserHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
        fun default(name: String, value: String) {
            if (original.header(name) == null) builder.header(name, value)
        }
        default("User-Agent", NetworkHelper.USER_AGENT)
        default("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        default("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        return chain.proceed(builder.build())
    }
}

/**
 * Some video hosts drop a large share of connection attempts, yet answer at once when one gets
 * through. Several short tries reach them far sooner than one long wait.
 */
private class ConnectRetryInterceptor(private val attempts: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 1
        while (true) {
            try {
                return chain.proceed(chain.request())
            } catch (e: IOException) {
                val failedToConnect = e is ConnectException || (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
                if (!failedToConnect || attempt >= attempts || chain.call().isCanceled()) throw e
                attempt++
            }
        }
    }
}

/**
 * A block or challenge page often arrives as a plain 200. Turning it into an error keeps the image
 * loader from saving it in its disk cache, where it would stay broken even after the site recovers.
 */
private class RejectWebPagesInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val isPage = response.header("Content-Type")?.startsWith("text/html", ignoreCase = true) == true
        return if (response.isSuccessful && isPage) response.newBuilder().code(415).message("No es una imagen").build() else response
    }
}

/** Spaces out requests to the same host so we never hammer a site. */
private class RateLimitInterceptor(private val minIntervalMs: Long) : Interceptor {
    private val nextSlot = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val wait: Long
        synchronized(nextSlot) {
            val now = System.currentTimeMillis()
            val slot = maxOf(now, nextSlot[host] ?: 0L)
            nextSlot[host] = slot + minIntervalMs
            wait = slot - now
        }
        if (wait > 0) Thread.sleep(wait)
        return chain.proceed(chain.request())
    }
}

/** Retries transient failures (connection errors, 429, 502, 504) with linear backoff. */
private class RetryInterceptor(private val maxRetries: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            try {
                val response = chain.proceed(chain.request())
                if (response.code !in RETRYABLE || attempt >= maxRetries) return response
                val retryAfter = response.header("Retry-After")?.toLongOrNull()?.coerceAtMost(10)
                response.close()
                Thread.sleep(retryAfter?.times(1000) ?: (400L * (attempt + 1)))
            } catch (e: UnknownHostException) {
                throw e // retrying a DNS failure never helps
            } catch (e: SocketTimeoutException) {
                throw e // it already waited the whole timeout once
            } catch (e: TorNotReadyException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= maxRetries || chain.call().isCanceled()) throw e
                Thread.sleep(400L * (attempt + 1))
            }
            attempt++
        }
    }

    companion object {
        val RETRYABLE = setOf(429, 502, 504)
    }
}

/** Bridges OkHttp and the WebView cookie store (needed for Cloudflare clearance cookies). */
private class WebViewCookieJar : CookieJar {
    private val manager: CookieManager? by lazy {
        runCatching { CookieManager.getInstance() }.getOrNull()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = manager ?: return
        cookies.forEach { m.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = manager?.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

/** While Tor is still connecting, refuses requests that should go through it instead of leaking them. */
private class TorGuardInterceptor(private val directDomains: Set<String>, private val state: TorState) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (TorRouting.bypasses(host, directDomains) || TorRouting.isLoopback(host)) return chain.proceed(chain.request())
        throw TorNotReadyException(
            when (state.phase) {
                TorPhase.ERROR -> state.error ?: "Tor no está disponible"
                else -> "Tor aún se está conectando. Espera unos segundos o desactívalo en Ajustes › Red."
            }
        )
    }
}
