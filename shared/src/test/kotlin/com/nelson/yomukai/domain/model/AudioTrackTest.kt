package com.nelson.yomukai.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** F3.1: the audio language read from what real sites write. */
class AudioTrackTest {
    @Test
    fun readsWhatTheSitesWrite() {
        assertEquals(AudioTrack.LATINO, AudioTrack.detect("dragon-ball-super-latino", "ES"))
        assertEquals(AudioTrack.LATINO, AudioTrack.detect("Dragon Ball Super Latino", "ES"))
        assertEquals(AudioTrack.CASTELLANO, AudioTrack.detect("One Piece (Castellano)", "ES"))
        assertEquals(AudioTrack.CASTELLANO, AudioTrack.detect("Doblaje en español de España", "ES"))
        assertEquals(AudioTrack.INGLES, AudioTrack.detect("Solo Leveling (Dub)", "EN"))
        assertEquals(AudioTrack.INGLES, AudioTrack.detect("Frieren English Dubbed", "EN"))
        assertEquals(AudioTrack.DOBLADO, AudioTrack.detect("Doblado", "ES"))
        assertEquals(AudioTrack.ORIGINAL, AudioTrack.detect("Sub español", "ES"))
        assertEquals(AudioTrack.ORIGINAL, AudioTrack.detect("Kusuriya no Hitorigoto", "ES"))
        // Words that only contain the letters are not a dub («Dubai», «Platinum»).
        assertEquals(AudioTrack.ORIGINAL, AudioTrack.detect("Dubai Detective", "EN"))
        assertEquals(AudioTrack.ORIGINAL, AudioTrack.detect("Platinum End", "ES"))
    }
}
