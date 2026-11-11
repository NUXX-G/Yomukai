package com.nelson.yomukai.data.speech

/**
 * Text as a voice should say it (F6.1), Spanish and English: numbers in words («12» → «doce», «1990» → «mil
 * novecientos noventa», «3,5» → «tres coma cinco», «50 %» → «cincuenta por ciento», «1.º» → «primero»), common
 * abbreviations («Sr.» → «señor», «Cap. 3» → «capítulo tres», «Mr.» → «mister»), and pauses that engines stumble
 * on («...» → «…», «!!!» → «!», a dash opening a line of dialogue → nothing). The web has the same rules
 * (web/src/lib/speech-text.js) and both are checked against shared/src/test/resources/speech_text.json.
 */
object SpeechText {
    fun normalize(text: String, lang: String): String {
        val es = !lang.lowercase().startsWith("en")
        var s = text
        s = s.replace(Regex("\\.{3,}"), "…").replace(Regex("([!?¡¿])\\1+"), "$1").replace(Regex("[*~_]{2,}"), " ")
        // A dash that opens a line is a dialogue mark (nothing to say); between words it is a short pause.
        s = s.replace(Regex("(^|\\n)\\s*[—–]\\s*"), "$1").replace(Regex("\\s*[—–]\\s*"), ", ").replace(Regex(",\\s*([,.;:!?…])"), "$1")
        // «etc.» at the end of a sentence keeps its full stop (the pause), elsewhere it is just the word.
        s = Regex("(?<![\\p{L}])etc\\.(?=\\s+\\p{Lu}|\\s*$)").replace(s, if (es) "etcétera." else "et cetera.")
        for ((pattern, words) in if (es) ABBR_ES else ABBR_EN) s = pattern.replace(s, words)
        s = PERCENT.replace(s) { m -> number(m.groupValues[1], es) + if (es) " por ciento" else " percent" }
        if (es) s = ORDINAL_ES.replace(s) { m -> ordinalEs(m.groupValues[1].toInt(), m.groupValues[2]) ?: m.value }
        s = NUMBER.replace(s) { m -> number(m.value, es) }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** "12", "3,5", "3.5", "1.234" (Spanish thousands) as words; numbers too long are read digit by digit. */
    private fun number(raw: String, es: Boolean): String {
        val (int, frac) = splitDecimal(raw, es)
        val whole = int.toLongOrNull()?.takeIf { int.length <= 12 } ?: return int.map { digit(it, es) }.joinToString(" ")
        val words = when {
            es -> cardinalEs(whole)
            frac.isEmpty() && raw.length == 4 && whole in 1100..2099 -> yearEn(whole)
            else -> cardinalEn(whole)
        }
        if (frac.isEmpty()) return words
        return words + (if (es) " coma " else " point ") + frac.map { digit(it, es) }.joinToString(" ")
    }

    /**
     * Integer and decimal digits. A separator followed by exactly three digits is a thousands mark in the language's
     * own style («1.234» in Spanish, «1,234» in English); anything else is a decimal point, whichever sign it uses
     * («3.5» online in a Spanish text is still «tres coma cinco»).
     */
    private fun splitDecimal(raw: String, es: Boolean): Pair<String, String> {
        val thousands = if (es) '.' else ','
        val groups = raw.split('.', ',')
        if (groups.size == 1) return raw to ""
        val seps = raw.filter { it == '.' || it == ',' }
        val allThousands = groups.drop(1).all { it.length == 3 } && seps.all { it == thousands }
        if (allThousands) return groups.joinToString("") to ""
        // The last separator is the decimal one; the ones before it, thousands.
        return groups.dropLast(1).joinToString("") to groups.last()
    }

    private fun digit(c: Char, es: Boolean) = (if (es) UNITS_ES else UNITS_EN).getOrElse(c.digitToIntOrNull() ?: 0) { "" }

    fun cardinalEs(n: Long): String = when {
        n < 0 -> "menos " + cardinalEs(-n)
        n < 30 -> UNITS_ES[n.toInt()]
        n < 100 -> TENS_ES[(n / 10).toInt()] + if (n % 10 == 0L) "" else " y " + UNITS_ES[(n % 10).toInt()]
        n == 100L -> "cien"
        n < 1_000 -> HUNDREDS_ES[(n / 100).toInt()] + if (n % 100 == 0L) "" else " " + cardinalEs(n % 100)
        n < 1_000_000 -> (if (n / 1_000 == 1L) "mil" else apocope(cardinalEs(n / 1_000)) + " mil") + if (n % 1_000 == 0L) "" else " " + cardinalEs(n % 1_000)
        n < 1_000_000_000_000 -> (if (n / 1_000_000 == 1L) "un millón" else apocope(cardinalEs(n / 1_000_000)) + " millones") +
            if (n % 1_000_000 == 0L) "" else " " + cardinalEs(n % 1_000_000)
        else -> n.toString()
    }

    /** «veintiuno mil» → «veintiún mil», «uno millones» never happens (handled above). */
    private fun apocope(words: String) = words.replace(Regex("veintiuno$"), "veintiún").replace(Regex("uno$"), "un")

    fun cardinalEn(n: Long): String = when {
        n < 0 -> "minus " + cardinalEn(-n)
        n < 20 -> UNITS_EN[n.toInt()]
        n < 100 -> TENS_EN[(n / 10).toInt()] + if (n % 10 == 0L) "" else "-" + UNITS_EN[(n % 10).toInt()]
        n < 1_000 -> UNITS_EN[(n / 100).toInt()] + " hundred" + if (n % 100 == 0L) "" else " " + cardinalEn(n % 100)
        n < 1_000_000 -> cardinalEn(n / 1_000) + " thousand" + if (n % 1_000 == 0L) "" else " " + cardinalEn(n % 1_000)
        n < 1_000_000_000_000 -> cardinalEn(n / 1_000_000) + " million" + if (n % 1_000_000 == 0L) "" else " " + cardinalEn(n % 1_000_000)
        else -> n.toString()
    }

    /** A plain four-digit number in English is read as a year: «1990» nineteen ninety, «1905» nineteen oh five. */
    fun yearEn(n: Long): String {
        val hi = n / 100
        val lo = n % 100
        return when {
            n % 1000 == 0L || (n in 2000..2009) -> cardinalEn(n)
            lo == 0L -> cardinalEn(hi) + " hundred"
            lo < 10 -> cardinalEn(hi) + " oh " + cardinalEn(lo)
            else -> cardinalEn(hi) + " " + cardinalEn(lo)
        }
    }

    private fun ordinalEs(n: Int, mark: String): String? {
        val word = ORDINALS_ES.getOrNull(n) ?: return null
        val feminine = mark == "ª"
        return if (feminine) word.dropLast(1) + "a" else word
    }

    private val NUMBER = Regex("(?<![\\p{L}\\d])\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d+)?(?![\\p{L}\\d])|(?<![\\p{L}\\d])\\d+(?:[.,]\\d+)?(?![\\p{L}\\d])")
    private val PERCENT = Regex("(\\d+(?:[.,]\\d+)?)\\s?%")
    private val ORDINAL_ES = Regex("(?<!\\d)(\\d{1,2})\\.?([ºª])")

    private val UNITS_ES = listOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince",
        "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco",
        "veintiséis", "veintisiete", "veintiocho", "veintinueve"
    )
    private val TENS_ES = listOf("", "", "veinte", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val HUNDREDS_ES = listOf("", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos")
    private val ORDINALS_ES = listOf("", "primero", "segundo", "tercero", "cuarto", "quinto", "sexto", "séptimo", "octavo", "noveno", "décimo")
    private val UNITS_EN = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
    )
    private val TENS_EN = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun abbr(vararg pairs: Pair<String, String>) = pairs.map { (a, w) -> Regex("(?<![\\p{L}])" + Regex.escape(a) + "(?![\\p{L}])") to w }

    private val ABBR_ES = abbr(
        "EE. UU." to "Estados Unidos", "EE.UU." to "Estados Unidos", "Srta." to "señorita", "Sra." to "señora", "Sr." to "señor",
        "Dra." to "doctora", "Dr." to "doctor", "Ud." to "usted", "Uds." to "ustedes", "etc." to "etcétera", "aprox." to "aproximadamente",
        "Cap." to "capítulo", "cap." to "capítulo", "pág." to "página", "núm." to "número", "nº" to "número", "Nº" to "número",
        "vs." to "contra", "p. ej." to "por ejemplo", "Vol." to "volumen", "vol." to "volumen"
    )
    private val ABBR_EN = abbr(
        "Mrs." to "missus", "Mr." to "mister", "Ms." to "miz", "Dr." to "doctor", "St." to "saint", "vs." to "versus", "etc." to "et cetera",
        "e.g." to "for example", "i.e." to "that is", "Ch." to "chapter", "Vol." to "volume", "No." to "number"
    )
}
