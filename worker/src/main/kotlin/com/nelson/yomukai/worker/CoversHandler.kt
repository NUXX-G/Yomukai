package com.nelson.yomukai.worker

import com.nelson.yomukai.data.image.ImageShrinker
import okhttp3.Request
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers and banners at every width the apps ask for, made at home so the VPS does not spend its CPU on them (F2.2).
 * Payload: {"images":[{"u": url, "h": {header: value}, "w": [240, 360, …]}]}. Output: a ZIP with, for image i,
 * "i/original" (the downloaded bytes, its type in the entry comment) and "i/<width>" for each width that got smaller
 * (a width missing from the ZIP means the original is already as small). The VPS checks everything it gets back.
 */
class CoversHandler(
    /** How an image is fetched (tests give their own); by default from the public internet only. */
    private val fetch: ((String, com.google.gson.JsonObject?, JobContext) -> Pair<ByteArray, String>?)? = null
) : JobHandler {
    override val type = "covers"

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val images = job.payload.getAsJsonArray("images")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty().take(MAX_IMAGES)
        if (images.isEmpty()) throw PermanentJobFailure("Sin imágenes")
        val out = context.newFile(".zip")
        var done = 0
        ZipOutputStream(out.outputStream()).use { zip ->
            images.forEachIndexed { i, img ->
                val url = img.get("u")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEachIndexed
                val fetched = runCatching { (fetch ?: ::download)(url, img.getAsJsonObject("h"), context) }.getOrNull() ?: return@forEachIndexed
                zip.putNextEntry(ZipEntry("$i/original").apply { comment = fetched.second })
                zip.write(fetched.first)
                zip.closeEntry()
                img.getAsJsonArray("w")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asInt }.orEmpty().filter { it in WIDTHS }.distinct().forEach { w ->
                    val small = runCatching { ImageShrinker.shrink(fetched.first, fetched.second.lowercase(), w) }.getOrNull() ?: return@forEach
                    zip.putNextEntry(ZipEntry("$i/$w"))
                    zip.write(small)
                    zip.closeEntry()
                }
                done++
            }
        }
        if (done == 0) throw PermanentJobFailure("No se pudo descargar ninguna imagen")
        return JobOutput(out, "application/zip")
    }

    private fun download(url: String, headers: com.google.gson.JsonObject?, context: JobContext): Pair<ByteArray, String>? {
        // Only public addresses, at every hop: redirects are followed by hand and each one is checked again, and the
        // resolver never hands out a home address (so a name re-pointed after the check cannot reach this network).
        val client = PublicOnly.client(context.http)
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            PublicOnly.check(target)
            val request = Request.Builder().url(target).apply {
                headers?.entrySet()?.take(MAX_HEADERS)?.forEach { (k, v) ->
                    if (k in ALLOWED_HEADERS && v.isJsonPrimitive) header(k, v.asString.take(MAX_HEADER_VALUE))
                }
            }.build()
            var redirected = false
            val answer = client.newCall(request).execute().use { r ->
                if (r.isRedirect) {
                    target = r.header("Location")?.let { r.request.url.resolve(it)?.toString() } ?: return null
                    redirected = true
                    null
                } else {
                    read(r)
                }
            }
            if (!redirected) return answer
        }
        return null
    }

    private fun read(r: okhttp3.Response): Pair<ByteArray, String>? {
        run {
            if (!r.isSuccessful) return null
            val type = r.header("Content-Type").orEmpty().substringBefore(';').trim()
            if (!type.startsWith("image/")) return null
            val body = r.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.byteStream().use { it.readNBytes(MAX_BYTES + 1) }
            return if (bytes.size > MAX_BYTES) null else bytes to type
        }
    }

    companion object {
        const val MAX_IMAGES = 20
        const val MAX_BYTES = 12 * 1024 * 1024
        private const val MAX_HEADERS = 6
        private const val MAX_REDIRECTS = 3
        private const val MAX_HEADER_VALUE = 500
        private val ALLOWED_HEADERS = setOf("Referer", "User-Agent", "Origin", "Accept")
        val WIDTHS = setOf(240, 360, 480, 720, 1080, 1440, 1920)
    }
}
