package com.nelson.yomukai.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voices per character by cloning (Pocket TTS on the home worker): short samples the admin uploads, only with a free
 * licence or recorded by themselves (the licence is required and kept). They are offered as "w:clone:<id>" voices
 * when a worker that can clone is online; the worker fetches the sample through its own authenticated route.
 */
class VoiceSamples(private val db: Database, dataDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    data class Sample(val id: Long, val name: String, val lang: String, val gender: String, val license: String, val source: String, val seconds: Double)

    private val dir = File(dataDir, "voice-samples").apply { mkdirs() }

    fun file(id: Long): File? = File(dir, "$id.wav").takeIf { it.isFile }

    suspend fun list(): List<Sample> = db.tx {
        query("SELECT * FROM voice_samples ORDER BY lang, name") {
            Sample(getLong("id"), getString("name"), getString("lang"), getString("gender"), getString("license"), getString("source"), getDouble("seconds"))
        }
    }

    suspend fun add(user: User, bytes: ByteArray, name: String, lang: String, gender: String, license: String, source: String): Long {
        require(user.isAdmin) { "Solo el administrador" }
        val seconds = wavSeconds(bytes) ?: throw IllegalArgumentException("Tiene que ser un archivo WAV")
        require(seconds in MIN_SECONDS..MAX_SECONDS) { "La muestra tiene que durar entre 3 y 30 segundos" }
        val cleanName = name.filter { !it.isISOControl() }.trim().take(MAX_NAME)
        require(cleanName.isNotEmpty()) { "Falta el nombre" }
        require(lang.length == 2 && lang.all { it in 'a'..'z' }) { "Idioma no válido" }
        require(gender == "f" || gender == "m") { "Indica si es voz de mujer o de hombre" }
        // Only voices that may be used: a free licence or recorded by us (the admin says which, and it is kept).
        val cleanLicense = license.filter { !it.isISOControl() }.trim().take(MAX_LICENSE)
        require(cleanLicense.isNotEmpty()) { "Indica la licencia libre de la muestra o que la habéis grabado vosotros" }
        val id = db.tx {
            update(
                "INSERT INTO voice_samples(name, lang, gender, license, source, seconds, created_at) VALUES(?,?,?,?,?,?,?)",
                cleanName, lang, gender, cleanLicense, source.filter { !it.isISOControl() }.trim().take(MAX_SOURCE), seconds, clock()
            )
            lastId()
        }
        withContext(Dispatchers.IO) { File(dir, "$id.wav").writeBytes(bytes) }
        return id
    }

    suspend fun delete(user: User, id: Long) {
        require(user.isAdmin) { "Solo el administrador" }
        db.tx { update("DELETE FROM voice_samples WHERE id = ?", id) }
        withContext(Dispatchers.IO) { File(dir, "$id.wav").delete() }
    }

    companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        private const val MIN_SECONDS = 3.0
        private const val MAX_SECONDS = 30.0
        private const val MAX_NAME = 40
        private const val MAX_LICENSE = 200
        private const val MAX_SOURCE = 300
        private const val HEADER = 44

        /** Length of a PCM WAV from its header, or null when it is not one. */
        fun wavSeconds(bytes: ByteArray): Double? {
            if (bytes.size < HEADER) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
            // Walk the chunks to "fmt " (byte rate) and "data" (size).
            var pos = 12
            var byteRate = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = b.getInt(pos + 4)
                if (size < 0) return null
                when (id) {
                    "fmt " -> if (pos + 16 <= bytes.size) byteRate = b.getInt(pos + 16)
                    "data" -> return if (byteRate > 0) minOf(size, bytes.size - pos - 8).toDouble() / byteRate else null
                }
                pos += 8 + size + (size and 1)
            }
            return null
        }
    }
}

fun Route.voiceSampleAdminRoutes(s: Services) {
    get("/voice-samples") { call.respond(s.voiceSamples.list()) }
    post("/voice-samples") {
        val q = call.request.queryParameters
        val bytes = call.receiveChannel().toInputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= VoiceSamples.MAX_BYTES) { "La muestra es demasiado grande (máximo 5 MB)" }
            }
            out.toByteArray()
        }
        val id = s.voiceSamples.add(call.user, bytes, q["name"].orEmpty(), q["lang"].orEmpty(), q["gender"].orEmpty(), q["license"].orEmpty(), q["source"].orEmpty())
        call.respond(mapOf("id" to id))
    }
    delete("/voice-samples/{id}") {
        s.voiceSamples.delete(call.user, call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("No válido"))
        call.respond(mapOf("ok" to true))
    }
}

/** The worker fetches a sample to clone (inside the authenticated /worker routes). */
fun Route.workerVoiceSampleRoute(s: Services) {
    get("/voice-samples/{id}") {
        val file = call.parameters["id"]?.toLongOrNull()?.let(s.voiceSamples::file) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondFile(file)
    }
}
