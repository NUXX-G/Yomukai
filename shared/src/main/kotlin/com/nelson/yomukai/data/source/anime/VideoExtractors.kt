package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.domain.model.VideoSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Turns web-player embeds into direct streams ExoPlayer can play. Each host extractor is a pure
 * function over the embed HTML so it can be unit-tested; embeds from unsupported hosts are kept
 * as-is (opened in the browser).
 */
class VideoExtractors(private val network: HttpFetcher) {

    /** Resolves every embed it can (in parallel), returning the streams most likely to play first. */
    suspend fun resolveAll(sources: List<VideoSource>): List<VideoSource> = coroutineScope {
        val checked = sources.map { source ->
            async {
                if (source.isDirect) return@async Checked(listOf(source), usable = true)
                if (EmbedProbe.isDownloadOnly(source.videoUrl)) return@async Checked(listOf(source), usable = false)
                val direct = withTimeoutOrNull(20_000) { runCatching { resolve(source) }.getOrNull() }
                // Its own page is not offered next to it: those host pages are what sent people to ad sites.
                if (direct != null) return@async Checked(listOf(direct), usable = true)
                withTimeoutOrNull(PROBE_TIMEOUT_MS) { runCatching { probe(source) }.getOrNull() } ?: Checked(listOf(source), usable = true)
            }
        }.awaitAll()
        // A host that is dead, asks for a bot check it would not pass, or refuses to play in the app is only offered
        // when nothing else is left (F2, F3): better one server that plays than five that do not.
        val good = checked.filter { it.usable }.flatMap { it.sources }
        (good.ifEmpty { checked.flatMap { it.sources } }).distinctBy { it.videoUrl }.let(::bestFirst)
    }

    private class Checked(val sources: List<VideoSource>, val usable: Boolean)

    /**
     * Any other host: its page is read (through FlareSolverr when it shows a bot check, on the server) and its stream
     * taken from the page or its packed player script. Dead, sandbox-refusing or still-blocked pages are marked unusable.
     */
    private suspend fun probe(source: VideoSource): Checked {
        val url = EmbedProbe.mirrorFor(source.videoUrl)
        if (!url.startsWith("http")) return Checked(listOf(source), usable = true)
        val origin = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/') + "/"
        val html = network.getString(url, mapOf("Referer" to origin))
        if (EmbedProbe.isDead(html) || com.nelson.yomukai.data.network.WebProtection.detectProtection(200, html) != null) {
            return Checked(listOf(source), usable = false)
        }
        EmbedProbe.findStream(html)?.let { stream ->
            val direct = source.copy(
                videoUrl = stream, isDirect = true, headers = mapOf("Referer" to origin),
                quality = source.quality + if ("m3u8" in stream) " • HLS" else " • MP4"
            )
            return Checked(listOf(direct), usable = true)
        }
        return Checked(listOf(source), usable = !EmbedProbe.refusesSandbox(html))
    }

    private suspend fun resolve(source: VideoSource): VideoSource? {
        // A preferred resolver (yt-dlp on the server) gets the first try; the extractors below are the fallback.
        preferred?.let { p -> runCatching { p(source) }.getOrNull()?.let { return it } }
        val url = source.videoUrl
        val stream = when {
            "ok.ru/videoembed" in url || "ok.ru/video/" in url ->
                okru(network.getString(url.replace("/video/", "/videoembed/"), cloudflareFallback = false))
                    ?.let { it to emptyMap<String, String>() }
            "mp4upload.com" in url ->
                mp4upload(network.getString(embedUrlForMp4upload(url), mapOf("Referer" to "https://www.mp4upload.com/"), cloudflareFallback = false))
                    ?.let { it to mapOf("Referer" to "https://www.mp4upload.com/") }
            "yourupload.com" in url ->
                yourupload(network.getString(url.replace("/watch/", "/embed/"), cloudflareFallback = false))
                    ?.let { it to mapOf("Referer" to "https://www.yourupload.com/") }
            VOE_HOST in url -> voe(url)
            EmbedProbe.isDood(url) -> dood(url)
            "blogger.com/video.g" in url ->
                blogger(network.getString(url, cloudflareFallback = false))?.let { it to emptyMap<String, String>() }
            else -> null
        } ?: return null
        return source.copy(
            videoUrl = stream.first,
            isDirect = true,
            headers = stream.second,
            quality = source.quality + if ("m3u8" in stream.first) " • HLS" else " • MP4"
        )
    }

    /**
     * DoodStream: its page (after the redirect to the current domain) gives a `/pass_md5/…` path; that answers the
     * stream's base, which plays with ten random letters, the token and the time appended (its own `makePlay()`).
     */
    private suspend fun dood(url: String): Pair<String, Map<String, String>>? {
        // Its many domains come and go (dsvplay.com answers empty); dood.li always sends to the current one.
        val embed = url.replace("/d/", "/e/").replaceFirst(Regex("//[^/]+"), "//dood.li")
        val (html, finalUrl) = network.getStringWithUrl(embed, mapOf("Referer" to embed))
        val (path, token) = EmbedProbe.doodPass(html) ?: return null
        val origin = finalUrl.substringBefore("://") + "://" + finalUrl.substringAfter("://").substringBefore('/')
        val base = network.getString(origin + path, mapOf("Referer" to finalUrl), cloudflareFallback = false)
        return EmbedProbe.doodStream(base, token)?.let { it to mapOf("Referer" to "$origin/") }
    }

