package com.nelson.yomukai.data.speech

/**
 * A paragraph as the pieces a reader speaks, each with its role (narrator, female, male): the same cut as the web's
 * planRoles (web/src/lib/dialogue.js) and the app's reader, so audio the server prepares ahead (overnight) is exactly
 * the audio asked for later. shared/src/test/resources/speech_parity.json is checked on both sides.
 */
object SpeechPlan {
    data class Piece(val text: String, val role: String)

    const val NARRATOR = "narrator"
    const val FEMALE = "female"
    const val MALE = "male"

    private val BLANK_LINE = Regex("\n[${Dialogue.SPACE_CHARS}]*\n")

    /** A chapter's text as the web reader's paragraphs: split at blank lines, like its text.split(/\n\s*\n/). */
    fun paragraphs(text: String): List<String> = text.split(BLANK_LINE).map { Dialogue.speakable(it) }.filter { it.isNotEmpty() }

    fun roles(paragraph: String, characters: Boolean = true, max: Int = 400): List<Piece> {
        val text = Dialogue.speakable(paragraph)
        if (!Dialogue.hasWords(text)) return emptyList()
        val parts = if (characters) Dialogue.split(text) else listOf(SpeechPart(text, false))
        return parts.flatMap { p ->
            val role = when {
                !p.dialogue -> NARRATOR
                p.gender == Gender.FEMALE -> FEMALE
                p.gender == Gender.MALE -> MALE
                else -> NARRATOR
            }
            Dialogue.chunks(p.text, max).map { Piece(it, role) }
        }
    }
}
