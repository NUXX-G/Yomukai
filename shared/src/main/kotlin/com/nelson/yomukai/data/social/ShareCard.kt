package com.nelson.yomukai.data.social

/**
 * A work, episode or chapter shared in a direct message, a room's chat or a forum post (F4.3). It travels as data,
 * never as HTML: every client draws it itself and builds the link from the fields, so a card can only ever point
 * inside the app. [sanitize] is the single gate every card passes before it is stored.
 */
data class ShareCard(
    /** "work", "episode" or "chapter". */
    val kind: String,
    val sourceId: String,
    val mediaId: String,
    val chapterId: String = "",
    val title: String,
    val cover: String = "",
    /** ANIME, MANGA, MANHWA, MANHUA, COMIC or NOVEL. */
    val type: String = "",
    /** Language of the source (ES, EN, ZH...). */
    val lang: String = "",
    /** Episode or chapter number, when known (for the spoiler shield). */
    val number: Double? = null,
    /** How the source calls the episode or chapter («Ep. 12», «Cap. 104 — El final»). */
    val label: String = "",
    /** The sender asks to hide it until each reader has got that far. */
    val spoiler: Boolean = false
) {
    /** Where the card leads inside the app (the same path in the web and in the app's deep links). */
    fun link(): String {
        val s = enc(sourceId)
        val m = enc(mediaId)
        return when (kind) {
            "episode" -> "/ver/$s/$m/${enc(chapterId)}"
            "chapter" -> "/leer/$s/$m/${enc(chapterId)}"
            else -> "/obra/$s/$m"
        }
    }

    /** One line for notifications and for clients that cannot draw cards. */
    fun summary(): String = listOf(title, label.ifBlank { number?.let { "#" + fmt(it) }.orEmpty() }).filter { it.isNotBlank() }.joinToString(" · ")

    /** Whether a reader who has got to [reached] (episode/chapter number, null = unknown) should see it covered. */
    fun hiddenFor(reached: Double?): Boolean = spoiler && number != null && (reached == null || reached < number)

    companion object {
        val KINDS = setOf("work", "episode", "chapter")
        val TYPES = setOf("ANIME", "MANGA", "MANHWA", "MANHUA", "COMIC", "NOVEL")
        private val SOURCE_ID = Regex("^[a-z0-9_.:-]{1,60}$")
        private val LANG = Regex("^[A-Za-z]{2,3}(-[A-Za-z]{2,4})?$")
        private val SAFE_COVER = Regex("^(https://[^\\s\"'<>]{4,1990}|/api/img\\?[^\\s\"'<>]{1,1990})$")

        /**
         * The card as it may be stored, or null when it cannot be one: unknown kind, missing ids or title, an episode
         * or chapter without its id. Text is cleaned of control characters and cut; a cover that is not https (or our
         * own image proxy) is dropped, never followed.
         */
        fun sanitize(card: ShareCard): ShareCard? {
            val kind = card.kind.trim().lowercase().takeIf { it in KINDS } ?: return null
            val sourceId = card.sourceId.trim().takeIf { SOURCE_ID.matches(it) } ?: return null
            val mediaId = text(card.mediaId, 2_000).takeIf { it.isNotEmpty() } ?: return null
            val chapterId = if (kind == "work") "" else text(card.chapterId, 2_000).takeIf { it.isNotEmpty() } ?: return null
            val title = text(card.title, 200).takeIf { it.isNotEmpty() } ?: return null
            val number = card.number?.takeIf { it.isFinite() && it >= 0 && it < 100_000 }
            return ShareCard(
                kind = kind, sourceId = sourceId, mediaId = mediaId, chapterId = chapterId, title = title,
                cover = card.cover.trim().takeIf { SAFE_COVER.matches(it) }.orEmpty(),
                type = card.type.trim().uppercase().takeIf { it in TYPES }.orEmpty(),
                lang = card.lang.trim().takeIf { LANG.matches(it) }?.uppercase().orEmpty(),
                number = if (kind == "work") null else number,
                label = if (kind == "work") "" else text(card.label, 120),
                spoiler = kind != "work" && card.spoiler && number != null
            )
        }

        private fun text(s: String, max: Int) = s.filter { !it.isISOControl() }.trim().take(max)
        private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        private fun fmt(n: Double) = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
    }
}
