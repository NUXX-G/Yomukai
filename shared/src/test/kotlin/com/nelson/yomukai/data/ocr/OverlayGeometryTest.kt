package com.nelson.yomukai.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayGeometryTest {
    @Test
    fun placements() {
        assertEquals(Placement(0.5f, 0f, 0f), OverlayGeometry.place(400f, 0f, 800f, 6000f, FitMode.WIDTH))
        // A tall page inside a phone screen: fitted by height and centred across.
        assertEquals(Placement(0.5f, 50f, 0f), OverlayGeometry.place(500f, 1000f, 800f, 2000f, FitMode.INSIDE))
        assertEquals(Placement(1f, 0f, 100f), OverlayGeometry.place(800f, 1200f, 800f, 1000f, FitMode.INSIDE))
        assertNull(OverlayGeometry.place(0f, 0f, 800f, 1000f, FitMode.WIDTH))
    }
}
