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

    @Test
    fun aGenreCanSayItToo() {
        val jk = MediaItem("one-piece", "One Piece", "", MediaType.ANIME, "", "", "jkanime", genres = listOf("Acción", "Español Latino"))
        assertEquals(AudioTrack.LATINO, AudioTrack.of(jk, "ES"))
        assertEquals(AudioTrack.ORIGINAL, AudioTrack.of(jk.copy(type = MediaType.MANGA), "ES"))
        assertEquals("the site's word wins", AudioTrack.CASTELLANO, AudioTrack.of(jk.copy(audio = AudioTrack.CASTELLANO), "ES"))
    }

    @Test
    fun aDubIsTheSameWorkSoItsNameLosesTheAudioNote() {
        assertEquals("Dragon Ball Super", AudioTrack.strip("Dragon Ball Super Latino"))
        assertEquals("Frieren", AudioTrack.strip("Frieren (Dub)"))
        assertEquals("One Piece", AudioTrack.strip("One Piece Castellano"))
        assertEquals("Shingeki no Kyojin", AudioTrack.strip("Shingeki no Kyojin - Audio Latino"))
        assertEquals("Kimetsu no Yaiba", AudioTrack.strip("Kimetsu no Yaiba [Sub Español]"))
        assertEquals("a title that is only the word stays", "Latino", AudioTrack.strip("Latino"))
        assertEquals("Platinum End", AudioTrack.strip("Platinum End"))
    }
}
