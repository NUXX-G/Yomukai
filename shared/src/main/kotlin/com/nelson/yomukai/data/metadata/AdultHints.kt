package com.nelson.yomukai.data.metadata

/**
 * Titles that say outright they are sexual content, for sites that do not tag it (the genre filter cannot see what is
 * not tagged). Only unambiguous words, in Spanish and English, as whole words: "Sexto sentido" or "Essex" are not
 * caught. Pure, unit tested. Ecchi is not sexual content for this purpose.
 */
object AdultHints {
    private val EXPLICIT = Regex(
        """\b(sexo|sexual(?:es)?|sex|sexy|porno|porn|pornograf[ií]a|hentai|xxx|nsfw|milf|follar|follando|cogiendo|""" +
            """org[ií]as?|orgy|swingers?|cuckold|cornud[oa]|incesto|incest|violaci[oó]n|esclava sexual|r-?18|\+18)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isExplicit(title: String): Boolean = EXPLICIT.containsMatchIn(title)
}
