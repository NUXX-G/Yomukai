package com.nelson.yomukai.data.learning

/** What a long press on a text picked: the word (or, without spaces, the text from there) and its sentence. */
data class PickedWord(val word: String, val sentence: String)

object WordAt {
    private val SENTENCE_END = setOf('.', '!', '?', '。', '！', '？', '…', '\n')
    private const val CJK_SPAN = 8
    private const val MAX_SENTENCE = 500

    /**
     * The word at character [offset] of [text]. In scripts with spaces, the run of letters (and inner apostrophes or
     * hyphens) around it; in Chinese or Japanese, up to [CJK_SPAN] characters from there (the dictionary then finds the
     * longest word they start with). Null when the press is not on a letter.
     */
    fun find(text: String, offset: Int): PickedWord? {
        if (text.isEmpty()) return null
        val at = offset.coerceIn(0, text.length - 1)
        if (!text[at].isLetterOrDigit()) return null
        val word = if (isCjk(text[at])) {
            text.substring(at).takeWhile { isCjk(it) }.take(CJK_SPAN)
        } else {
            fun inWord(i: Int) = text[i].isLetterOrDigit() ||
                (text[i] in "'’-" && i > 0 && i < text.length - 1 && text[i - 1].isLetter() && text[i + 1].isLetter())
            var start = at
            while (start > 0 && inWord(start - 1) && !isCjk(text[start - 1])) start--
            var end = at
            while (end < text.length - 1 && inWord(end + 1) && !isCjk(text[end + 1])) end++
            text.substring(start, end + 1)
        }
        return PickedWord(word, sentence(text, at))
    }

    private fun sentence(text: String, at: Int): String {
        var s = at
        while (s > 0 && text[s - 1] !in SENTENCE_END) s--
        var e = at
        while (e < text.length && text[e] !in SENTENCE_END) e++
        return text.substring(s, minOf(text.length, e + 1)).trim().take(MAX_SENTENCE)
    }

    private fun isCjk(c: Char): Boolean = Character.UnicodeScript.of(c.code) in setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA
    )
}
