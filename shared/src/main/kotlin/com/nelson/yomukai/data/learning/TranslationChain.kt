package com.nelson.yomukai.data.learning

import java.net.URLEncoder

/**
 * The translation chain of every reader (F6.3), the same order as the web's translate-chain.js:
 *   1. the offline dictionary (single words),
 *   2. the translator on the device (ML Kit in the app; the browser's on the web),
 *   3. the server (the home worker's LibreTranslate, or the free online translator when it is off),
 * and when none can, a link to a web translator for the reader to open.
 */
object TranslationChain {
    enum class Via { SAME, DICTIONARY, DEVICE, SERVER, ONLINE }

    data class Result(val text: String?, val via: Via?, val webUrl: String? = null)

    private val WORD = Regex("^[\\p{L}\\p{M}'’-]{1,40}$")

    fun isWord(text: String): Boolean = WORD.matches(text.trim())

    /** "es" from "es", "es-ES", "pt_BR"; "" when it is not a language ("auto", blank). */
    fun baseLang(lang: String?): String = lang?.takeIf { Regex("^[A-Za-z]{2}([-_].*)?$").matches(it) }?.take(2)?.lowercase().orEmpty()

    /** In Spanish the dictionary's Spanish words; in English its first English meaning; else nothing. */
    fun fromDictionary(entries: List<Dictionary.Entry>, to: String): String? = when (baseLang(to)) {
        "es" -> entries.flatMap { it.es }.distinct().take(6).takeIf { it.isNotEmpty() }?.joinToString(", ")
        "en" -> entries.flatMap { it.glosses }.firstOrNull { it.isNotBlank() }
        else -> null
    }

    fun webTranslatorUrl(text: String, from: String, to: String): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        return "https://translate.google.com/?sl=${enc(baseLang(from).ifEmpty { "auto" })}&tl=${enc(baseLang(to).ifEmpty { "es" })}&text=${enc(text.take(2_000))}&op=translate"
    }

    /**
     * Runs the chain for one text. Each step answers null when it cannot (and must not throw): [dictionary] gets
     * (language, word), [device] and [server] get (text, from, to); [server] tells whether the worker or the online
     * translator answered.
     */
    suspend fun translate(
        raw: String, from: String, to: String,
        dictionary: suspend (String, String) -> List<Dictionary.Entry>?,
        device: suspend (String, String, String) -> String?,
        server: suspend (String, String, String) -> Pair<String, Via>?
    ): Result {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        val src = baseLang(from)
        val dst = baseLang(to).ifEmpty { "es" }
        if (text.isEmpty()) return Result("", Via.SAME)
        if (src == dst) return Result(text, Via.SAME)
        if (src.isNotEmpty() && isWord(text)) {
            dictionary(src, text.lowercase())?.let { fromDictionary(it, dst) }?.let { return Result(it, Via.DICTIONARY) }
        }
        if (src.isNotEmpty()) device(text, src, dst)?.takeIf { it.isNotBlank() }?.let { return Result(it, Via.DEVICE) }
        server(text, src.ifEmpty { "auto" }, dst)?.takeIf { it.first.isNotBlank() }?.let { return Result(it.first, it.second) }
        return Result(null, null, webTranslatorUrl(text, src, dst))
    }
}
