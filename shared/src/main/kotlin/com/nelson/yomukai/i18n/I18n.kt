package com.nelson.yomukai.i18n

import com.google.gson.JsonParser

/**
 * The interface in Spanish or English, shared by the Android app (the web has the same in i18n.svelte.js, with the
 * same dictionary: resources/i18n/en.json, Spanish text → English). Texts are written in Spanish in the code and
 * wrapped in [tr]; in Spanish they come out as they are, in English they are looked up, and a text missing from the
 * dictionary shows in Spanish rather than breaking anything. Only the interface: content shows in its own language.
 */
object I18n {
    const val SPANISH = "es"
    const val ENGLISH = "en"

    @Volatile
    var lang: String = SPANISH
        private set

    @Volatile
    private var dict: Map<String, String> = emptyMap()

    /** "es", "en", or anything else for the device's language: Spanish for Spanish speakers, English for everyone else. */
    fun use(choice: String?, deviceLanguage: String = java.util.Locale.getDefault().language) {
        val wanted = when (choice) {
            SPANISH, ENGLISH -> choice
            else -> if (deviceLanguage == SPANISH) SPANISH else ENGLISH
        }
        dict = if (wanted == ENGLISH) load() else emptyMap()
        lang = wanted
    }

    private fun load(): Map<String, String> = runCatching {
        I18n::class.java.getResourceAsStream("/i18n/en.json")!!.bufferedReader().use { r ->
            JsonParser.parseReader(r).asJsonObject.entrySet().associate { (k, v) -> k to v.asString }
        }
    }.getOrDefault(emptyMap())

    /** Every key of the English dictionary (for tests). */
    fun englishKeys(): Set<String> = load().keys

    /** The text in the interface language; {0}, {1}… are filled with [args] in order. */
    fun tr(es: String, vararg args: Any?): String {
        val s = if (lang == SPANISH) es else dict[es] ?: es
        if (args.isEmpty()) return s
        return SLOT.replace(s) { m -> m.groupValues[1].toIntOrNull()?.let { args.getOrNull(it)?.toString() } ?: m.value }
    }

    private val SLOT = Regex("""\{(\d+)\}""")
}

/** Shorthand used all over the app: tr("Guardar"), tr("{0} capítulos", n). */
fun tr(es: String, vararg args: Any?): String = I18n.tr(es, *args)
