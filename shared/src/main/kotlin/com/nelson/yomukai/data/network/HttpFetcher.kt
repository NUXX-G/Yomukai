package com.nelson.yomukai.data.network

import java.io.IOException

/**
 * What the sources and the metadata clients need from the network, and nothing else. The Android
 * app implements it with its OkHttp stack (DNS over HTTPS, Tor, the WebView for Cloudflare); the web
 * server with its own client. Keeping it this small is what lets one copy of every source run in both.
 */
interface HttpFetcher {
    /**
     * GETs [url] and returns the body as text. With [cloudflareFallback], a page protected by an
     * anti-bot challenge may be fetched through a real browser engine when the platform has one.
     */
    suspend fun getString(url: String, headers: Map<String, String> = emptyMap(), cloudflareFallback: Boolean = true): String

    /**
     * Like [getString], also returning the address the answer finally came from after redirects, so a source can
     * notice that its site moved. Fetchers that cannot tell return the requested address.
     */
    suspend fun getStringWithUrl(url: String, headers: Map<String, String> = emptyMap()): Pair<String, String> = getString(url, headers) to url

    /** POSTs with an empty body; some sites answer their AJAX endpoints only to POST. */
    suspend fun postString(url: String, headers: Map<String, String> = emptyMap()): String

    /** POSTs a JSON [body] and returns the answer as text. */
    suspend fun postJson(url: String, body: String): String

    /** Fetches [url] as an image would be; returns its content type or throws when it is not an image. */
    suspend fun checkImage(url: String, headers: Map<String, String>): String
}

class HttpException(val code: Int, url: String) : IOException("HTTP $code en $url")
