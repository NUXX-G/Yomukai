package com.nelson.yomukai.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F1.1 in the app: the same cases as web/tests/next-episode.test.mjs. */
class NextEpisodeTest {
    private fun ep(n: Float, index: Int = n.toInt() - 1, source: String = "main") = EpisodeRef(source, "m-$source", "$source-$n", n, index)
    private val own = listOf(ep(1f), ep(2f), ep(3f))

    @Test
    fun plainSource() {
        assertEquals("main-2.0", NextEpisode.pick(own[0], own)?.chapterId)
        assertNull(NextEpisode.pick(own[2], own))
    }

    @Test
    fun theNextEpisodeComesFromASisterWhenThisSourceStops() {
        val others = listOf(ep(4f, 0, "jk"), ep(3f, 1, "jk"))
        assertEquals("jk-4.0", NextEpisode.pick(own[2], own, others)?.chapterId)
    }

    @Test
    fun thisSourceWinsAndGapsAreFilledByTheSister() {
        assertEquals("main-2.0", NextEpisode.pick(own[0], own, listOf(ep(2f, 0, "b")))?.chapterId)
        val gappy = listOf(ep(1f, 0), ep(3f, 1))
        assertEquals("b-2.0", NextEpisode.pick(gappy[0], gappy, listOf(ep(2f, 0, "b")))?.chapterId)
    }

    @Test
    fun fillerIsSkippedWhenAskedAndSpecialsFollowTheOrder() {
        assertEquals("main-3.0", NextEpisode.pick(own[0], own) { it.number == 2f }?.chapterId)
        val specials = listOf(ep(0f, 0).copy(chapterId = "a"), ep(0f, 1).copy(chapterId = "b"))
        assertEquals("b", NextEpisode.pick(specials[0], specials)?.chapterId)
    }

    @Test
    fun halfEpisodesComeBetween() {
        val list = listOf(ep(12f, 0), ep(12.5f, 1), ep(13f, 2))
        assertEquals("main-12.5", NextEpisode.pick(list[0], list)?.chapterId)
    }
}
