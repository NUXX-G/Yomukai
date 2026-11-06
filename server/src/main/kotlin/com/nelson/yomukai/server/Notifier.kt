package com.nelson.yomukai.server

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Warnings for the admin (worker gone, bandwidth near the cap, broken source...): a Discord webhook and/or an ntfy
 * topic, both optional. Sending never blocks the caller and a failure only logs. The same message is not repeated
 * within [quietMs] so a flapping problem cannot flood the channel.
 */
class Notifier(
    private val discordWebhook: String?,
    private val ntfyUrl: String?,
    private val ntfyToken: String? = null,
    private val quietMs: Long = 30 * 60_000L,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
) {
    private val log = LoggerFactory.getLogger("Notifier")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val enabled: Boolean get() = discordWebhook != null || ntfyUrl != null

    /** [key] groups repeats of the same problem; [urgent] raises the ntfy priority. */
    fun admin(key: String, title: String, message: String, urgent: Boolean = false, now: Long = System.currentTimeMillis()) {
        log.info("Aviso [{}]: {} — {}", key, title, message)
        if (!enabled || !shouldSend(key, now)) return
        scope.launch {
            discordWebhook?.let { url ->
                val body = JsonObject().apply { addProperty("content", "**${clip(title, 200)}**\n${clip(message, 1800)}") }
                post(url, body.toString(), "application/json")
            }
            ntfyUrl?.let { url ->
                val request = Request.Builder().url(url)
                    // ntfy takes the title from a header; non-ASCII must go RFC 2047-free, so it is kept to ASCII-safe text.
                    .header("Title", asciiHeader(title))
                    .header("Priority", if (urgent) "high" else "default")
                    .apply { ntfyToken?.let { header("Authorization", "Bearer $it") } }
                    .post(clip(message, 3500).toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()
                send(request)
            }
        }
    }

    /** A message for the group's Discord channel (rooms, new threads); repeats within [quietMs] are dropped. */
    fun public(key: String, message: String, now: Long = System.currentTimeMillis()) {
        val url = discordWebhook ?: return
        if (!shouldSend(key, now)) return
        scope.launch { post(url, JsonObject().apply { addProperty("content", clip(message, 1800)) }.toString(), "application/json") }
    }

    internal fun shouldSend(key: String, now: Long): Boolean {
        val previous = lastSent[key]
        if (previous != null && now - previous < quietMs) return false
        lastSent[key] = now
        if (lastSent.size > 1_000) lastSent.entries.removeIf { now - it.value > quietMs }
        return true
    }

    private fun post(url: String, body: String, type: String) =
        send(Request.Builder().url(url).post(body.toRequestBody(type.toMediaType())).build())

    private fun send(request: Request) {
        runCatching { client.newCall(request).execute().use { if (!it.isSuccessful) log.warn("Aviso rechazado: HTTP {}", it.code) } }
            .onFailure { log.warn("No se pudo enviar el aviso: {}", it.message) }
    }

    companion object {
        private fun clip(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"
        fun asciiHeader(text: String): String =
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("[^\\x20-\\x7E]"), "").take(200)
    }
}
