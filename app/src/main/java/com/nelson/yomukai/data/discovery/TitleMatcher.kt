package com.nelson.yomukai.data.discovery

import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType

/** How sure the app is that a title in another source is the same work. */
enum class MatchConfidence {
    /** Same name and the same authors (or a name too specific to be shared by chance). */
    SAME_WORK,

    /** Same name, but nothing else to compare. */
    UNCONFIRMED,

    /** Same name and different authors: often another work, sometimes just another spelling. */
    DIFFERENT_AUTHOR,
    NONE
}

/** Decides whether two entries are the same work. Pure, so it is unit tested. */
object TitleMatcher {
    /** Manga, manhwa, manhua and comics can be the same work across sources; an anime or a novel never is. */
    fun sameFamily(a: MediaType, b: MediaType): Boolean =
        (a.isReadableAsImages && b.isReadableAsImages) || a == b

    private fun keys(names: Collection<String>): Set<String> =
        names.map(SmartSearch::duplicateKey).filter { it.length >= MIN_KEY_LENGTH }.toSet()

    /** True when some name of [a] equals some name of [b] once case, accents and punctuation are ignored. */
    fun shareName(a: Collection<String>, b: Collection<String>): Boolean = keys(a).any { it in keys(b) }

    /**
     * A shared name alone can join two different works ("Leviathan" is several), so it only counts
     * as the same work when the authors agree. Without authors to compare, that takes a name too
     * long to be a coincidence. Anime sources list no authors at all, and their titles are specific.
     * The result is shown to the user, who decides: different authors are often just transliteration.
     */
    fun compare(names: Collection<String>, author: String, type: MediaType, candidate: MediaItem): MatchConfidence {
        if (!sameFamily(type, candidate.type)) return MatchConfidence.NONE
        val shared = sharedKey(names, listOf(candidate.title) + candidate.altTitles) ?: return MatchConfidence.NONE
        return when {
            authorsDisagree(author, candidate.author) -> MatchConfidence.DIFFERENT_AUTHOR
            authorsKnown(author, candidate.author) -> MatchConfidence.SAME_WORK
            type == MediaType.ANIME -> MatchConfidence.SAME_WORK
            type == candidate.type && shared.split(' ').size >= SPECIFIC_NAME_WORDS -> MatchConfidence.SAME_WORK
            else -> MatchConfidence.UNCONFIRMED
        }
    }

    private fun sharedKey(a: Collection<String>, b: Collection<String>): String? {
        val other = keys(b)
        return keys(a).firstOrNull { it in other }
    }

    private fun authorWords(text: String) = SmartSearch.tokens(text).filter { it.length >= 3 }.toSet()

    private fun authorsKnown(a: String, b: String) = authorWords(a).isNotEmpty() && authorWords(b).isNotEmpty()

    /** True when both lists are known and do not have a single word in common. */
    fun authorsDisagree(a: String, b: String): Boolean {
        val wordsB = authorWords(b)
        return authorsKnown(a, b) && authorWords(a).none { it in wordsB }
    }

    /** Close enough to be worth asking the source for the title's other names. */
    fun nearMatch(names: Collection<String>, candidate: MediaItem): Boolean =
        names.any { SmartSearch.scoreTitle(it, candidate.title) >= NEAR_SCORE }

    private const val MIN_KEY_LENGTH = 3
    private const val SPECIFIC_NAME_WORDS = 3
    private const val NEAR_SCORE = 0.6
}
