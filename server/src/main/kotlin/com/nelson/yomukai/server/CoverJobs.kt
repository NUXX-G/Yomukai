package com.nelson.yomukai.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nelson.yomukai.data.image.HighResCovers
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Covers and banners at every width, made by the home worker (F2.2) so the VPS neither downloads the originals nor
 * spends CPU shrinking them when someone opens Inicio. Only while a worker with the «covers» engine is online; without
 * one, the proxy goes on doing it on demand exactly as before.
 */
class CoverJobs(private val s: Services) {
    private val log = LoggerFactory.getLogger("CoverJobs")

    /** One image to prepare: its address (as the proxy fetches it), headers and the widths wanted. */
    data class Wanted(val url: String, val headers: Map<String, String>, val widths: List<Int>)

    /** The covers and banners of the works people see first (most recently updated in the index), not yet ready. */
    suspend fun wanted(max: Int = MAX_PER_ROUND): List<Wanted> {
        val rows = s.db.tx {
            query(
                """SELECT w.cover, w.banner FROM works w WHERE EXISTS(SELECT 1 FROM index_items i WHERE i.work_key = w.key)
                   ORDER BY w.updated_at DESC LIMIT ?""",
                max * 2
            ) { getString(1).orEmpty() to getString(2).orEmpty() }
        }
        val out = mutableListOf<Wanted>()
        for ((cover, banner) in rows) {
            if (cover.startsWith("https://")) HighResCovers.upgrade(cover).let { u -> if (!s.proxy.hasImage(u, COVER_WIDTHS.last())) out += Wanted(u, emptyMap(), COVER_WIDTHS) }
            if (banner.startsWith("https://") && !s.proxy.hasImage(banner, BANNER_WIDTHS.first())) out += Wanted(banner, emptyMap(), BANNER_WIDTHS)
            if (out.size >= max) break
        }
        return out.distinctBy { it.url }.take(max)
    }

    /** Queues a few low-priority jobs when a worker can take them. Returns how many images were asked for. */
    suspend fun enqueueSome(): Int {
        if (s.workers.online().none { JobTypes.COVERS in it.engines }) return 0
        val wanted = wanted()
        wanted.chunked(PER_JOB).forEach { chunk ->
            val payload = JsonObject().apply {
                add("images", JsonArray().also { arr ->
                    chunk.forEach { w ->
                        arr.add(JsonObject().apply {
                            addProperty("u", w.url)
                            add("h", JsonObject().also { h -> w.headers.forEach { (k, v) -> h.addProperty(k, v) } })
                            add("w", JsonArray().also { a -> w.widths.forEach(a::add) })
                        })
                    }
                })
            }
            val dedup = "covers|" + MessageDigest.getInstance("SHA-256").digest(chunk.joinToString("\n") { it.url }.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(32)
            s.jobs.enqueue(JobQueue.Request(JobTypes.COVERS, payload, priority = JobPriority.BACKGROUND, dedupKey = dedup))
        }
        if (wanted.isNotEmpty()) log.debug("Portadas pedidas al worker: {}", wanted.size)
        return wanted.size
    }

    /**
     * A finished covers job: its ZIP is read and only what was asked for goes to the image cache (the address of the
     * i-th image of the payload, the widths it listed, real image bytes, bounded sizes). The result file is removed.
     */
    suspend fun finished(job: Job) {
        if (job.type != JobTypes.COVERS || job.resultKey == null) return
        val (file, _) = s.jobs.results.get(job.resultKey) ?: return
        val asked = job.payload.getAsJsonArray("images")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
        var stored = 0
        runCatching {
            ZipFile(file).use { zip ->
                asked.forEachIndexed { i, img ->
                    val url = img.get("u")?.asString ?: return@forEachIndexed
                    val widths = img.getAsJsonArray("w")?.map { it.asInt }.orEmpty()
                    val originalEntry = zip.getEntry("$i/original") ?: return@forEachIndexed
                    if (originalEntry.size > MAX_ENTRY_BYTES) return@forEachIndexed
                    val original = zip.getInputStream(originalEntry).use { it.readNBytes(MAX_ENTRY_BYTES + 1) }.takeIf { it.size <= MAX_ENTRY_BYTES } ?: return@forEachIndexed
                    val shrunk = widths.associateWith { w ->
                        zip.getEntry("$i/$w")?.takeIf { it.size <= MAX_ENTRY_BYTES }?.let { e -> zip.getInputStream(e).use { it.readNBytes(MAX_ENTRY_BYTES) } }
                    }
                    if (s.proxy.storeFromWorker(url, original, originalEntry.comment ?: "image/jpeg", shrunk)) stored++
                }
            }
        }.onFailure { log.info("Resultado de portadas no válido: {}", it.message) }
        s.jobs.results.remove(job.resultKey)
        log.debug("Portadas del worker guardadas: {}", stored)
    }

    companion object {
        val COVER_WIDTHS = listOf(240, 360, 480, 720)
        val BANNER_WIDTHS = listOf(1080, 1440, 1920)
        const val PER_JOB = 20
        const val MAX_PER_ROUND = 100
        const val MAX_ENTRY_BYTES = 12 * 1024 * 1024
        const val ROUND_MS = 30 * 60_000L
    }
}
