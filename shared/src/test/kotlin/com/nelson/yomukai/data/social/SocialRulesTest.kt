package com.nelson.yomukai.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialRulesTest {
    private fun card(kind: String = "episode", cover: String = "https://img.example/c.jpg") = ShareCard(
        kind = kind, sourceId = "animeflv", mediaId = "one-piece", chapterId = "one-piece-1100", title = " One Piece\u0007 ",
        cover = cover, type = "anime", lang = "es", number = 1100.0, label = "Ep. 1100", spoiler = true
    )

    @Test
    fun cardsAreCleanedAndLeadOnlyInsideTheApp() {
        val c = ShareCard.sanitize(card())!!
        assertEquals("One Piece", c.title)
        assertEquals("ANIME", c.type)
        assertEquals("ES", c.lang)
        assertEquals("/ver/animeflv/one-piece/one-piece-1100", c.link())
        assertEquals("One Piece · Ep. 1100", c.summary())
        assertEquals("/leer/mangadex/a%2Fb/c%20d", ShareCard.sanitize(card("chapter").copy(sourceId = "mangadex", mediaId = "a/b", chapterId = "c d"))!!.link())
        // A work card carries no chapter, number or spoiler.
        val w = ShareCard.sanitize(card("work"))!!
        assertEquals("" to null, w.chapterId to w.number)
        assertFalse(w.spoiler)
        assertEquals("/obra/animeflv/one-piece", w.link())
    }

    @Test
    fun badCardsAreRefusedAndUnsafeCoversDropped() {
        assertNull(ShareCard.sanitize(card("video")))
        assertNull(ShareCard.sanitize(card().copy(sourceId = "../etc")))
        assertNull(ShareCard.sanitize(card().copy(chapterId = "")))
        assertNull(ShareCard.sanitize(card().copy(title = "  ")))
        for (bad in listOf("javascript:alert(1)", "http://plain.example/c.jpg", "https://x\" onerror=\"a", "data:image/png;base64,AAAA")) {
            assertEquals(bad, "", ShareCard.sanitize(card(cover = bad))!!.cover)
        }
        assertEquals("/api/img?u=abc", ShareCard.sanitize(card(cover = "/api/img?u=abc"))!!.cover)
        assertEquals("", ShareCard.sanitize(card().copy(type = "PORN"))!!.type)
        // Without a number nothing can be compared, so the spoiler flag goes.
        assertFalse(ShareCard.sanitize(card().copy(number = null))!!.spoiler)
        assertNull(ShareCard.sanitize(card().copy(number = Double.NaN))!!.number)
    }

    @Test
    fun spoilerShieldCoversOnlyWhoHasNotGotThere() {
        val c = ShareCard.sanitize(card())!!
        assertTrue(c.hiddenFor(null))
        assertTrue(c.hiddenFor(1099.0))
        assertFalse(c.hiddenFor(1100.0))
        assertFalse(c.copy(spoiler = false).hiddenFor(null))
    }

    private fun item(id: Long, by: Long = 1, ch: String = "c$id") = RoomQueue.Item(id, "s", "m", ch, "T", addedBy = by)

    @Test
    fun queueRules() {
        val q = (1L..3).map { item(it) }
        assertNull(RoomQueue.refuseAdd(q, item(4)))
        assertNotNull(RoomQueue.refuseAdd(q, item(9, ch = "c2")))
        assertNotNull(RoomQueue.refuseAdd((1L..10).map { item(it) }, item(11)))
        assertNull(RoomQueue.refuseAdd((1L..10).map { item(it) }, item(11, by = 2)))
        assertNotNull(RoomQueue.refuseAdd((1L..50).map { item(it, by = it) }, item(51, by = 99)))
        assertEquals(listOf(3L, 1L, 2L), RoomQueue.move(q, 3, 0).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), RoomQueue.move(q, 1, 99).map { it.id })
        assertEquals(q, RoomQueue.move(q, 42, 0))
        assertTrue(RoomQueue.canEdit(2, item(1, by = 1), controls = true))
        assertTrue(RoomQueue.canEdit(1, item(1, by = 1), controls = false))
        assertFalse(RoomQueue.canEdit(2, item(1, by = 1), controls = false))
    }

    @Test
    fun skipNeedsMoreThanHalfOfThoseStillHere() {
        assertEquals(1, RoomQueue.votesToSkip(1))
        assertEquals(2, RoomQueue.votesToSkip(2))
        assertEquals(2, RoomQueue.votesToSkip(3))
        assertEquals(3, RoomQueue.votesToSkip(4))
        assertTrue(RoomQueue.skipPasses(setOf(1, 2), setOf(1, 2, 3)))
        // Someone who voted and left no longer counts.
        assertFalse(RoomQueue.skipPasses(setOf(1, 9), setOf(1, 2, 3)))
    }

    private fun situation(me: Int = 0, peer: Int = 0, started: Int = 0, blockedByPeer: Boolean = false, blockedPeer: Boolean = false, muted: Long = 0) =
        DmRules.Situation(blockedByPeer, blockedPeer, me, peer, started, muted, now = 1_000)

    @Test
    fun dmAntispam() {
        assertNull(DmRules.refuse(situation()))
        assertNotNull(DmRules.refuse(situation(muted = 2_000)))
        assertNull(DmRules.refuse(situation(muted = 500)))
        assertNotNull(DmRules.refuse(situation(blockedByPeer = true)))
        assertNotNull(DmRules.refuse(situation(blockedPeer = true)))
        assertNull(DmRules.refuse(situation(me = 4)))
        assertNotNull(DmRules.refuse(situation(me = 5)))
        assertNull(DmRules.refuse(situation(me = 50, peer = 1)))
        assertNotNull(DmRules.refuse(situation(started = DmRules.MAX_NEW_PER_DAY)))
        // Answering someone else's conversation is never a "new" one.
        assertNull(DmRules.refuse(situation(peer = 1, started = 99)))
        assertEquals("hola\n\nque tal", DmRules.clean("  hola\n\n\n\n\u0000que tal "))
    }
}
