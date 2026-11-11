package com.nelson.yomukai.data.work

/** One episode or chapter of a work, in one source: enough to choose the next one. [index] = order in its source. */
data class EpisodeRef(val sourceId: String, val mediaId: String, val chapterId: String, val number: Float, val index: Int)

/**
 * Which episode «Siguiente» opens (same rule as the web's next-episode.js). A work can be spread over several sources:
 * the next one is the next NUMBER of the work, from this source when it has it, from a sister source when only that
 * one does. Unnumbered lists (specials, movies) go by the source's order.
 */
object NextEpisode {
    fun pick(current: EpisodeRef, own: List<EpisodeRef>, others: List<EpisodeRef> = emptyList(), skip: (EpisodeRef) -> Boolean = { false }): EpisodeRef? {
        val at = numberKey(current.number)
        if (current.number <= 0f) {
            val plain = own.filter { it.index > current.index }.sortedBy { it.index }
            return plain.firstOrNull { !skip(it) } ?: plain.firstOrNull()
        }
        val ahead = own.filter { it.number > current.number || (numberKey(it.number) == at && it.index > current.index) }
        val ownNumbers = own.map { numberKey(it.number) }.toSet()
        val foreign = others.filter { it.number > current.number && numberKey(it.number) !in ownNumbers && it.sourceId != current.sourceId }
        val all = (ahead.map { it to it.index } + foreign.map { it to Int.MAX_VALUE }).sortedWith(compareBy({ it.first.number }, { it.second })).map { it.first }
        return all.firstOrNull { !skip(it) } ?: all.firstOrNull()
    }
}
