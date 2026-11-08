package com.nelson.yomukai.server

import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Natural English voices in the browser (Kokoro-82M on WebAssembly): the model and voices are fetched from Hugging Face
 * once, checked against [KokoroFiles] and kept here, so a browser only downloads from this server (nothing from third
 * parties, nothing unchecked). Serving files is light; the speech itself runs on each viewer's device.
 */
class KokoroModels(dataDir: File, private val client: HttpClient = defaultClient()) {
    private val dir = File(dataDir, "cache/kokoro").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The local copy of [name] (fetched and checked the first time), or null when it is not one of the allowed files. */
    suspend fun file(name: String): File? {
        val spec = KokoroFiles.FILES[name] ?: return null
        val target = File(dir, name.replace('/', '_'))
        if (target.isFile) return target
        return locks.getOrPut(name) { Mutex() }.withLock {
            if (target.isFile) return@withLock target
            withContext(Dispatchers.IO) { fetch(spec, target) }
            target
        }
    }

    private fun fetch(spec: KokoroFiles.KokoroFile, target: File) {
        val url = "https://huggingface.co/${KokoroFiles.REPO}/resolve/${KokoroFiles.REVISION}/${spec.path}"
        val response = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) { response.body().close(); throw IOException("Hugging Face respondió ${response.statusCode()}") }
        val part = File(target.path + ".part")
        try {
            val sha = response.body().use { input -> part.outputStream().use { out -> copyHashed(input, out, spec.maxBytes) } }
            if (sha != spec.sha256) throw IOException("El archivo de voz no es el esperado (SHA-256)")
            if (!part.renameTo(target)) throw IOException("No se pudo guardar")
        } finally {
            part.delete()
        }
    }

    /** Copies at most [max] bytes and returns their SHA-256 (hex). */
    private fun copyHashed(input: java.io.InputStream, out: java.io.OutputStream, max: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("Archivo de voz demasiado grande")
            digest.update(buf, 0, n)
            out.write(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        fun defaultClient(): HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    }
}

/** GET /api/tts/kokoro/{model | tokenizer.json | voices/<id>.bin}: only the allowed files, cached for a year. */
fun Route.kokoroRoutes(s: Services) {
    get("/tts/kokoro/voices") {
        if (!s.features.isOn("tts_browser")) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador"))
        call.respond(mapOf("voices" to KokoroFiles.VOICES))
    }
    get("/tts/kokoro/{path...}") {
        if (!s.features.isOn("tts_browser")) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador"))
        val name = call.parameters.getAll("path").orEmpty().joinToString("/")
        val file = runCatching { s.kokoro.file(name) }.getOrElse { return@get call.respond(HttpStatusCode.BadGateway, mapOf("error" to (it.message ?: "Error"))) }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Private))
        call.respondFile(file)
    }
}
