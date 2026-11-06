package com.nelson.yomukai.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PartySyncTest {
    private val base = PartyState("animeflv", "m", "c1", position = 100.0, playing = true, at = 1_000)

    @Test
    fun expectedPositionAdvancesOnlyWhilePlaying() {
        assertEquals(110.0, PartySync.expectedPosition(base, 11_000), 1e-9)
        assertEquals(115.0, PartySync.expectedPosition(base.copy(speed = 1.5), 11_000), 1e-9)
        assertEquals(100.0, PartySync.expectedPosition(base.copy(playing = false), 11_000), 1e-9)
        // A clock a little behind the stamp never moves it backwards.
        assertEquals(100.0, PartySync.expectedPosition(base, 500), 1e-9)
    }

    @Test
    fun driftIsSeekedNudgedOrLeftAlone() {
        assertEquals(Correction.Seek(50.0), PartySync.correction(47.5, 50.0))
        assertEquals(Correction.Seek(50.0), PartySync.correction(52.1, 50.0))
        assertEquals(Correction.Rate(1.05), PartySync.correction(49.0, 50.0))
        assertEquals(Correction.Rate(0.95), PartySync.correction(51.0, 50.0))
        assertEquals(Correction.None, PartySync.correction(50.2, 50.0))
        assertEquals(Correction.Rate(1.5 * 1.05), PartySync.correction(49.0, 50.0, speed = 1.5))
    }

    @Test
    fun loadingPausesUntilEveryoneIsReadyOrTenSecondsPass() {
        assertTrue(PartySync.canResume(emptySet(), 0, 1))
        assertFalse(PartySync.canResume(setOf(2L), 0, 9_999))
        assertTrue(PartySync.canResume(setOf(2L), 0, 10_000))
        val paused = PartySync.pausedAt(base, 6_000)
        assertEquals(105.0, paused.position, 1e-9)
        assertFalse(paused.playing)
        assertEquals(105.0, PartySync.expectedPosition(PartySync.resumedAt(paused, 20_000), 20_000), 1e-9)
    }

    @Test
    fun hostStaysOrPassesToWhoeverJoinedFirst() {
        assertEquals(1L, PartySync.nextHost(1, listOf(1, 2, 3), leaving = 3))
        assertEquals(2L, PartySync.nextHost(1, listOf(1, 2, 3), leaving = 1))
        assertEquals(2L, PartySync.nextHost(null, listOf(2, 3), leaving = null))
        assertNull(PartySync.nextHost(1, listOf(1), leaving = 1))
        assertTrue(PartySync.canControl(5, host = 1, everyoneControls = true))
        assertFalse(PartySync.canControl(5, host = 1, everyoneControls = false))
    }

    @Test
    fun roomCodesAvoidLookAlikes() {
        val code = RoomCodes.generate(Random(7))
        assertEquals(RoomCodes.LENGTH, code.length)
        assertTrue(code.none { it in "01OIL" })
        assertEquals(code, RoomCodes.normalize(" ${code.lowercase().chunked(3).joinToString("-")} "))
        assertNull(RoomCodes.normalize("ABC10O"))
        assertNull(RoomCodes.normalize("ABCDE"))
    }

    @Test
    fun chatIsCleanedLimitedAndKnowsMentions() {
        val guard = ChatGuard(maxMessages = 2, windowMs = 1_000)
        assertTrue(guard.allowMessage(1, 0))
        assertTrue(guard.allowMessage(1, 10))
        assertFalse(guard.allowMessage(1, 20))
        assertTrue(guard.allowMessage(2, 20))
        assertTrue(guard.allowMessage(1, 1_500))
        assertEquals("hola\n\nadiós", ChatGuard.clean("  hola\n\n\n\nadiós\u0007 "))
        assertEquals(500, ChatGuard.clean("x".repeat(900)).length)
        assertEquals(listOf("ana", "luis.m"), ChatGuard.mentions("@ana mira esto, @luis.m. y otra vez @ana; correo a@b.com"))
    }
}
