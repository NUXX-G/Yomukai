package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.nelson.yomukai.data.speech.TtsPlan
import com.nelson.yomukai.data.speech.TtsTier
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.MessageDigest

/**
 * Reading aloud picks the best voice available (shared TtsPlan): the home worker's high-quality engine first (the VPS
 * only queues the job and keeps the result, which is reused when someone else reads the same text), Piper on the VPS
 * as a light last resort, and otherwise the client's own voice (device or system). Worker voices are "w:<id>".
 */
class VoiceRouter(private val s: Services) {
    suspend fun voices(): Map<String, Any?> {
        val worker = workerVoices()
        // Voices per character cloned from the admin's samples, when a worker that can clone is online.
        val clones = if (worker.isNotEmpty() && canClone()) s.voiceSamples.list() else emptyList()
        val server = if (s.speech.available) s.speech.voices() else emptyList()
        val tiers = buildSet {
            if (worker.isNotEmpty()) add(TtsTier.WORKER)
            if (server.isNotEmpty()) add(TtsTier.SERVER)
        }
        return mapOf(
            "available" to (worker.isNotEmpty() || server.isNotEmpty()),
            "tiers" to TtsPlan.order(tiers).map { it.name.lowercase() },
            "voices" to worker.map { v ->
                mapOf("id" to "w:" + v.get("id").asString, "label" to v.get("label")?.asString + " (alta calidad)", "lang" to v.get("lang")?.asString,
                    "gender" to v.get("gender")?.asString, "region" to "", "tier" to "worker")
            } + clones.map { c ->
                mapOf("id" to "w:" + CLONE + c.id, "label" to c.name + " (voz clonada)", "lang" to c.lang, "gender" to c.gender, "region" to "", "tier" to "worker")
            } + server.map { mapOf("id" to it.id, "label" to it.label, "lang" to it.lang, "gender" to it.gender, "region" to it.region, "tier" to "server") }
        )
    }

    private suspend fun canClone(): Boolean = s.features.isOn("worker_jobs") && s.workers.availability().cloning

    /** A worker voice id ("pocket-alba", or "clone:3" for a sample) that can be used right now. */
    private suspend fun usable(id: String): Boolean =
        if (id.startsWith(CLONE)) canClone() && id.removePrefix(CLONE).toLongOrNull()?.let(s.voiceSamples::file) != null
        else workerVoices().any { it.get("id")?.asString == id }

    private suspend fun workerVoices(): List<JsonObject> {
        if (!s.features.isOn("worker_jobs")) return emptyList()
        val a = s.workers.availability()
        return if (JobTypes.TTS in a.engines) a.voices else emptyList()
    }

    /** WAV of [text]: from the worker (waiting up to [waitMs]) for "w:" voices, from Piper otherwise. */
    suspend fun speak(user: User, text: String, voice: String, rate: Float, waitMs: Long = 30_000): ByteArray {
        if (!voice.startsWith("w:")) return s.speech.synthesize(text, voice, rate)
        val clean = text.replace(Regex("\\s+"), " ").trim().take(MAX_CHARS)
        require(clean.isNotEmpty()) { "Texto vacío" }
        val id = voice.removePrefix("w:")
        require(!user.isGuest) { "Las voces del servidor de casa no están disponibles para invitados" }
        require(usable(id)) { "Esa voz no está disponible ahora (¿el servidor de casa está apagado?)" }
        val r = normalRate(rate)
        val key = key(id, r, clean)
        s.jobs.results.get(key)?.let { (file, _) -> return file.readBytes() }
        require(pending(user) < MAX_PENDING_PER_USER) { "Demasiadas lecturas en cola; espera un poco" }
        val job = s.jobs.enqueue(JobQueue.Request(JobTypes.TTS, payload(clean, id, r), JobPriority.INTERACTIVE, dedupKey = key, requestedBy = user.id, ttlMs = 10 * 60_000L))
        val done = withTimeoutOrNull(waitMs) {
            while (true) {
                val j = s.jobs.get(job.id) ?: break
                if (j.status == JobStatus.DONE || j.status == JobStatus.FAILED || j.status == JobStatus.EXPIRED) break
                delay(POLL_MS)
            }
        }
        return s.jobs.results.get(key)?.first?.readBytes() ?: throw IOException(if (done == null) "El servidor de casa tarda demasiado" else "El servidor de casa no pudo leerlo")
    }

    /** Queues the next texts (e.g. the next chapter's paragraphs) at lower priority, so they are ready when reached. */
    suspend fun prefetch(user: User, texts: List<String>, voice: String, rate: Float): Int {
        if (!voice.startsWith("w:") || user.isGuest) return 0
        val id = voice.removePrefix("w:")
        val r = normalRate(rate)
        var room = MAX_PENDING_PER_USER - pending(user)
        if (!usable(id)) return 0
        var queued = 0
        for (t in texts.map { it.replace(Regex("\\s+"), " ").trim() }.distinct().take(MAX_PREFETCH)) {
            val clean = t.replace(Regex("\\s+"), " ").trim().take(MAX_CHARS).takeIf { it.isNotEmpty() } ?: continue
            val key = key(id, r, clean)
            if (s.jobs.results.has(key)) continue
            if (room-- <= 0) break
            s.jobs.enqueue(JobQueue.Request(JobTypes.TTS, payload(clean, id, r), JobPriority.NEXT, dedupKey = key, requestedBy = user.id, ttlMs = 6 * 3_600_000L))
            queued++
        }
        return queued
    }

    private fun payload(text: String, voice: String, rate: Float) = JsonObject().apply {
        addProperty("text", text); addProperty("voice", voice); addProperty("rate", rate)
    }

    /** One value per real speed (0.5..2 in steps of 0.05): the same audio is never queued twice for 1.0 and 1.0001. */
    private fun normalRate(rate: Float): Float = if (!rate.isFinite()) 1f else (Math.round(rate.coerceIn(0.5f, 2f) * 20) / 20f)

    private suspend fun pending(user: User): Int = s.db.tx {
        queryOne("SELECT COUNT(*) FROM jobs WHERE requested_by = ? AND type = ? AND status IN ('queued','running')", user.id, JobTypes.TTS) { getInt(1) }
    } ?: 0

    private fun key(voice: String, rate: Float, text: String): String =
        "tts:" + MessageDigest.getInstance("SHA-256").digest("$voice|${"%.2f".format(java.util.Locale.ROOT, rate)}|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_CHARS = 2_000
        const val CLONE = "clone:"
        const val MAX_PREFETCH = 60
        const val POLL_MS = 300L
        const val MAX_PENDING_PER_USER = 120
    }
}

fun Route.voiceRoutes(s: Services) {
    get("/tts/voices") { call.respond(s.voices.voices()) }
    // A piece of text read aloud by a natural voice, as WAV. The same text and voice is cached (and shared).
    post("/tts") {
        val body = call.jsonBody(16_384)
        val rate = body.get("rate")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1f
        val audio = runCatching { s.voices.speak(call.user, body.text("text", 4_000), body.text("voice", 60), rate) }.getOrElse { e ->
            if (e is IllegalArgumentException) throw e
            return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "No se pudo leer en voz alta")))
        }
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.respondBytes(audio, ContentType.parse("audio/wav"))
    }
    post("/tts/prefetch") {
        val body = call.jsonBody(64_000)
        val texts = body.getAsJsonArray("texts")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()
        val rate = body.get("rate")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1f
        call.respond(mapOf("queued" to s.voices.prefetch(call.user, texts, body.text("voice", 60), rate)))
    }
}
