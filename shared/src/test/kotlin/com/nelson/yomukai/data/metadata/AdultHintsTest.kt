package com.nelson.yomukai.data.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultHintsTest {
    @Test
    fun titlesThatSayItAreSexualContent() {
        listOf(
            "Teniendo sexo con la madre de mi amigo", "Tener Sexo Con Sexmuwiki", "Swingers (Bombam)", "My Stepmom Is a MILF",
            "Sex Stopwatch", "Hentai Prison", "La esclava sexual del rey", "Juego R-18"
        ).forEach { assertTrue(it, AdultHints.isExplicit(it)) }
    }

    @Test
    fun ordinaryTitlesAreNot() {
        listOf(
            "Sexto Sentido", "Essex Girls", "Super Humanos", "Solo Leveling", "Ao Ashi Season 2", "Sexagenarian Hero",
            "Prison School", "Ecchi na Onee-san", "Harem in the Labyrinth"
        ).forEach { assertFalse(it, AdultHints.isExplicit(it)) }
    }
}
