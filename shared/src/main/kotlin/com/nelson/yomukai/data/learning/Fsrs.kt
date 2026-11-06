package com.nelson.yomukai.data.learning

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong

/** How well a saved word was remembered in a review (Anki's four buttons). */
enum class Grade(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

/** A word's memory state: stability (days until 90 % recall), difficulty (1..10), when it is due next. */
data class CardState(
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val lastReview: Long = 0
) {
    val isNew: Boolean get() = reps == 0
}

/**
 * FSRS 4.5 (Free Spaced Repetition Scheduler, the one in Anki), with its published default weights. Pure: the server
 * and the app schedule a review the same way. Aims at 90 % recall.
 */
object Fsrs {
    val DEFAULT_W = doubleArrayOf(
        0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755
    )
    private const val DECAY = -0.5
    private val FACTOR = 0.9.pow(1 / DECAY) - 1 // 19/81
    const val DAY_MS = 86_400_000L
    const val MAX_INTERVAL_DAYS = 36_500.0

    /** Chance of remembering after [days] with [stability]. */
    fun retrievability(days: Double, stability: Double): Double = (1 + FACTOR * days / stability).pow(DECAY)

    /** Days until recall drops to [retention] (0.9 = the default). */
    fun interval(stability: Double, retention: Double = 0.9): Double =
        (stability / FACTOR * (retention.pow(1 / DECAY) - 1)).coerceIn(1.0, MAX_INTERVAL_DAYS)

    fun review(card: CardState, grade: Grade, now: Long, w: DoubleArray = DEFAULT_W): CardState {
        val g = grade.value
        if (card.isNew) {
            val s = w[g - 1]
            val d = clampD(w[4] - (g - 3) * w[5])
            return CardState(s, d, now + due(s, grade), 1, if (grade == Grade.AGAIN) 1 else 0, now)
        }
        val elapsed = ((now - card.lastReview).coerceAtLeast(0) / DAY_MS.toDouble())
        val r = retrievability(elapsed, card.stability)
        // Mean reversion towards the difficulty of a first "Easy" answer, D0(4) = w4 - w5.
        val d = clampD(w[7] * (w[4] - w[5]) + (1 - w[7]) * (card.difficulty - w[6] * (g - 3)))
        val s = if (grade == Grade.AGAIN) {
            w[11] * card.difficulty.pow(-w[12]) * ((card.stability + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r))
        } else {
            val hard = if (grade == Grade.HARD) w[15] else 1.0
            val easy = if (grade == Grade.EASY) w[16] else 1.0
            card.stability * (exp(w[8]) * (11 - card.difficulty) * card.stability.pow(-w[9]) * (exp(w[10] * (1 - r)) - 1) * hard * easy + 1)
        }
        return CardState(s, d, now + due(s, grade), card.reps + 1, card.lapses + if (grade == Grade.AGAIN) 1 else 0, now)
    }

    /** "Again" comes back in ten minutes; the rest after the interval that keeps 90 % recall. */
    private fun due(stability: Double, grade: Grade): Long =
        if (grade == Grade.AGAIN) 10 * 60_000L else (interval(stability) * DAY_MS).roundToLong()

    private fun clampD(d: Double) = d.coerceIn(1.0, 10.0)
}

/** Known / learning / new, for marking words discreetly and the comprehension percentage. */
enum class WordLevel { NEW, LEARNING, KNOWN }

object Comprehension {
    /** A word counts as known once it would be remembered for three weeks. */
    fun level(card: CardState?): WordLevel = when {
        card == null || card.isNew -> WordLevel.NEW
        card.stability >= 21 -> WordLevel.KNOWN
        else -> WordLevel.LEARNING
    }

    /**
     * Share of the running words of [text] the reader knows: words they marked as known, plus anything not in their
     * list of saved words that is in [common] (the language's most frequent words), counted as known.
     */
    fun percent(text: String, levels: Map<String, WordLevel>, common: Set<String> = emptySet()): Int {
        val words = Regex("\\p{L}+").findAll(text.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return 100
        val known = words.count { w -> levels[w]?.let { it == WordLevel.KNOWN } ?: (w in common) }
        return known * 100 / words.size
    }
}

/** Anki import file: tab-separated front, back, context and source, one card per line (no tabs or newlines inside). */
object AnkiExport {
    data class Entry(val word: String, val meaning: String, val context: String, val source: String)

    fun tsv(entries: List<Entry>): String = buildString {
        append("#separator:tab\n#html:false\n#columns:Palabra\tSignificado\tContexto\tObra\n")
        entries.forEach { e -> append(listOf(e.word, e.meaning, e.context, e.source).joinToString("\t") { clean(it) }).append('\n') }
    }

    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim()
}
