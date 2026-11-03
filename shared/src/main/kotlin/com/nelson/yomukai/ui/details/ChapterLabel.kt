package com.nelson.yomukai.ui.details

import com.nelson.yomukai.data.metadata.ChapterIntegrity
import com.nelson.yomukai.data.search.SmartSearch

/** How a chapter row reads: a short label ("Cap. 133") and the chapter's own name when it has one. */
data class ChapterLabel(val short: String, val name: String?)

/**
 * Sources repeat a lot in chapter titles: "Solo Leveling - Capítulo 133: El regreso", "Chapter 133",
 * "Cap. 133 - Cap. 133". This keeps the number as "Cap. 133" (or "Ep. 12") and only the part that
 * says something more as the name. Pure.
 */
object ChapterLabels {
    private val PREFIX = Regex(
        """^\s*(?:cap[ií]tulo|capitulo|chapter|chap|cap|ch|episodio|episode|ep|vol(?:umen|ume)?\.?\s*\d+\s*(?:cap[ií]tulo|chapter|cap|ch)?)\.?\s*#?\s*(\d+(?:[.,]\d+)?)\s*""",
        RegexOption.IGNORE_CASE
    )
    private val LEADING_NUMBER = Regex("""^\s*#?(\d+(?:[.,]\d+)?)\s*""")
    private val SEPARATORS = Regex("""^[\s:|·•\-–—_.,]+|[\s:|·•\-–—_.,]+$""")

    fun of(title: String, number: Float, isAnime: Boolean, mediaTitle: String = ""): ChapterLabel {
        val prefix = if (isAnime) "Ep." else "Cap."
        var rest = title.trim()
        // The title of the work itself, repeated in front of every chapter.
        if (mediaTitle.isNotBlank() && rest.length > mediaTitle.length &&
            SmartSearch.normalize(rest).startsWith(SmartSearch.normalize(mediaTitle))
        ) {
            rest = rest.drop(mediaTitle.length).replace(SEPARATORS, "")
        }
        val numberText = PREFIX.find(rest)?.let { match ->
            rest = rest.substring(match.range.last + 1)
            match.groupValues[1]
        } ?: LEADING_NUMBER.find(rest)?.takeIf { number > 0f }?.let { match ->
            rest = rest.substring(match.range.last + 1)
            match.groupValues[1]
        }
        rest = rest.replace(SEPARATORS, "")
        // "Cap. 133 - Cap. 133" and "133 - Capítulo 133" say the number twice.
        PREFIX.find(rest)?.takeIf { it.range.first == 0 && it.groupValues[1] == numberText }?.let {
            rest = rest.substring(it.range.last + 1).replace(SEPARATORS, "")
        }
        val shown = when {
            number > 0f -> ChapterIntegrity.format(number)
            numberText != null -> numberText.replace('.', ',')
            else -> null
        }
        if (shown == null) return ChapterLabel(title.trim().ifBlank { prefix }, null)
        // The number said again anywhere in the rest ("SL - Capítulo 11") adds nothing.
        val digits = (numberText ?: shown).replace(',', '.')
        Regex("""(?i)(?:cap[ií]tulo|capitulo|chapter|chap|cap|ch|episodio|episode|ep)\.?\s*#?\s*${Regex.escape(digits)}(?![\d.,])""")
            .find(rest)?.let { repeated ->
                val before = rest.substring(0, repeated.range.first).replace(SEPARATORS, "")
                val after = rest.substring(repeated.range.last + 1).replace(SEPARATORS, "")
                // What comes before the repeated number is usually the work's initials ("SL").
                rest = listOf(before.takeUnless { it.isBlank() || isAbbreviationOf(it, mediaTitle) }, after.ifBlank { null })
                    .filterNotNull().joinToString(" ")
            }
        rest = rest.replace(SEPARATORS, "").trim()
        val name = rest.takeIf {
            it.isNotBlank() && SmartSearch.normalize(it) != SmartSearch.normalize(shown) && !isAbbreviationOf(it, mediaTitle)
        }
        return ChapterLabel("$prefix $shown", name)
    }

    /** "SL" for "Solo Leveling", or a leftover too short to be a name. */
    private fun isAbbreviationOf(text: String, mediaTitle: String): Boolean {
        val key = SmartSearch.normalize(text).replace(" ", "")
        if (key.length <= 2) return true
        val initials = SmartSearch.tokens(mediaTitle).joinToString("") { it.take(1) }
        return initials.length >= 2 && key == initials
    }
}
