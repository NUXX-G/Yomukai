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

    /**
     * Key of a work by name and format family: "Solo Leveling" the manhwa and "Solo Leveling" the
     * novel get different keys, the manhwa and a manga edition of it the same one.
     */
    fun familyKey(type: MediaType, title: String): String =
        (if (type.isReadableAsImages) "images" else type.name) + "|" + SmartSearch.duplicateKey(Seasons.canonical(title)) +
            (if (type == MediaType.ANIME) SmartSearch.bracketYear(title)?.let { " $it" }.orEmpty() else "")

    /**
     * An anime and its remake share the name and differ in the year in brackets: «Ranma 1/2» and «Ranma1/2 (2024)»,
     * «Hunter x Hunter (2011)». Two lists of names are apart when the years they give are not the same.
     */
    fun yearsConflict(a: Collection<String>, b: Collection<String>): Boolean =
        a.mapNotNull(SmartSearch::bracketYear).toSet() != b.mapNotNull(SmartSearch::bracketYear).toSet()

    /**
     * Names compared: case, accents, punctuation and the way a season is written ignored, and the spaces too («Ao
     * Ashi» and «Aoashi» are one name), so long as what is left is not too short to be a coincidence.
     */
    fun keys(names: Collection<String>): Set<String> =
        names.map { SmartSearch.duplicateKey(Seasons.canonical(it)) }.filter { it.length >= MIN_KEY_LENGTH }
            .flatMap { k -> listOf(k, k.replace(" ", "")) }.filter { it.length >= MIN_KEY_LENGTH }.toSet()

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
        if (type == MediaType.ANIME && yearsConflict(names.take(1), listOf(candidate.title))) return MatchConfidence.NONE
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

    /**
     * How much [name] and the candidate's names are the same name, 0..1, measured both ways: a
     * sequel ("Solo Leveling: Ragnarok") contains the name but the name does not contain it.
     */
    fun closeness(name: String, candidate: MediaItem): Double =
        (listOf(candidate.title) + candidate.altTitles).maxOfOrNull { other ->
            minOf(SmartSearch.scoreTitle(name, other), SmartSearch.scoreTitle(other, name))
        } ?: 0.0

    private const val MIN_KEY_LENGTH = 3
    private const val SPECIFIC_NAME_WORDS = 3
    private const val NEAR_SCORE = 0.6
}
