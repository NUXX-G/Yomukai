package com.nelson.yomukai.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTest {
    private fun box(x: Double, y: Double, text: String, w: Double = 0.1, h: Double = 0.05) = TextBox(x, y, w, h, text)

    @Test
    fun readingOrderFollowsTheKindOfWork() {
        // Two bubbles side by side at the top, one below.
        val left = box(0.1, 0.1, "izquierda")
        val right = box(0.7, 0.12, "derecha")
        val below = box(0.4, 0.6, "abajo")
        val boxes = listOf(below, left, right)
        assertEquals(listOf("derecha", "izquierda", "abajo"), ReadingOrder.sort(boxes, ReadingDirection.RIGHT_TO_LEFT).map { it.text })
        assertEquals(listOf("izquierda", "derecha", "abajo"), ReadingOrder.sort(boxes, ReadingDirection.LEFT_TO_RIGHT).map { it.text })
        assertEquals(listOf("izquierda", "derecha", "abajo"), ReadingOrder.sort(boxes, ReadingDirection.TOP_DOWN).map { it.text })
        assertEquals(ReadingDirection.TOP_DOWN, ReadingOrder.forType("MANHWA"))
        assertEquals(ReadingDirection.RIGHT_TO_LEFT, ReadingOrder.forType("MANGA"))
    }

    @Test
    fun soundEffectsAreToldApartFromSpeech() {
        listOf("BOOM", "¡¡ZAS!!", "Krrrr", "aaaargh", "...").forEach { assertEquals(it, BoxKind.SFX, BubbleClassifier.classify(it)) }
        listOf("¿Qué haces aquí?", "No puede ser", "Hola", "I can't believe it!").forEach { assertEquals(it, BoxKind.BUBBLE, BubbleClassifier.classify(it)) }
        // A real word drawn huge is lettering, not speech.
        assertEquals(BoxKind.SFX, BubbleClassifier.classify("Bang", boxHeight = 0.12, lines = 1))
    }

    @Test
    fun tesseractTsvBecomesParagraphBoxes() {
        val tsv = javaClass.getResourceAsStream("/ocr/tesseract_page.tsv")!!.bufferedReader().readText()
        val boxes = TesseractTsv.parse(tsv)
        assertEquals(2, boxes.size)
        val speech = boxes.first { it.kind == BoxKind.BUBBLE }
        assertEquals("No puede ser... ¿otra vez tú?", speech.text)
        assertEquals(0.1, speech.x, 1e-9)
        assertEquals(0.05, speech.y, 1e-9)
        assertEquals(BoxKind.SFX, boxes.first { it.text == "BOOM" }.kind)
    }
}
