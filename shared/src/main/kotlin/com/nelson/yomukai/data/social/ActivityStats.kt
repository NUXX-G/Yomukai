package com.nelson.yomukai.data.social

import java.time.LocalDate

/** Streaks, estimated hours and feed bursts, from plain lists of what someone did. Pure, shared by server and app. */
object ActivityStats {
    /** Minutes counted per episode and per chapter when estimating hours (a typical TV episode, a typical chapter). */
    const val EPISODE_MINUTES = 24
    const val CHAPTER_MINUTES = 5

    data class Streak(val current: Int, val longest: Int)

    /** Consecutive days with activity: the current run (alive if it reaches today or yesterday) and the longest ever. */
    fun streak(days: Collection<LocalDate>, today: LocalDate): Streak {
        if (days.isEmpty()) return Streak(0, 0)
        val sorted = days.toSortedSet().toList()
        var longest = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i - 1].plusDays(1) == sorted[i]) run + 1 else 1
            longest = maxOf(longest, run)
        }
        val last = sorted.last()
        val current = if (last == today || last == today.minusDays(1)) run else 0
        return Streak(current, longest)
    }

    fun hours(episodes: Int, chapters: Int): Int = (episodes * EPISODE_MINUTES + chapters * CHAPTER_MINUTES + 30) / 60

    /** One thing done: [work] identifies the title, [at] is when (ms). */
    data class Done(val work: String, val title: String, val label: String, val at: Long)

    /** Several things on the same title close together, shown as one feed entry ("12 episodes in a row"). */
    data class Burst(val work: String, val title: String, val count: Int, val firstLabel: String, val lastLabel: String, val from: Long, val to: Long) {
        /** Twelve or more in one go is a marathon. */
        val marathon: Boolean get() = count >= MARATHON
    }

    const val MARATHON = 12

    /** Groups [items] per title while each one comes within [gapMs] of the previous one; newest burst first. */
    fun bursts(items: List<Done>, gapMs: Long = 3 * 3_600_000L): List<Burst> {
        val out = mutableListOf<Burst>()
        items.groupBy { it.work }.forEach { (work, list) ->
            var current: MutableList<Done>? = null
            for (d in list.sortedBy { it.at }) {
                val c = current
                if (c != null && d.at - c.last().at <= gapMs) {
                    c += d
                } else {
                    c?.let { out += burstOf(work, it) }
                    current = mutableListOf(d)
                }
            }
            current?.let { out += burstOf(work, it) }
        }
        return out.sortedByDescending { it.to }
    }

    private fun burstOf(work: String, list: List<Done>) =
        Burst(work, list.last().title, list.size, list.first().label, list.last().label, list.first().at, list.last().at)

    /** Most frequent genre among [genres] lists (one list per title), or null. */
    fun topGenre(genres: List<List<String>>): String? =
        genres.flatten().map { it.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}
