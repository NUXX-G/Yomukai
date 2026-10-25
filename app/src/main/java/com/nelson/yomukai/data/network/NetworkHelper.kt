package com.nelson.yomukai.data.network

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.nelson.yomukai.data.prefs.NetworkSettings
import com.nelson.yomukai.data.prefs.ProxyType
import com.nelson.yomukai.data.source.CloudflareResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.io.IOException
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

/** The connection never reached the site (DNS or IP blocking by the ISP, or the site is down). */
class SourceUnreachableException(host: String, cause: Throwable) : IOException(
    "No se pudo conectar con $host. Puede estar caído o bloqueado por tu operador; " +
        "activa DNS seguro o configura un proxy en Ajustes › Red.",
    cause
)

/**
 * Single OkHttp stack shared by every scraper, the download manager and Coil, so cookies
 * obtained by solving a Cloudflare challenge in a WebView are reused everywhere. The client
 * is rebuilt whenever the user changes DNS-over-HTTPS or proxy settings.
 */
class NetworkHelper(
    private val context: Context,
    settings: StateFlow<NetworkSettings>,
    scope: CoroutineScope
) {
    private val cache = Cache(File(context.cacheDir, "http_cache"), 25L * 1024 * 1024)
    private val cookieJar = WebViewCookieJar()

    @Volatile
    var client: OkHttpClient = buildClient(settings.value)
        private set

    /** Stable factory for long-lived consumers (Coil) that always delegates to the current client. */
    val callFactory = Call.Factory { request -> client.newCall(request) }

    init {
        scope.launch {
            settings.collect { client = buildClient(it) }
        }
    }

    private fun buildClient(settings: NetworkSettings): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(cookieJar)
            .cache(cache)
            .addInterceptor(BrowserHeadersInterceptor())
            .addInterceptor(RateLimitInterceptor(minIntervalMs = 300))
            .addInterceptor(RetryInterceptor(maxRetries = 2))

        if (settings.proxyType != ProxyType.NONE && settings.proxyHost.isNotBlank()) {
            val type = if (settings.proxyType == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
            // Unresolved address: with SOCKS the proxy (e.g. Tor) resolves hostnames itself.
            builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(settings.proxyHost, settings.proxyPort)))
        } else if (settings.dnsOverHttps) {
            builder.dns(dohWithFallback())
        }
        return builder.build()
    }

    /** Cloudflare DoH (bootstrapped by IP so it works even if system DNS is filtered), then system DNS. */
    private fun dohWithFallback(): Dns {
        val bootstrap = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        val doh = DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
                InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))
            )
            .build()
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = try {
                doh.lookup(hostname)
            } catch (e: UnknownHostException) {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    /**
     * GETs [url] as text. When the response is an anti-bot page (or the request fails outright)
     * and [cloudflareFallback] is set, the page is rendered in a headless WebView instead; the
     * cookies it earns are then visible to OkHttp for following requests.
     */
    suspend fun getString(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cloudflareFallback: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val host = request.url.host

        val failure: IOException = try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val protection = detectProtection(response.code, body)
                when {
                    protection == null && response.isSuccessful -> return@withContext body
                    protection != null -> SourceBlockedException(host, protection)
                    else -> HttpException(response.code, url)
                }
            }
        } catch (e: UnknownHostException) {
            SourceUnreachableException(host, e)
        } catch (e: SocketTimeoutException) {
            SourceUnreachableException(host, e)
        } catch (e: SSLException) {
            SourceUnreachableException(host, e)
        } catch (e: IOException) {
            e
        }

        if (cloudflareFallback && failure !is HttpException) {
            Log.d(TAG, "Resolviendo $url vía WebView (${failure.message})")
            val resolved = CloudflareResolver(context).resolveHtmlAndCookies(url)
            val html = resolved?.first.orEmpty()
            if (html.length > 500 && detectProtection(200, html) == null) {
                return@withContext html
            }
        }
        throw failure
    }

    suspend fun postString(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .post(ByteArray(0).toRequestBody(null))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            detectProtection(response.code, body)?.let { throw SourceBlockedException(request.url.host, it) }
            if (!response.isSuccessful) throw HttpException(response.code, url)
            body
        }
    }

    companion object {
        const val TAG = "YomukaiNetwork"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        /** Recognizes the block pages of common anti-bot/firewall products; null when the page looks real. */
        fun detectProtection(code: Int, body: String): String? {
            val head = body.take(6000)
            return when {
                "cf-browser-verification" in head || "<title>Just a moment...</title>" in head ||
                    ("challenge-platform" in head && "Just a moment" in head) -> "Cloudflare"
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

/** Retries transient failures (I/O errors, 429, 502, 504) with linear backoff. */
private class RetryInterceptor(private val maxRetries: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            try {
                val response = chain.proceed(chain.request())
                if (response.code !in RETRYABLE || attempt >= maxRetries) return response
                val retryAfter = response.header("Retry-After")?.toLongOrNull()?.coerceAtMost(10)
                response.close()
                Thread.sleep(retryAfter?.times(1000) ?: (700L * (attempt + 1)))
            } catch (e: UnknownHostException) {
                throw e // retrying a DNS failure never helps
            } catch (e: IOException) {
                if (attempt >= maxRetries || chain.call().isCanceled()) throw e
                Thread.sleep(700L * (attempt + 1))
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
