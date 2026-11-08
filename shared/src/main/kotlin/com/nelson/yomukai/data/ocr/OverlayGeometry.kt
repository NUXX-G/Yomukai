package com.nelson.yomukai.data.ocr

/** How a page image is drawn in its box, as the reader's content scales do. */
enum class FitMode { WIDTH, HEIGHT, INSIDE }

/** Where the image lands inside its box: image pixel x → `dx + x * scale`. */
data class Placement(val scale: Float, val dx: Float, val dy: Float)

object OverlayGeometry {
    /** Placement of an [imageW]×[imageH] image in a [boxW]×[boxH] box, centred like the reader draws it. */
    fun place(boxW: Float, boxH: Float, imageW: Float, imageH: Float, mode: FitMode): Placement? {
        if (boxW <= 0f || imageW <= 0f || imageH <= 0f) return null
        val scale = when (mode) {
            FitMode.WIDTH -> boxW / imageW
            FitMode.HEIGHT -> if (boxH > 0f) boxH / imageH else return null
            FitMode.INSIDE -> if (boxH > 0f) minOf(boxW / imageW, boxH / imageH) else boxW / imageW
        }
        val dx = (boxW - imageW * scale) / 2f
        val dy = if (boxH > 0f) ((boxH - imageH * scale) / 2f).coerceAtLeast(0f) else 0f
        return Placement(scale, dx, dy)
    }
}
