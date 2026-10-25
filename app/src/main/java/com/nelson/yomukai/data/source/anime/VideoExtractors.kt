package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.data.network.NetworkHelper
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
class VideoExtractors(private val network: NetworkHelper) {

    /** Resolves every embed it can (in parallel), returning direct streams first. */
    suspend fun resolveAll(sources: List<VideoSource>): List<VideoSource> = coroutineScope {
        sources.map { source ->
            async {
                if (source.isDirect) return@async listOf(source)
                val direct = withTimeoutOrNull(20_000) { runCatching { resolve(source) }.getOrNull() }
                listOfNotNull(direct, source)
            }
        }.awaitAll().flatten().distinctBy { it.videoUrl }.sortedByDescending { it.isDirect }
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
            else -> null
        } ?: return null
        return source.copy(
            videoUrl = stream.first,
            isDirect = true,
            headers = stream.second,
            quality = source.quality + if ("m3u8" in stream.first) " • HLS" else " • MP4"
        )
    }

    companion object {
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
