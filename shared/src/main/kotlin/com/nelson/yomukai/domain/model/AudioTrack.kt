package com.nelson.yomukai.domain.model

import com.nelson.yomukai.i18n.tr
import java.text.Normalizer

/**
 * The language an episode is heard in, apart from its subtitles (F3.1): the original audio (subtitled), a dub into
 * Spain's Spanish, into Latin American Spanish, into English, or a dub the site does not say which.
 */
enum class AudioTrack(val label: String, val short: String) {
    ORIGINAL(tr("Subtitulado"), "SUB"),
    CASTELLANO(tr("Castellano"), "CAST"),
    LATINO(tr("Latino"), "LAT"),
    INGLES(tr("Doblaje en inglés"), "DUB EN"),
    DOBLADO(tr("Doblado"), "DOB");

    val isDub: Boolean get() = this != ORIGINAL

    companion object {
        /** An anime title's audio: what its site says, or what its name and address say. */
        fun of(item: MediaItem, sourceLanguage: String): AudioTrack =
            if (item.type != MediaType.ANIME) ORIGINAL else item.audio ?: detect(item.title + " " + item.id, sourceLanguage)

        /**
         * The title without what only says how it is heard («Dragon Ball Super Latino», «Frieren (Dub)», «One Piece
         * Castellano»): a dub is the same work, so its name must give the same work key (F3.1).
         */
        fun strip(title: String): String =
            title.replace(AUDIO_NOTE, " ").replace(Regex("\\(\\s*\\)|\\[\\s*]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('-', ':', '|').trim()
                .ifBlank { title }

        private val AUDIO_NOTE = Regex(
            """(?i)\b(audio\s+)?(latino|castellano|english\s+dub(bed)?|dub(bed)?|doblaje|doblad[oa]|sub\s+espa[nñ]ol|subtitulad[oa])\b"""
        )

        fun fromName(name: String?): AudioTrack = entries.firstOrNull { it.name == name } ?: ORIGINAL

        /**
         * From what a site writes in a title, a slug or a server's label («Dragon Ball Super Latino», «(Dub)»,
         * «Doblaje castellano»). [sourceLanguage] tells an English site's «Dub» (English) from a Spanish one's
         * «Doblado» (which Spanish, the site does not say).
         */
        fun detect(text: String, sourceLanguage: String): AudioTrack {
            val t = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            val words = t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
            return when {
                "castellano" in words || "espana" in words || "castilian" in words -> CASTELLANO
                "latino" in words || "latam" in words || "lat" in words -> LATINO
                sourceLanguage.equals("EN", true) && ("dub" in words || "dubbed" in words) -> INGLES
                "dub" in words || "dubbed" in words || "doblado" in words || "doblaje" in words || "doblada" in words ->
                    if (sourceLanguage.equals("EN", true)) INGLES else DOBLADO
                else -> ORIGINAL
            }
        }
    }
}