    /** Voe first sends the visitor to a rotating mirror; the real page holds the stream. */
    private suspend fun voe(url: String): Pair<String, Map<String, String>>? {
        val first = network.getString(url, cloudflareFallback = false)
        val page = VOE_MIRROR.find(first)?.value
        val html = if (page == null) first else network.getString(page, mapOf("Referer" to url), cloudflareFallback = false)
        val referer = (page ?: url).substringBefore("/e/") + "/"
        return voeStream(html)?.let { it to mapOf("Referer" to referer) }
    }

    companion object {
        /**
         * Tried before the built-in extractors for every player page. The server sets it to yt-dlp; the Android app
         * leaves it empty (and falls back to a hidden WebView instead).
         */
        @Volatile
        var preferred: (suspend (VideoSource) -> VideoSource?)? = null

        /**
         * Blogger's player page lists its MP4 files as `"https://…googlevideo.com/videoplayback?…",[itag]`
         * inside escaped JSON. 720p (itag 22) first, then 360p (18). The links are tied to the address that
         * asked for the page, so the same client must play them (the server proxies, the app plays itself).
         */
        fun blogger(html: String): String? {
            val text = html.replace("&quot;", "\"").replace("\\u0026", "&").replace("\\u003d", "=").replace("\\/", "/")
            val found = BLOGGER_STREAM.findAll(text).map { it.groupValues[2] to it.groupValues[1] }.toList()
            return (found.firstOrNull { it.first == "22" } ?: found.firstOrNull { it.first == "18" } ?: found.firstOrNull())?.second
        }

        private val BLOGGER_STREAM = Regex(""""(https://[a-z0-9-]+\.googlevideo\.com/videoplayback\?[^"\\]+)",\[(\d+)]""")
        private const val VOE_HOST = "voe.sx"
        private const val PROBE_TIMEOUT_MS = 25_000L
        private val VOE_MIRROR = Regex("""https://[a-zA-Z0-9.-]+/e/[a-z0-9]+""")
        private val VOE_DATA = Regex("""<script[^>]*type="application/json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val VOE_NOISE = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

        /** Direct streams first; the zilla-networks HLS last, because Cloudflare blocks its segments for non-browsers. */
        fun bestFirst(sources: List<VideoSource>): List<VideoSource> =
            sources.sortedWith(compareBy({ !it.isDirect }, { "zilla-networks" in it.videoUrl }))

        private fun rot13(text: String) = text.map { c ->
            when (c) {
                in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            }
        }.joinToString("")

        /** Voe's player config: rot13, noise markers, Base64, every char shifted by 3, reversed, Base64 again. */
        fun voeConfig(encoded: String): com.google.gson.JsonObject? = runCatching {
            val clean = VOE_NOISE.fold(rot13(encoded)) { text, noise -> text.replace(noise, "") }
            // The MIME decoder skips stray characters, which the strict one rejects outright.
            val decoder = java.util.Base64.getMimeDecoder()
            val shifted = decoder.decode(clean).map { ((it.toInt() and 0xFF) - 3).toChar() }
            val inner = shifted.reversed().joinToString("")
            parseJson(String(decoder.decode(inner), Charsets.UTF_8)).asJsonObject
        }.getOrNull()

        /** The MP4 when offered, since it also works for downloads; otherwise the HLS playlist. */
        fun voeStream(html: String): String? {
            val config = VOE_DATA.findAll(html).firstNotNullOfOrNull { block ->
                runCatching { parseJson(block.groupValues[1]).asJsonArray.first().asString }.getOrNull()?.let(::voeConfig)
            } ?: return null
            return config.str("direct_access_url")?.takeIf { it.startsWith("http") }
                ?: config.str("source")?.takeIf { it.startsWith("http") }
        }

        private val MP4UPLOAD_SRC = Regex("""src:\s*"(https?://[^"]+\.mp4[^"]*)"""")
        private val YOURUPLOAD_FILE = Regex("""file\s*:\s*['"](https?://[^'"]+\.mp4[^'"]*)['"]""")

        fun embedUrlForMp4upload(url: String): String =
            if ("/embed-" in url) url
            else "https://www.mp4upload.com/embed-" + url.trimEnd('/').substringAfterLast("/").removeSuffix(".html") + ".html"

        /** ok.ru: data-options → flashvars.metadata (object, or JSON-encoded string) → hlsManifestUrl or best MP4. */
        fun okru(html: String): String? {
            val options = Jsoup.parse(html).selectFirst("[data-options]")?.attr("data-options") ?: return null
            val raw = runCatching { parseJson(options).asJsonObject.obj("flashvars")?.get("metadata") }.getOrNull() ?: return null
            val metadata = when {
                raw.isJsonObject -> raw.asJsonObject
                raw.isJsonPrimitive -> runCatching { parseJson(raw.asString).asJsonObject }.getOrNull()
                else -> null
            } ?: return null
            metadata.str("hlsManifestUrl")?.takeIf { it.isNotBlank() }?.let { return it }
            return metadata.arr("videos").objects().lastOrNull { !it.str("url").isNullOrBlank() }?.str("url")
        }

        fun mp4upload(html: String): String? = MP4UPLOAD_SRC.find(html)?.groupValues?.get(1)

        fun yourupload(html: String): String? {
            val doc = Jsoup.parse(html)
            doc.selectFirst("meta[property=og:video]")?.attr("content")?.takeIf { it.startsWith("http") }?.let { return it }
            return YOURUPLOAD_FILE.find(Parser.unescapeEntities(html, false))?.groupValues?.get(1)
        }
    }
}
