package com.nelson.yomukai.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniSkipTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test
    fun readsOpeningAndEnding() {
        val frieren = AniSkip.parse(res("aniskip_frieren_5.json"))!!
        assertEquals(153.346, frieren.openingStart!!, 0.01)
        assertEquals(243.346, frieren.openingEnd!!, 0.01)
        assertEquals(1370.0, frieren.endingStart!!, 0.01)
        val onePiece = AniSkip.parse(res("aniskip_onepiece_1000.json"))!!
        assertEquals(123.834, onePiece.openingEnd!!, 0.01)
        assertNull("no ending known", onePiece.endingStart)
    }

    @Test
    fun nothingKnownIsNull() {
        assertNull(AniSkip.parse("""{"found":false,"results":[],"statusCode":404}"""))
        assertNull(AniSkip.parse("""{"found":true,"results":[{"interval":{"startTime":50,"endTime":20},"skipType":"op"}]}"""))
        assertNull(AniSkip.parse("no es json"))
    }
}
