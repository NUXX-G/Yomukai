package com.nelson.yomukai.ui

import com.nelson.yomukai.ui.reader.BorderCrop
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BorderCropTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun page(w: Int, h: Int, content: IntRange, rows: IntRange): IntArray =
        IntArray(w * h) { i -> if (i % w in content && i / w in rows) black else white }

    @Test
    fun cropsWhiteMargins() {
        val px = page(100, 100, content = 20..79, rows = 10..89)
        assertArrayEquals(intArrayOf(20, 10, 80, 90), BorderCrop.contentBounds(px, 100, 100))
    }

    @Test
    fun noCropWhenContentFillsPage() {
        assertNull(BorderCrop.contentBounds(page(100, 100, 0..99, 0..99), 100, 100))
    }

    @Test
    fun blankPageIsLeftAlone() {
        assertNull(BorderCrop.contentBounds(IntArray(100 * 100) { white }, 100, 100))
    }
}
