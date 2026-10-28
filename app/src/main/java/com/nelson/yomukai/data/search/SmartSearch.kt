package com.nelson.yomukai.data.search

import com.nelson.yomukai.domain.model.MediaItem
import java.text.Normalizer

/** A title found in one or more sources; [primary] is the best-ranked copy. */
data class SearchGroup(val primary: MediaItem, val others: List<MediaItem>, val score: Double) {
    val all: List<MediaItem> get() = listOf(primary) + others
}

/**
 * Pure text matching used by the search screen: normalisation (case, accents, punctuation),
 * typo tolerance (Levenshtein per token), partial matches, alternative titles, relevance
 * ranking and cross-source duplicate grouping.
 */
object SmartSearch {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")
    private val parenthesised = Regex("\\([^)]*\\)|\\[[^]]*]")

    /** Lowercase, strip accents and punctuation, collapse whitespace. "Ñandú: ¡Rápido!" -> "nandu rapido". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase()
            .replace(nonAlphanumeric, " ")
            .trim()

    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    fun levenshtein(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, curr[j])
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = curr; curr = t
        }
        return prev[b.length]
    }

    /** Dice coefficient over character trigrams, 0..1. */
    fun trigramSimilarity(a: String, b: String): Double {
        fun grams(s: String): Set<String> {
            val padded = "  $s "
            return (0..padded.length - 3).map { padded.substring(it, it + 3) }.toSet()
        }
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        return 2.0 * ga.intersect(gb).size / (ga.size + gb.size)
    }

    private fun allowedTypos(length: Int) = when {
        length <= 3 -> 0
        length <= 6 -> 1
        else -> 2
    }

    /** Score of one query token against the tokens of a candidate title (0 = no match). */
    private fun tokenScore(queryToken: String, candidateTokens: List<String>, isLast: Boolean): Double {
        var best = 0.0
        for (c in candidateTokens) {
            val s = when {
                c == queryToken -> 1.0
                c.startsWith(queryToken) && (isLast || queryToken.length >= 3) -> 0.85
                c.contains(queryToken) && queryToken.length >= 3 -> 0.6
                else -> {
                    val typos = allowedTypos(queryToken.length)
                    if (typos == 0) 0.0
                    else {
                        // Compare against the whole token and, for partially typed words, its prefix.
                        val d = minOf(
                            levenshtein(queryToken, c, typos),
                            if (c.length > queryToken.length) levenshtein(queryToken, c.take(queryToken.length), typos) else typos + 1
                        )
                        if (d <= typos) 0.7 - 0.15 * d else 0.0
                    }
                }
            }
            if (s > best) best = s
        }
        return best
    }

    /** Relevance of [title] for [query], 0..1. */
    fun scoreTitle(query: String, title: String): Double {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q == t) return 1.0
        val qTokens = q.split(' ')
        val tTokens = t.split(' ')
        val perToken = qTokens.mapIndexed { i, qt -> tokenScore(qt, tTokens, isLast = i == qTokens.lastIndex) }
        if (perToken.any { it == 0.0 }) {
            // Every query word must match something, otherwise fall back to whole-string trigrams.
            val tri = trigramSimilarity(q, t)
            return if (tri >= 0.55) tri * 0.6 else 0.0
        }
        var score = perToken.average()
        if (t.startsWith(q)) score += 0.1
        else if (t.contains(q)) score += 0.05
        // Shorter titles that fully contain the query are more specific than long ones.
        score -= 0.02 * (tTokens.size - qTokens.size).coerceAtLeast(0).coerceAtMost(5)
        return score.coerceIn(0.0, 0.99)
    }

    /** Best score over the title and all alternative titles (alternatives count slightly less). */
    fun score(query: String, item: MediaItem): Double {
        val main = scoreTitle(query, item.title)
        val alt = item.altTitles.maxOfOrNull { scoreTitle(query, it) * 0.95 } ?: 0.0
        return maxOf(main, alt)
    }

    /** Key shared by the same title in different sources: normalised, without "(2026)" style suffixes. */
    fun duplicateKey(title: String): String = normalize(title.replace(parenthesised, " "))

    fun rank(query: String, items: List<MediaItem>, minScore: Double = 0.3): List<Pair<MediaItem, Double>> =
        items.map { it to score(query, it) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }

    /**
     * Ranks everything found across sources and merges copies of the same title (same duplicate
     * key and type family), keeping the highest-scoring copy as primary.
     */
    fun rankAndGroup(query: String, items: List<MediaItem>, minScore: Double = 0.3): List<SearchGroup> {
        val grouped = LinkedHashMap<String, MutableList<Pair<MediaItem, Double>>>()
        for (entry in rank(query, items, minScore)) {
            val key = duplicateKey(entry.first.title).ifEmpty { entry.first.id }
            grouped.getOrPut(key) { mutableListOf() }.add(entry)
        }
        return grouped.values.map { group ->
            // Same source twice (e.g. two editions): keep only the best copy per source.
            val perSource = group.distinctBy { it.first.sourceId + "|" + it.first.id }
            SearchGroup(perSource.first().first, perSource.drop(1).map { it.first }, perSource.first().second)
        }.sortedByDescending { it.score }
    }

    /**
     * Queries to send to sources. Sources only do literal substring search, so a typo would return
     * nothing; the extra variants (longest word, shortened word) pull candidates that [rank] then
     * scores fuzzily.
     */
    fun queryVariants(query: String): List<String> {
        val full = normalize(query)
        if (full.isEmpty()) return emptyList()
        val longest = full.split(' ').maxByOrNull { it.length }.orEmpty()
        val variants = linkedSetOf(query.trim(), full)
        if (longest.length >= 4 && longest != full) variants += longest
        if (longest.length >= 5) variants += longest.dropLast(2)
        return variants.toList()
    }
}
