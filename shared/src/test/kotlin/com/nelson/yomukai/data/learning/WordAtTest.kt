package com.nelson.yomukai.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordAtTest {
    @Test
    fun wordsAndSentencesWithSpaces() {
        val text = "He smiled. Don't look at the well-known door! Then he left."
        assertEquals(PickedWord("Don't", "Don't look at the well-known door!"), WordAt.find(text, 13))
        assertEquals("well-known", WordAt.find(text, text.indexOf("known"))?.word)
        assertEquals(PickedWord("left", "Then he left."), WordAt.find(text, text.length - 2))
        assertNull(WordAt.find(text, text.indexOf(' ')))
    }

    @Test
    fun chineseTakesTheTextFromThePress() {
        val text = "我们去北京吧。他很高兴。"
        assertEquals(PickedWord("北京吧", "我们去北京吧。"), WordAt.find(text, 3))
        assertEquals("他很高兴", WordAt.find(text, 7)?.word)
    }
}
