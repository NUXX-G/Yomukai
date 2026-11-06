package com.nelson.yomukai.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** The app's colours must be the web's: DesignTokens.kt is generated from tokens.json and must not drift. */
class DesignTokensTest {
    @Test
    fun generatedColoursMatchTheSharedTokens() {
        val json = javaClass.getResourceAsStream("/design/tokens.json")!!.bufferedReader().readText()
        val colorBlock = json.substringAfter("\"color\"").substringAfter("{").substringBefore("}")
        val expected = Regex(""""(\w+)"\s*:\s*"(#[0-9a-fA-F]{6})"""").findAll(colorBlock)
            .associate { it.groupValues[1] to it.groupValues[2].lowercase() }
        assertEquals("Ejecuta node scripts/tokens-android.mjs", expected, DesignTokens.HEX)
    }
}
