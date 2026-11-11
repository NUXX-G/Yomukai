package com.nelson.yomukai.server

import org.junit.Assert.assertEquals
import org.junit.Test

/** O2/O4: a work opens subtitled (original audio) by default, a dub only from its section, never a broken source first. */
class WorkPlacesOrderTest {
    private val latino = "animedlatino" to "1"
    private val sub = "animeflv" to "2"
    private val cast = "ninja" to "3"
    private val all = listOf(latino, cast, sub)
    private val audios = mapOf(latino to "LATINO", cast to "CASTELLANO", sub to null)

    @Test fun `by default the subtitled version opens, not a dub`() {
        assertEquals(sub, orderWorkPlaces(all, emptySet(), audios, null, emptySet()).first())
    }

    @Test fun `from the castellano section the castellano dub opens`() {
        assertEquals(cast, orderWorkPlaces(all, emptySet(), audios, "CASTELLANO", emptySet()).first())
    }

    @Test fun `the source the user follows comes before other subtitled ones`() {
        assertEquals(latino, orderWorkPlaces(all, setOf(latino), audios, null, emptySet()).first())
    }

    @Test fun `a source that is down goes last even if the user follows it`() {
        val order = orderWorkPlaces(all, setOf(sub), audios, null, setOf("animeflv"))
        assertEquals(sub, order.last())
    }
}
