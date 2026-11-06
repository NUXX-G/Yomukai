package com.nelson.yomukai.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsTest {
    private val day = Fsrs.DAY_MS

    @Test
    fun firstReviewUsesTheInitialStabilities() {
        val good = Fsrs.review(CardState(), Grade.GOOD, 0)
        assertEquals(3.7145, good.stability, 1e-9)
        assertEquals(1, good.reps)
        // At 90 % retention the interval equals the stability (rounded to a day at least).
        assertEquals(3.7145 * day, good.due.toDouble(), day * 0.01)
        assertEquals(10 * 60_000L, Fsrs.review(CardState(), Grade.AGAIN, 0).due)
        assertTrue(Fsrs.review(CardState(), Grade.EASY, 0).due > good.due)
        assertTrue(Fsrs.review(CardState(), Grade.EASY, 0).difficulty < Fsrs.review(CardState(), Grade.HARD, 0).difficulty)
    }

    @Test
    fun rememberingOnTimeKeepsStretchingTheInterval() {
        var card = Fsrs.review(CardState(), Grade.GOOD, 0)
        var lastInterval = card.due - card.lastReview
        repeat(4) {
            card = Fsrs.review(card, Grade.GOOD, card.due)
            val interval = card.due - card.lastReview
            assertTrue(interval > lastInterval)
            lastInterval = interval
        }
        // Forgetting drops the stability and counts a lapse.
        val lapsed = Fsrs.review(card, Grade.AGAIN, card.due)
        assertTrue(lapsed.stability < card.stability)
        assertEquals(1, lapsed.lapses)
        assertEquals(0.9, Fsrs.retrievability(3.0, 3.0), 1e-9)
    }

    @Test
    fun comprehensionAndAnkiExport() {
        assertEquals(WordLevel.NEW, Comprehension.level(null))
        assertEquals(WordLevel.KNOWN, Comprehension.level(CardState(stability = 30.0, reps = 5)))
        assertEquals(WordLevel.LEARNING, Comprehension.level(CardState(stability = 3.0, reps = 1)))
        val levels = mapOf("dragon" to WordLevel.LEARNING, "sword" to WordLevel.KNOWN)
        assertEquals(75, Comprehension.percent("The sword, the dragon!", levels, common = setOf("the")))
        val tsv = AnkiExport.tsv(listOf(AnkiExport.Entry("sword", "espada", "He drew\this sword\n", "Berserk")))
        assertEquals("sword\tespada\tHe drew his sword\tBerserk", tsv.lines()[3])
    }
}
