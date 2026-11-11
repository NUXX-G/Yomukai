package com.nelson.yomukai.data.social

/**
 * Antispam for direct messages (F4.2), as pure rules: flood control is ChatGuard's; these stop someone writing to
 * people who never answer and opening conversations with everyone on the server.
 */
object DmRules {
    /** Messages to someone who has never replied before you must wait for an answer. */
    const val MAX_UNANSWERED = 5

    /** New conversations a person may start in a day. */
    const val MAX_NEW_PER_DAY = 15

    /** Longest message (text; a shared card goes apart). */
    const val MAX_LENGTH = 2_000

    data class Situation(
        /** The receiver blocked the sender. */
        val blockedByPeer: Boolean,
        /** The sender blocked the receiver (they must unblock to write). */
        val blockedPeer: Boolean,
        /** Messages already in the conversation, from each side. */
        val sentByMe: Int,
        val sentByPeer: Int,
        /** Conversations the sender started today (before this one). */
        val startedToday: Int,
        /** Silenced by an admin until this time (0 = never). */
        val mutedUntil: Long,
        val now: Long
    )

    /** Why the message cannot go, or null when it can. */
    fun refuse(s: Situation): String? = when {
        s.mutedUntil > s.now -> "Estás silenciado por un administrador"
        s.blockedPeer -> "Has bloqueado a esta persona: desbloquéala para escribirle"
        // Said the same way as an unknown name: whoever is blocked is not told so.
        s.blockedByPeer -> "No puedes escribir a esta persona"
        s.sentByPeer == 0 && s.sentByMe >= MAX_UNANSWERED -> "Espera a que te conteste antes de mandar más mensajes"
        s.sentByMe == 0 && s.sentByPeer == 0 && s.startedToday >= MAX_NEW_PER_DAY -> "Has empezado muchas conversaciones hoy: prueba mañana"
        else -> null
    }

    /** The text as stored: control characters out (line breaks stay), at most three in a row, trimmed and cut. */
    fun clean(text: String): String =
        text.filter { it == '\n' || !it.isISOControl() }.trim().replace(Regex("\n{3,}"), "\n\n").take(MAX_LENGTH)
}
