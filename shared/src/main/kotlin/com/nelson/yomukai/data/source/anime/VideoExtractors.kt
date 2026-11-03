package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.arr
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
        sources.map { source ->
            async {
                if (source.isDirect) return@async listOf(source)
                val direct = withTimeoutOrNull(20_000) { runCatching { resolve(source) }.getOrNull() }
                listOfNotNull(direct, source)
            }
        }.awaitAll().flatten().distinctBy { it.videoUrl }.let(::bestFirst)
    }

    private suspend fun resolve(source: VideoSource): VideoSource? {
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
            else -> null
        } ?: return null
        return source.copy(
            videoUrl = stream.first,
            isDirect = true,
            headers = stream.second,
            quality = source.quality + if ("m3u8" in stream.first) " • HLS" else " • MP4"
        )
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
        private const val VOE_HOST = "voe.sx"
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
