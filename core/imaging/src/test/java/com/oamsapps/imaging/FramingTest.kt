package com.oamsapps.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FramingTest {

    @Test fun squareKeepsTheOldBehaviour() {
        // A 1000x2000 cutout, 8% padding: a 2320 square, scaled down to 2048.
        val l = Framing.layout(1000, 2000, aspect = 1f, padding = 0.08f, maxEdge = 2048)
        assertEquals(2048, l.width); assertEquals(2048, l.height)
        assertEquals(2000f * 2048f / 2320f, l.drawHeight, 0.5f)
        assertEquals((2048 - l.drawWidth) / 2f, l.left, 0.01f)
    }

    @Test fun noShapeKeepsTheContentShape() {
        val l = Framing.layout(1600, 1200, aspect = null, padding = 0.08f, maxEdge = 2048)
        assertEquals(1600, l.width); assertEquals(1200, l.height)
        assertEquals(0f, l.left, 0f); assertEquals(1600f, l.drawWidth, 0f)
    }

    @Test fun marketplaceSizeIsExactAndCanEnlarge() {
        // Etsy: 4:3 landscape at 2667x2000, from a tall cutout.
        val l = Framing.layout(800, 1500, aspect = 4f / 3f, padding = 0.08f, maxEdge = 2048, outWidth = 2667, outHeight = 2000)
        assertEquals(2667, l.width); assertEquals(2000, l.height)
        // Height-bound: content plus padding fills the height.
        assertEquals(2000f / 1.16f, l.drawHeight, 1f)
        assertEquals(l.drawWidth / l.drawHeight, 800f / 1500f, 1e-3f)
    }

    @Test fun portraitCanvasForAWideItem() {
        // Vinted-style 4:5 around a wide cutout: width-bound.
        val l = Framing.layout(2000, 1000, aspect = 0.8f, padding = 0f, maxEdge = 2048, outWidth = 1600, outHeight = 2000)
        assertEquals(1600f, l.drawWidth, 0.5f)
        assertEquals((2000 - l.drawHeight) / 2f, l.top, 0.5f)
    }

    @Test fun cropAroundCentresOnTheProductWithinThePhoto() {
        // 4:5 out of a 4000x3000 landscape photo: 2400 wide, full height.
        assertArrayEquals(intArrayOf(800, 0, 3200, 3000), Framing.cropAround(4000, 3000, 0.8f, 2000f, 1500f))
        // Product near the left edge: the crop stops at the edge.
        assertArrayEquals(intArrayOf(0, 0, 2400, 3000), Framing.cropAround(4000, 3000, 0.8f, 300f, 1500f))
        // Landscape crop out of a portrait photo.
        assertArrayEquals(intArrayOf(0, 1275, 3000, 3525), Framing.cropAround(3000, 4000, 4f / 3f, 1500f, 2400f))
    }
}
