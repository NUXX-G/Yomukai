package com.nelson.yomukai.data.social

/**
 * Chat rules shared by rooms and the forum: length limits, flood control per user and the people a message mentions.
 * Messages are plain text everywhere (shown as text, never as HTML).
 */
class ChatGuard(
    private val maxMessages: Int = 5,
    private val windowMs: Long = 5_000,
    private val maxReactions: Int = 20
) {
    private val sent = HashMap<Long, ArrayDeque<Long>>()
    private val reacted = HashMap<Long, ArrayDeque<Long>>()

    /** True when [user] may send another message at [now] (and counts it). */
    @Synchronized
    fun allowMessage(user: Long, now: Long): Boolean = allow(sent, user, now, maxMessages)

    /** Reactions are cheaper but still limited, so nobody floods the screen. */
    @Synchronized
    fun allowReaction(user: Long, now: Long): Boolean = allow(reacted, user, now, maxReactions)

    private fun allow(map: HashMap<Long, ArrayDeque<Long>>, user: Long, now: Long, max: Int): Boolean {
        val q = map.getOrPut(user) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
        if (q.size >= max) return false
        q.addLast(now)
        return true
    }

    companion object {
        const val MAX_LENGTH = 500
        private val MENTION = Regex("(?<![\\w@])@([\\p{L}\\p{N}_.-]{2,40})")

        /** Allowed reactions: a short fixed set, so nothing odd ends up floating over the video. */
        val REACTIONS = listOf("😂", "😮", "😭", "😡", "❤️", "👏", "🔥", "💀", "🤯", "👀")

        /** The text as it will be stored: control characters out, trimmed, at most [MAX_LENGTH] characters. */
        fun clean(text: String): String =
            text.filter { it == '\n' || !it.isISOControl() }.trim().replace(Regex("\n{3,}"), "\n\n").take(MAX_LENGTH)

        /** Names mentioned with @name, in order, without repeats. */
        fun mentions(text: String): List<String> = MENTION.findAll(text).map { it.groupValues[1].trimEnd('.', '-') }.distinct().toList()
    }
}
