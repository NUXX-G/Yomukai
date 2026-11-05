package com.nelson.yomukai.worker

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The worker's side of the /worker API on the VPS. Every call carries the worker token. */
class VpsClient(private val baseUrl: String, private val token: String, val http: OkHttpClient = defaultClient()) {
    private val json = "application/json".toMediaType()

    private fun request(path: String) = Request.Builder().url("$baseUrl$path").header("Authorization", "Bearer $token").header("User-Agent", "Yomukai-Worker/$VERSION")

    private fun call(request: Request): Pair<Int, String> = http.newCall(request).execute().use { r ->
        if (r.code == 401) throw IOException("El VPS rechaza el token del worker (¿revocado?)")
        r.code to r.body?.string().orEmpty()
    }

    fun hello(capabilities: JsonObject): JsonObject {
        val body = JsonObject().apply { addProperty("version", VERSION); add("capabilities", capabilities) }
        val (code, text) = call(request("/worker/hello").post(body.toString().toRequestBody(json)).build())
        if (code != 200) throw IOException("hello: HTTP $code")
        return JsonParser.parseString(text).asJsonObject
    }

    /** The next job, or null when there is none for us. */
    fun claim(): ClaimedJob? {
        val (code, text) = call(request("/worker/claim").post("{}".toRequestBody(json)).build())
        if (code == 204) return null
        if (code != 200) throw IOException("claim: HTTP $code")
        val o = JsonParser.parseString(text).asJsonObject
        return ClaimedJob(o.get("id").asLong, o.get("type").asString, o.getAsJsonObject("payload") ?: JsonObject(), o.get("attempt")?.asInt ?: 1)
    }

    /** False when the VPS no longer considers the job ours (it was reassigned): stop working on it. */
    fun heartbeat(jobId: Long): Boolean = call(request("/worker/jobs/$jobId/heartbeat").post("{}".toRequestBody(json)).build()).first == 200

    fun upload(jobId: Long, output: JobOutput) {
        val (code, text) = call(request("/worker/jobs/$jobId/result").put(output.file.asRequestBody(output.contentType.toMediaType())).build())
        if (code != 200) throw IOException("Subida rechazada (HTTP $code): ${text.take(200)}")
    }

    fun fail(jobId: Long, error: String, retry: Boolean) {
        val body = JsonObject().apply { addProperty("error", error.take(900)); addProperty("retry", retry) }
        call(request("/worker/jobs/$jobId/fail").post(body.toString().toRequestBody(json)).build())
    }

    /** Saves the newest VPS database backup into [dir] unless it is already there; returns the file if new. */
    fun pullBackup(dir: File): File? {
        http.newCall(request("/worker/backups/latest").get().build()).execute().use { r ->
            if (r.code == 204) return null
            if (!r.isSuccessful) throw IOException("copia: HTTP ${r.code}")
            val name = Regex("""filename="(yomukai-\d{8}-\d{6}\.db\.gz)"""").find(r.header("Content-Disposition").orEmpty())?.groupValues?.get(1)
                ?: throw IOException("Nombre de copia inesperado")
            val target = File(dir.apply { mkdirs() }, name)
            if (target.exists()) return null
            val partial = File(dir, "$name.partial")
            r.body!!.byteStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (!partial.renameTo(target)) throw IOException("No se pudo guardar la copia en $dir")
            return target
        }
    }

    companion object {
        const val VERSION = "1.0"
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}
