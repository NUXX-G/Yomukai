package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.network.WebProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A web page where an API should answer must never reach the user as Gson's «MalformedJsonException». */
class NotJsonTest {
    private val botCheck = """<!DOCTYPE html><html><head><title>Verification</title></head><body>
        <h1>Security check</h1><p>Checking your browser</p>
        <p>Before you can access this site, we need to verify you’re human.</p>
        <p>Verification failed. Please refresh and try again.</p></body></html>"""

    @Test fun `a bot check page is recognized and explained`() {
        assertTrue(WebProtection.isGenericBotCheck(botCheck))
        assertNotNull(WebProtection.detectProtection(200, botCheck))
        val e = assertThrows(NotJsonException::class.java) { parseJson(botCheck) }
        assertTrue(e.message, "anti-bot" in e.message.orEmpty())
        assertFalse("Strictness" in e.message.orEmpty())
    }

    @Test fun `broken text and pages get a clear message`() {
        assertThrows(NotJsonException::class.java) { parseJson("<html><body>404</body></html>") }
        val e = assertThrows(NotJsonException::class.java) { parseJson("Bad gateway {") }
        assertFalse("Strictness" in e.message.orEmpty())
    }

    @Test fun `byte order mark and anti-hijacking prefix are accepted`() {
        assertEquals(1, parseJson("﻿{\"a\":1}").asJsonObject["a"].asInt)
        assertEquals(2, parseJson(")]}'\n{\"a\":2}").asJsonObject["a"].asInt)
    }

    @Test fun `an article quoting the phrase is not a bot check`() {
        assertFalse(WebProtection.isGenericBotCheck("<html><body><p>Chapter 3: Security check</p></body></html>"))
    }
}
