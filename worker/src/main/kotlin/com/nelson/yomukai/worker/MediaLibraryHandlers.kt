package com.nelson.yomukai.worker

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nelson.yomukai.data.media.ComicInfo
import com.nelson.yomukai.data.media.ExternalIds
import com.nelson.yomukai.data.media.JellyfinNaming
import com.nelson.yomukai.data.media.Nfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun JsonObject.str(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
private fun JsonObject.int(name: String) = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
private fun JsonObject.ids() = ExternalIds(int("anilistId"), int("malId"), int("tvdbId"))

/** A destination inside [root] only: names come from titles, never from paths a user typed. */
internal fun inside(root: File, relative: String): File {
    val target = File(root, relative).canonicalFile
    if (!target.path.startsWith(root.canonicalFile.path + File.separator)) throw PermanentJobFailure("Ruta fuera de la biblioteca")
    return target
}

/**
 * "Descargar al Jellyfin": the episode's video (asked fresh to the VPS) is copied with ffmpeg into MKV without
 * re-encoding, in a temporary folder; then moved into the anime library with Jellyfin's layout, with tvshow.nfo,
 * season.nfo and the episode's .nfo carrying the AniList/MAL ids, the poster, and Jellyfin asked to scan only that
 * folder. Free space is checked first; a failed copy is retried by the queue.
 */
class JellyfinDownloadHandler : JobHandler {
    override val type = "jellyfin_download"
    override fun available(): Boolean = Capabilities.installed("ffmpeg", "-version")

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val p = job.payload
        val cfg = p.getAsJsonObject("config") ?: throw PermanentJobFailure("Falta la configuración de Jellyfin")
        val library = File(cfg.str("animePath").ifBlank { throw PermanentJobFailure("Falta la carpeta de anime en el panel") })
        if (!library.isDirectory) throw PermanentJobFailure("La carpeta ${library.path} no existe en este equipo")
        if (library.usableSpace < MIN_FREE) throw PermanentJobFailure("Queda poco espacio en ${library.path}")
        val title = p.str("title").ifBlank { throw PermanentJobFailure("Falta el título") }
        val year = p.int("year")
        val season = p.int("season") ?: 1
        val number = p.get("number")?.asDouble ?: throw PermanentJobFailure("Falta el número de episodio")
        val target = inside(library, JellyfinNaming.episodePath(title, year, season, number))
        if (target.isFile && target.length() > 0) return done(context, target, skipped = true)

        val video = context.freshVideo(p.str("sourceId"), p.str("episodeUrl")) ?: error("La fuente no da ahora un vídeo directo")
        val url = video.str("url")
        PublicOnly.check(url)
        val headers = video.getAsJsonObject("headers")?.entrySet()?.filter { it.key in setOf("Referer", "User-Agent", "Origin") }
            ?.joinToString("") { "${it.key}: ${it.value.asString.replace("\r", "").replace("\n", "")}\r\n" }.orEmpty()
        val temp = context.newFile(".mkv")
        val ff = Processes.run(
            listOf("ffmpeg", "-y", "-v", "error", "-protocol_whitelist", "https,http,tls,tcp,crypto,hls,httpproxy", "-headers", headers, "-i", url,
                "-map", "0", "-c", "copy", temp.absolutePath),
            timeoutMs = 3 * 3_600_000L
        )
        if (ff.exitCode != 0 || temp.length() < MIN_VIDEO) error("ffmpeg no pudo copiar el vídeo: ${ff.output.takeLast(300)}")

        val seasonDir = target.parentFile.apply { mkdirs() }
        val showDir = seasonDir.parentFile
        File(showDir, "tvshow.nfo").takeUnless { it.exists() }?.writeText(Nfo.tvshow(title, year, p.str("synopsis"), p.getAsJsonArray("genres")?.map { it.asString }.orEmpty(), p.ids()))
        File(seasonDir, "season.nfo").takeUnless { it.exists() }?.writeText(Nfo.season(season))
        File(target.path.substringBeforeLast('.') + ".nfo").writeText(Nfo.episode(title, p.str("episodeTitle"), season, number, p.ids()))
        p.str("cover").takeIf { it.startsWith("https://") }?.let { cover ->
            val poster = File(showDir, "poster.jpg")
            if (!poster.exists()) runCatching { PublicOnly.check(cover); download(context, cover, poster) }
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (cfg.str("jellyfinUrl").isNotBlank()) scan(context, cfg.str("jellyfinUrl"), cfg.str("jellyfinKey"), showDir.path)
        return done(context, target, skipped = false)
    }

    private fun download(context: JobContext, url: String, to: File) {
        context.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.byteStream()?.use { input -> to.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun done(context: JobContext, file: File, skipped: Boolean): JobOutput {
        val out = context.newFile(".json")
        out.writeText(JsonObject().apply { addProperty("path", file.path); addProperty("bytes", file.length()); addProperty("skipped", skipped) }.toString())
        return JobOutput(out, "application/json")
    }

    companion object {
        const val MIN_FREE = 5L * 1024 * 1024 * 1024
        const val MIN_VIDEO = 1024L * 1024

        /** Jellyfin's "look at this folder" (instead of rescanning the whole library). */
        fun scan(context: JobContext, base: String, key: String, path: String): Boolean = runCatching {
            val body = JsonObject().apply {
                add("Updates", JsonArray().apply { add(JsonObject().apply { addProperty("Path", path); addProperty("UpdateType", "Created") }) })
            }
            context.http.newCall(
                Request.Builder().url(base.trimEnd('/') + "/Library/Media/Updated").header("X-Emby-Token", key)
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}

/**
 * Manga and comics for Kavita/Komga: the chapter's pages (addresses resolved by the VPS, public only) zipped into a CBZ
 * with ComicInfo.xml, written into the comics folder with "Serie/Serie - Ch. 012.cbz".
 */
class CbzExportHandler : JobHandler {
    override val type = "cbz_export"

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val p = job.payload
        val cfg = p.getAsJsonObject("config") ?: throw PermanentJobFailure("Falta la configuración")
        val root = File(cfg.str("comicsPath").ifBlank { throw PermanentJobFailure("Falta la carpeta de cómics en el panel") })
        if (!root.isDirectory) throw PermanentJobFailure("La carpeta ${root.path} no existe en este equipo")
        val pages = p.getAsJsonArray("pages")?.map { it.asString }?.take(MAX_PAGES).orEmpty()
        if (pages.isEmpty()) throw PermanentJobFailure("Sin páginas")
        val number = p.get("number")?.asDouble ?: 0.0
        val target = inside(root, ComicInfo.cbzPath(p.str("series"), number, p.int("volume")))
        val headers = p.getAsJsonObject("headers")?.entrySet()?.filter { it.key in setOf("Referer", "User-Agent", "Origin") }?.associate { it.key to it.value.asString }.orEmpty()
        val temp = context.newFile(".cbz")
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            pages.forEachIndexed { i, url ->
                PublicOnly.check(url)
                val bytes = context.http.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { r ->
                    if (!r.isSuccessful) error("Página ${i + 1}: ${r.code}")
                    r.body?.bytes() ?: error("Página ${i + 1} vacía")
                }
                val ext = when {
                    bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
                    bytes.size > 11 && String(bytes, 8, 4) == "WEBP" -> "webp"
                    else -> "jpg"
                }
                zip.putNextEntry(ZipEntry("%04d.%s".format(i + 1, ext)))
                zip.write(bytes)
                zip.closeEntry()
            }
            val info = ComicInfo.Info(
                series = p.str("series"), number = Nfo.formatNumber(number), title = p.str("title"), volume = p.int("volume"), summary = p.str("summary"),
                writers = p.getAsJsonArray("authors")?.map { it.asString }.orEmpty(), genres = p.getAsJsonArray("genres")?.map { it.asString }.orEmpty(),
                language = p.str("language"), manga = p.str("type") == "MANGA", ids = p.ids(), pageCount = pages.size
            )
            zip.putNextEntry(ZipEntry("ComicInfo.xml"))
            zip.write(ComicInfo.xml(info).toByteArray())
            zip.closeEntry()
        }
        target.parentFile.mkdirs()
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val out = context.newFile(".json")
        out.writeText(JsonObject().apply { addProperty("path", target.path); addProperty("pages", pages.size) }.toString())
        return JobOutput(out, "application/json")
    }

    companion object { const val MAX_PAGES = 1_000 }
}
