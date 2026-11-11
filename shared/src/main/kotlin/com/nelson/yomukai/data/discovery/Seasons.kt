package com.nelson.yomukai.data.discovery

/**
 * One way of writing a season or a part: «2nd Season», «Season 2», «Temporada 2», «Segunda temporada», «2ª
 * temporada» all become «season 2»; «Part 2» / «Parte 2» become «part 2». Sites and databases write them each their
 * own way, so names compared with this (sequels against AniList, for example) find each other. Pure, unit tested.
 */
object Seasons {
    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "primera" to 1, "segunda" to 2, "tercera" to 3, "cuarta" to 4, "quinta" to 5, "sexta" to 6
    )
    private val ORDINAL_WORD = ORDINALS.keys.joinToString("|")
    private val NTH_SEASON = Regex("""\b(\d{1,2})\s*(?:st|nd|rd|th|ª|a|º|o)?\.?\s+(?:season|temporada)\b""", RegexOption.IGNORE_CASE)

    // «S2» only stuck to its number (so «Sister's 2 Lives» is left alone).
    private val SEASON_N = Regex("""\b(?:(?:season|temporada)\s*|s)(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val WORD_SEASON = Regex("""\b($ORDINAL_WORD)\s+(?:season|temporada)\b""", RegexOption.IGNORE_CASE)
    private val PART_N = Regex("""\b(?:part|parte|cour)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val NTH_PART = Regex("""\b(\d{1,2})\s*(?:st|nd|rd|th|ª|a)?\s+(?:part|parte|cour)\b""", RegexOption.IGNORE_CASE)

    // «Tensei shitara Ken deshita II» (AnimeFLV and others): a Roman numeral closing the title is its season. Only at
    // the very end, so «Final Fantasy VII: Advent Children» keeps its name.
    private val ROMAN_END = Regex("""\s(II|III|IV|V|VI)\s*$""")
    private val ROMANS = mapOf("II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6)

    fun canonical(title: String): String = title.trim()
        .replace(ROMAN_END) { " season ${ROMANS.getValue(it.groupValues[1])} " }
        .replace(WORD_SEASON) { " season ${ORDINALS[it.groupValues[1].lowercase()]} " }
        .replace(NTH_SEASON) { " season ${it.groupValues[1].toInt()} " }
        .replace(SEASON_N) { " season ${it.groupValues[1].toInt()} " }
        .replace(NTH_PART) { " part ${it.groupValues[1].toInt()} " }
        .replace(PART_N) { " part ${it.groupValues[1].toInt()} " }
        .replace(Regex("\\s+"), " ").trim()
}
