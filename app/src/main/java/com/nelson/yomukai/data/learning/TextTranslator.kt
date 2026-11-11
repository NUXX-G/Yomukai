package com.nelson.yomukai.data.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nelson.yomukai.data.ocr.DeviceOcr
import com.nelson.yomukai.data.sync.ServerSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's side of [TranslationChain] (F6.3): the offline dictionary, ML Kit on the phone (its model downloads once
 * per language pair; a first time that takes too long goes on to the next step) and the server when connected.
 */
class TextTranslator(private val dictionary: OfflineDictionary, private val ocr: DeviceOcr, private val sync: ServerSync) {
    suspend fun translate(text: String, from: String, to: String): TranslationChain.Result = TranslationChain.translate(
        text, from, to,
        dictionary = { lang, word -> quiet { dictionary.lookup(lang, word).second } },
        device = { t, f, d -> quiet { withTimeoutOrNull(DEVICE_WAIT_MS) { ocr.translate(listOf(t), f, d)?.firstOrNull() } } },
        server = { t, f, d ->
            if (sync.serverUrl == null) null else quiet {
                val body = JsonObject().apply { add("texts", JsonArray().apply { add(t) }); addProperty("from", f); addProperty("to", d) }
                val answer = sync.api("POST", "/api/translate", body)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@quiet null
                val first = answer.getAsJsonArray("translations")?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString ?: return@quiet null
                first to if (answer.get("via")?.asString == "online") TranslationChain.Via.ONLINE else TranslationChain.Via.SERVER
            }
        }
    )

    private suspend fun <T> quiet(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val DEVICE_WAIT_MS = 20_000L
    }
}
