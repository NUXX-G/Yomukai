package com.nelson.yomukai.server

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Last step of the translation chain (F6.3) for short selections when the home worker is off and the browser has no
 * translator of its own (Safari, Firefox): MyMemory's free public API (api.mymemory.translated.net), anonymous, which
 * allows ~5000 characters a day per address. Only what someone selected and asked to translate is sent, never whole
 * chapters; behind the admin switch `online_translate`. Light: one small HTTP request, answers kept in memory.
 */
class OnlineTranslator(
    private val http: HttpFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now
) {
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }
    private val perUser = ConcurrentHashMap<Long, ArrayDeque<Long>>()

    @Volatile private var day: LocalDate = LocalDate.MIN

    @Volatile private var usedToday = 0

    /** Translations in the same places, or null when it cannot (language unknown, too long, over the budget, failure). */
    suspend fun translate(userId: Long, texts: List<String>, from: String, to: String): List<String>? {
        // "es", "en-US", "pt_BR": a real language; "auto" is not one (MyMemory needs to be told).
        if (!LANG.matches(from.lowercase()) || !LANG.matches(to.lowercase())) return null
        val src = from.lowercase().take(2)
        val dst = to.lowercase().take(2)
        if (src == dst) return null
        val clean = texts.map { it.replace(Regex("\\s+"), " ").trim() }
        if (clean.size > MAX_TEXTS || clean.any { it.length > MAX_CHARS } || clean.all { it.isEmpty() }) return null
        if (!allowed(userId)) return null
        return clean.map { text ->
            if (text.isEmpty()) return@map ""
            val key = "$src|$dst|$text"
            synchronized(cache) { cache[key] }?.let { return@map it }
            if (!spend(text.length)) return null
            val url = "https://api.mymemory.translated.net/get?q=${enc(text)}&langpair=${enc("$src|$dst")}"
            val answer = runCatching { parse(http.getString(url, emptyMap(), cloudflareFallback = false)) }.getOrNull() ?: return null
            synchronized(cache) { cache[key] = answer }
            answer
        }
    }

    private fun allowed(userId: Long): Boolean {
        val now = clock()
        val times = perUser.getOrPut(userId) { ArrayDeque() }
        synchronized(times) {
            while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
            if (times.size >= MAX_PER_MINUTE) return false
            times.addLast(now)
        }
        return true
    }

    @Synchronized private fun spend(chars: Int): Boolean {
        val d = today()
        if (d != day) { day = d; usedToday = 0 }
        if (usedToday + chars > DAILY_CHARS) return false
        usedToday += chars
        return true
    }

    companion object {
        const val MAX_CHARS = 500
        const val MAX_TEXTS = 4
        const val MAX_PER_MINUTE = 20

        /** Under MyMemory's anonymous daily allowance, shared by the whole server. */
        const val DAILY_CHARS = 4_500
        private const val CACHE_SIZE = 2_000
        private val LANG = Regex("[a-z]{2}([-_][a-z0-9]{2,8})?")
        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        /** MyMemory's answer: the text, without the HTML entities it sometimes adds; null for its error messages. */
        fun parse(json: String): String? {
            val o = JsonParser.parseString(json).asJsonObject
            val status = o.get("responseStatus")?.takeIf { it.isJsonPrimitive }?.asString
            if (status != "200") return null
            if (o.get("quotaFinished")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return null
            val text = o.getAsJsonObject("responseData")?.get("translatedText")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            if (text.startsWith("MYMEMORY WARNING") || text.startsWith("QUERY LENGTH LIMIT")) return null
            return text.replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").take(MAX_CHARS * 3)
        }
    }
}
