package com.nelson.yomukai.data.discovery

import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType

/** How sure the app is that a title in another source is the same work. */
enum class MatchConfidence { SAME_WORK, PROBABLE, NONE }

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
     * A shared name alone can join two different works, so authors are compared when both sides
     * list them in Latin script. Different authors never reject: names are often transliterated
     * differently, so the result only drops to [MatchConfidence.PROBABLE].
     */
    fun compare(names: Collection<String>, author: String, type: MediaType, candidate: MediaItem): MatchConfidence {
        if (!sameFamily(type, candidate.type)) return MatchConfidence.NONE
        if (!shareName(names, listOf(candidate.title) + candidate.altTitles)) return MatchConfidence.NONE
        return if (authorsDisagree(author, candidate.author)) MatchConfidence.PROBABLE else MatchConfidence.SAME_WORK
    }

    /** True when both lists are known and do not have a single word in common. */
    fun authorsDisagree(a: String, b: String): Boolean {
        val wordsA = SmartSearch.tokens(a).filter { it.length >= 3 }.toSet()
        val wordsB = SmartSearch.tokens(b).filter { it.length >= 3 }.toSet()
        return wordsA.isNotEmpty() && wordsB.isNotEmpty() && wordsA.none { it in wordsB }
    }

    /** Close enough to be worth asking the source for the title's other names. */
    fun nearMatch(names: Collection<String>, candidate: MediaItem): Boolean =
        names.any { SmartSearch.scoreTitle(it, candidate.title) >= NEAR_SCORE }

    private const val MIN_KEY_LENGTH = 3
    private const val NEAR_SCORE = 0.6
}
