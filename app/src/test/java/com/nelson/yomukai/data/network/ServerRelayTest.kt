package com.nelson.yomukai.data.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

/** F2.3: a source this phone cannot reach is asked through the server's relay, with the user told once. */
class ServerRelayTest {
    @Test
    fun aBlockedSourceGoesThroughTheServer() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        var asked = ""
        var referer = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/relay") { ex ->
                asked = ex.requestURI.rawQuery.orEmpty()
                referer = ex.requestHeaders.getFirst("X-Relay-Referer").orEmpty()
                val body = "<html>capítulos</html>".toByteArray()
                ex.responseHeaders.add("X-Relay-Status", "200")
                ex.responseHeaders.add("X-Relay-Type", "text/html; charset=utf-8")
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val relay = ServerRelay().apply { target = { "http://localhost:${server.address.port}/".toHttpUrl() to "yomukai_session=abc" } }
            val client = OkHttpClient.Builder().addInterceptor(relay.interceptor).build()
            val told = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { relay.notices.first() } }
            // Nothing listens on port 1: the connection fails like a blocked site.
            val response = client.newCall(Request.Builder().url("http://127.0.0.1:1/manga/1").header("Referer", "http://127.0.0.1:1/").build()).execute()
            assertEquals("<html>capítulos</html>", response.body?.string())
            assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
            assertEquals(true, asked.contains("u=http%3A%2F%2F127.0.0.1%3A1%2Fmanga%2F1"))
            assertEquals("http://127.0.0.1:1/", referer)
            assertEquals("127.0.0.1", told.await())
            Unit
        } finally {
            server.stop(0)
        }
    }

    @Test(expected = java.io.IOException::class)
    fun withoutAServerNothingChanges() {
        val client = OkHttpClient.Builder().addInterceptor(ServerRelay().interceptor).build()
        client.newCall(Request.Builder().url("http://127.0.0.1:1/x").build()).execute()
    }
}
