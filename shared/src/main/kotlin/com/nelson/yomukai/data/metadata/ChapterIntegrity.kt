package com.nelson.yomukai.data.metadata

/** Something odd in a chapter list, found before the user starts reading. */
sealed interface ChapterIssue {
    /** Numbers jump from [from] to [to]: the chapters in between are missing. [afterIndex] is the reading position of [from]. */
    data class Gap(val from: Float, val to: Float, val afterIndex: Int) : ChapterIssue

    /** [number] appears [count] times. */
    data class Duplicate(val number: Float, val count: Int) : ChapterIssue

    /** The list goes backwards [count] times: the source sorted it wrong. */
    data class OutOfOrder(val count: Int) : ChapterIssue
}

/**
 * Checks the numbering of a chapter list in one pass. A gap only counts when it is far bigger than
 * the usual step of the list, so lists that number by tens or skip a filler chapter stay quiet. Pure.
 */
object ChapterIntegrity {
    /**
     * [numbers] in reading order (first chapter first). Numbers of 0 or less are unknown and skipped:
     * prologues and extras usually carry them.
     */
    fun check(numbers: List<Float>): List<ChapterIssue> {
        val known = numbers.withIndex().filter { it.value > 0f }
        if (known.size < MIN_CHAPTERS) return emptyList()
        val issues = mutableListOf<ChapterIssue>()

        known.groupingBy { it.value }.eachCount().filter { it.value > 1 }.entries
            .sortedBy { it.key }
            .take(MAX_REPORTED)
            .forEach { issues += ChapterIssue.Duplicate(it.key, it.value) }

        val backwards = known.zipWithNext().count { (a, b) -> b.value < a.value }
        // A few steps back are extras slotted in; many mean the whole list is reversed or shuffled.
        if (backwards > maxOf(1, known.size / OUT_OF_ORDER_SHARE)) issues += ChapterIssue.OutOfOrder(backwards)

        val ascending = known.distinctBy { it.value }.sortedBy { it.value }
        val steps = ascending.zipWithNext().map { (a, b) -> b.value - a.value }
        val usual = steps.sorted().let { it[it.size / 2] }.coerceAtLeast(1f)
        ascending.zipWithNext()
            .filter { (a, b) -> b.value - a.value > maxOf(MIN_GAP, usual * GAP_FACTOR) }
            .take(MAX_REPORTED)
            .forEach { (a, b) -> issues += ChapterIssue.Gap(a.value, b.value, a.index) }
        return issues
    }

    /** One short line per issue, for the warning above the list. */
    fun describe(issue: ChapterIssue, isAnime: Boolean): String {
        val noun = if (isAnime) "episodios" else "capítulos"
        return when (issue) {
            is ChapterIssue.Gap -> "Faltan ${noun} entre el ${format(issue.from)} y el ${format(issue.to)}"
            is ChapterIssue.Duplicate -> "El ${format(issue.number)} aparece ${issue.count} veces"
            is ChapterIssue.OutOfOrder -> "La fuente tiene ${noun} desordenados"
        }
    }

    fun format(number: Float): String = if (number % 1f == 0f) number.toInt().toString() else number.toString().replace('.', ',')

    private const val MIN_CHAPTERS = 3
    private const val MIN_GAP = 5f
    private const val GAP_FACTOR = 4f
    private const val OUT_OF_ORDER_SHARE = 10
    private const val MAX_REPORTED = 5
}
