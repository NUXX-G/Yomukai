package com.nelson.yomukai.data.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Offline dictionaries built from Wiktionary through kaikki.org (CC BY-SA). The home worker turns one language's dump
 * (gigabytes) into a small SQLite file with [SCHEMA]; the server keeps it and answers lookups, and the app downloads
 * the same file to look words up without a connection.
 */
object Dictionary {
    /** Languages offered, by ISO code, with kaikki.org's name for them. */
    val LANGUAGES = linkedMapOf(
        "en" to "English", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean", "fr" to "French",
        "de" to "German", "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian"
    )

    fun dumpUrl(lang: String): String? = LANGUAGES[lang]?.let { "https://kaikki.org/dictionary/$it/kaikki.org-dictionary-$it.jsonl" }

    const val SCHEMA = "CREATE TABLE entries(key TEXT NOT NULL, word TEXT NOT NULL, pos TEXT NOT NULL, pron TEXT NOT NULL, " +
        "glosses TEXT NOT NULL, es TEXT NOT NULL)"
    const val INDEX = "CREATE INDEX entries_key ON entries(key)"
    const val LOOKUP = "SELECT word, pos, pron, glosses, es FROM entries WHERE key = ? LIMIT 8"

    /** One meaning group of a word: [glosses] in English (Wiktionary's), [es] its Spanish translations if any. */
    data class Entry(val word: String, val pos: String, val pron: String, val glosses: List<String>, val es: List<String>)

    /** What a lookup is keyed by: lower case, trimmed, without the punctuation that sticks to words in a text. */
    fun key(word: String): String = word.trim().trim { !it.isLetterOrDigit() }.lowercase()

    /**
     * Keys to try for a pressed text, best first: the text itself, and for scripts without spaces (Chinese, Japanese)
     * each shorter start of it, so a press inside a sentence still finds the longest word there.
     */
    fun candidates(key: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val cjk = key.any { Character.UnicodeScript.of(it.code) in CJK }
        if (!cjk) return listOf(key)
        return (minOf(key.length, MAX_CJK) downTo 1).map { key.take(it) }.let { if (key.length > MAX_CJK) listOf(key) + it else it }
    }

    private val CJK = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
    private const val MAX_CJK = 8

    private val SKIP_POS = setOf("name", "character", "symbol", "romanization", "phrase", "proverb")
    private val SKIP_TAGS = setOf("obsolete", "archaic", "rare")

    /** A line of a kaikki.org dump as an [Entry], or null for what is not worth keeping (names, phrases, no meaning). */
    fun parse(line: String): Entry? {
        val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
        val word = o.str("word").trim()
        val pos = o.str("pos")
        val tooLong = word.length > MAX_WORD || word.count { it == ' ' } > 2
        if (word.isEmpty() || tooLong || pos in SKIP_POS) return null
        val glosses = o.list("senses").mapNotNull { s ->
            val tags = s.strings("tags")
            if (tags.any { it in SKIP_TAGS }) return@mapNotNull null
            // Sub-senses repeat their parent's gloss first: the last one is the specific meaning.
            s.strings("glosses").lastOrNull()?.trim()?.take(MAX_GLOSS)?.takeIf { it.isNotEmpty() }
        }.distinct().take(MAX_GLOSSES)
        if (glosses.isEmpty()) return null
        val sounds = o.list("sounds")
        val pron = sounds.firstOrNull { "Pinyin" in it.strings("tags") && "Mandarin" in it.strings("tags") }?.str("zh_pron")
            ?.substringBefore(" (")?.takeIf { it.isNotBlank() }
            ?: sounds.firstNotNullOfOrNull { it.str("ipa").takeIf(String::isNotBlank) }.orEmpty()
        val es = o.list("translations").filter { it.str("lang_code") == "es" }.map { it.str("word").trim() }
            .filter { it.isNotEmpty() }.distinct().take(MAX_TRANSLATIONS)
        return Entry(word, pos, pron.take(MAX_PRON), glosses, es)
    }

    /** How lists are kept in one column. */
    const val SEPARATOR = "\u001f"

    private fun JsonObject.str(k: String): String = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.list(k: String): List<JsonObject> =
        get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
    private fun JsonObject.strings(k: String): List<String> =
        get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()

    private const val MAX_WORD = 40
    private const val MAX_GLOSS = 160
    private const val MAX_GLOSSES = 3
    private const val MAX_TRANSLATIONS = 4
    private const val MAX_PRON = 60
}
