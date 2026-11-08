package com.nelson.yomukai.data.speech

/** A piece of a paragraph to read aloud: narration, or a line of dialogue and who seems to say it. */
data class SpeechPart(val text: String, val dialogue: Boolean, val gender: Gender = Gender.UNKNOWN)

enum class Gender { FEMALE, MALE, UNKNOWN }

/**
 * Splits novel text for reading aloud with one voice per role: the narrator, and the characters,
 * a woman's voice when the text says a woman speaks ("—dijo ella", "she said"), a man's when a man
 * does. The same rules as the web app (web/src/lib/speech.svelte.js).
 */
object Dialogue {
    private const val L = "\\p{L}"

    // What JavaScript's \s matches (Java's leaves out no-break and wide spaces): the web cuts text with it, and the
    // server's audio is only reused when both cut the same way.
    const val SPACE_CHARS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val WS = "[$SPACE_CHARS]"
    private const val NOT_WS = "[^$SPACE_CHARS]"
    private val FEMALE = Regex(
        "(?<!$L)(ella|ellas|la$WS+(?:chica|mujer|joven|niña|muchacha|señora|señorita|princesa|reina|madre|hermana|maga|doncella|dama|anciana|emperatriz|santa|bruja|diosa|maestra|profesora|doctora|capitana)|she|her|the$WS+(?:girl|woman|lady|princess|queen|mother|sister|witch|goddess|maid))(?!$L)",
        RegexOption.IGNORE_CASE
    )
    private val MALE = Regex(
        "(?<!$L)(él|ellos|el$WS+(?:chico|hombre|joven|niño|muchacho|señor|príncipe|rey|padre|hermano|mago|caballero|anciano|emperador|santo|brujo|dios|maestro|profesor|doctor|capitán|duque)|he|him|his|the$WS+(?:boy|man|lord|prince|king|father|brother|wizard|god|knight))(?!$L)",
        RegexOption.IGNORE_CASE
    )
    private val DASH_START = Regex("^[—–―-]$WS*$NOT_WS")
    private val DASH_LEAD = Regex("^[—–―-]$WS*")
    private val DASH = Regex("$WS*[—–―]$WS*")
    private val QUOTED = Regex("«([^»]+)»|“([^”]+)”|\"([^\"]+)\"")
    private val WORDY = Regex("[\\p{L}\\p{N}]")
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u2060-\\u2064\\uFEFF]")
    private val ESCAPE = Regex("\\\\([\\[\\]()*_~`#>-])")
    private val RULE = Regex("[*_~#=]{3,}")
    private val SPACES = Regex("$WS+")
    private val SENTENCE = Regex("[^.!?…]+[.!?…]+[\"»”')\\]]*$WS*|[^.!?…]+$")

    fun genderHint(narration: String): Gender {
        val f = FEMALE.containsMatchIn(narration)
        val m = MALE.containsMatchIn(narration)
        return when {
            f && !m -> Gender.FEMALE
            m && !f -> Gender.MALE
            else -> Gender.UNKNOWN
        }
    }

    /** Text as it should be heard: no invisible watermark characters, markdown escapes or "***". */
    fun speakable(text: String): String =
        text.replace(INVISIBLE, "").replace(ESCAPE, "$1").replace(RULE, " ").replace(SPACES, " ").trim()

    fun hasWords(text: String) = WORDY.containsMatchIn(text)

    fun split(paragraph: String): List<SpeechPart> {
        val text = paragraph.trim()
        val parts = mutableListOf<SpeechPart>()
        fun add(t: String, dialogue: Boolean) {
            val clean = t.trim()
            if (clean.isNotEmpty() && hasWords(clean)) parts += SpeechPart(clean, dialogue)
        }
        when {
            DASH_START.containsMatchIn(text) ->
                text.replace(DASH_LEAD, "").split(DASH).forEachIndexed { i, piece -> add(piece, i % 2 == 0) }
            text.any { it == '«' || it == '“' || it == '"' } -> {
                var last = 0
                QUOTED.findAll(text).forEach { m ->
                    add(text.substring(last, m.range.first), false)
                    add(m.groupValues.drop(1).first { it.isNotEmpty() }, true)
                    last = m.range.last + 1
                }
                add(text.substring(last), false)
            }
            else -> add(text, false)
        }
        if (parts.isEmpty()) return listOf(SpeechPart(text, false))
        val gender = genderHint(parts.filterNot { it.dialogue }.joinToString(" ") { it.text })
        return parts.map { if (it.dialogue) it.copy(gender = gender) else it }
    }

    /** Long text in pieces of whole sentences (~[max] characters), so the first one sounds sooner. */
    fun chunks(text: String, max: Int = 400): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (m in SENTENCE.findAll(text)) {
            val s = m.value
            if (current.isNotEmpty() && current.length + s.length > max) { out += current.toString().trim(); current = StringBuilder() }
            current.append(s)
            while (current.length > max * 2) {
                out += current.substring(0, max).trim()
                current = StringBuilder(current.substring(max))
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }
}
