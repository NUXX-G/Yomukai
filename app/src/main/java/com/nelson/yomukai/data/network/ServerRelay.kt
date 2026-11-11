package com.nelson.yomukai.data.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * When this phone cannot reach a source (LaLiga's blocks during matches: the operator resets the connection or answers
 * with its own certificate), the same request goes through the connected server's relay instead (F2.3), and the user
 * is told once why it is slower. Without a server, or for anything that is not a plain GET, nothing changes.
 */
class ServerRelay {
    /** The connected server and its session cookie; null while not connected. Set by the app container. */
    @Volatile var target: (() -> Pair<HttpUrl, String>?)? = null

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Hosts that just went through the server: the screen shows [LaLigaBlocks.NOTICE] once for each. */
    val notices: SharedFlow<String> = _notices

    private val told = ConcurrentHashMap<String, Long>()

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.method != "GET") return@Interceptor chain.proceed(request)
        try {
            chain.proceed(request)
        } catch (e: IOException) {
            val (server, cookie) = target?.invoke() ?: throw e
            if (!LaLigaBlocks.looksBlocked(e) || request.url.host == server.host) throw e
            val relayed = relayRequest(request, server, cookie)
            val response = chain.proceed(relayed)
            if (!response.isSuccessful && response.header(STATUS) == null) { response.close(); throw e }
            tell(request.url.host)
            restore(response)
        }
    }

    /** The relay address for [request], with the headers the source needs passed as X-Relay-*. */
    fun relayRequest(request: okhttp3.Request, server: HttpUrl, cookie: String): okhttp3.Request {
        val url = server.newBuilder().encodedPath("/api/relay").query(null).addQueryParameter("u", request.url.toString()).build()
        return okhttp3.Request.Builder().url(url).header("Cookie", cookie).apply {
            FORWARDED.forEach { h -> request.header(h)?.let { header("X-Relay-$h", it) } }
        }.build()
    }

    /** The source's own type comes back (pages travel as plain bytes from the relay). */
    private fun restore(response: Response): Response {
        val type = response.header(TYPE)?.toMediaTypeOrNull() ?: return response
        val bytes = response.body?.bytes() ?: ByteArray(0)
        return response.newBuilder().body(bytes.toResponseBody(type)).header("Content-Type", type.toString()).build()
    }

    private fun tell(host: String) {
        val now = System.currentTimeMillis()
        if ((told[host] ?: 0L) > now - TELL_AGAIN_MS) return
        told[host] = now
        _notices.tryEmit(host)
    }

    companion object {
        private val FORWARDED = listOf("Referer", "Origin", "Accept", "Range")
        private const val STATUS = "X-Relay-Status"
        private const val TYPE = "X-Relay-Type"
        private const val TELL_AGAIN_MS = 30 * 60_000L
    }
}
