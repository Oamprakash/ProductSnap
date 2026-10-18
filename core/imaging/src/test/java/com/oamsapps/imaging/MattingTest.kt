package com.oamsapps.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MattingTest {

    private val w = 120
    private val h = 80
    private val item = rgb(50, 50, 60)    // dark item on the left, true edge between x = 59 and 60
    private val wall = rgb(185, 180, 170) // light background on the right

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun photo() = IntArray(w * h) { if (it % w < 60) item else wall }

    /** What ML Kit tends to give: a soft ramp, and in the wrong place (centred 4 px inside the real edge). */
    private fun mlKitLikeMask() = Mask(FloatArray(w * h) { ((60f - 4f - (it % w)) / 8f + 0.5f).coerceIn(0f, 1f) }, w, h)

    private val p = Matting.params(1024)

    @Test fun edgeMovesToThePhotosRealEdge() {
        val before = mlKitLikeMask()
        val after = Matting.refine(before, photo(), p)
        val row = 40 * w
        assertTrue("mask was wrong at x=58", before.values[row + 58] < 0.5f)
        assertTrue("x=58 is item: ${after.values[row + 58]}", after.values[row + 58] > 0.9f)
        assertTrue("x=61 is wall: ${after.values[row + 61]}", after.values[row + 61] < 0.1f)
    }

    @Test fun interiorAndClearBackgroundAreUntouched() {
        val after = Matting.refine(mlKitLikeMask(), photo(), p)
        for (y in 0 until h) {
            assertEquals(1f, after.values[y * w + 10], 0f)
            assertEquals(0f, after.values[y * w + 110], 0f)
        }
    }

    @Test fun emptyAndFullMasksPassThrough() {
        assertEquals(0f, Matting.refine(Mask(FloatArray(w * h), w, h), photo(), p).values.max(), 0f)
        assertEquals(1f, Matting.refine(Mask(FloatArray(w * h) { 1f }, w, h), photo(), p).values.min(), 0f)
    }

    @Test fun thinStrandOutsideTheMaskIsRecovered() {
        // A 1 px dark fibre sticking out of the item that ML Kit's mask missed.
        val px = photo()
        for (x in 60..63) px[40 * w + x] = item
        val after = Matting.refine(mlKitLikeMask(), px, p)
        assertTrue("fibre kept: ${after.values[40 * w + 62]}", after.values[40 * w + 62] > 0.5f)
        assertTrue("wall beside it stays clear: ${after.values[20 * w + 62]}", after.values[20 * w + 62] < 0.1f)
    }

    @Test fun decontaminationRemovesTheWallsTint() {
        val mask = mlKitLikeMask()
        val colors = Matting.edgeColors(Matting.refine(mask, photo(), p), photo(), p.colorRadius)
        // Full-size cutout (2x the mask), with a 50/50 edge pixel: half item, half wall.
        val fw = 2 * w
        val fh = 2 * h
        val mixed = (128 shl 24) or (((50 + 185) / 2) shl 16) or (((50 + 180) / 2) shl 8) or ((60 + 170) / 2)
        val cut = IntArray(fw * fh)
        val i = 80 * fw + 118
        cut[i] = mixed
        assertEquals(1, Matting.decontaminate(cut, fw, fh, colors))
        val c = cut[i]
        assertEquals(128, c ushr 24) // alpha is kept
        for ((shift, want) in listOf(16 to 50, 8 to 50, 0 to 60)) {
            val got = (c shr shift) and 0xFF
            assertTrue("channel $shift: $got, want ~$want", abs(got - want) <= 4)
        }
    }

    @Test fun opaqueAndTransparentPixelsAreLeftAlone() {
        val colors = Matting.edgeColors(mlKitLikeMask(), photo(), p.colorRadius)
        val cut = IntArray(w * h) { if (it % w < 60) item else 0 }
        val copy = cut.copyOf()
        assertEquals(0, Matting.decontaminate(cut, w, h, colors))
        assertTrue(cut.contentEquals(copy))
    }

    @Test fun lowContrastFallsBackToTheMask() {
        // Grey item on a nearly identical grey wall: colour can't tell them apart, so ML Kit's mask is kept.
        val px = IntArray(w * h) { if (it % w < 60) rgb(150, 150, 150) else rgb(155, 153, 150) }
        val before = mlKitLikeMask()
        val after = Matting.refine(before, px, p)
        val row = 40 * w
        for (x in 50..62) assertEquals("x=$x", before.values[row + x], after.values[row + x], 0.15f)
    }

    @Test fun cameraNoiseDoesNotSpeckleTheEdge() {
        val rnd = java.util.Random(7)
        fun n(v: Int) = (v + rnd.nextGaussian() * 8).toInt().coerceIn(0, 255)
        val px = IntArray(w * h) { if (it % w < 60) rgb(n(50), n(50), n(60)) else rgb(n(185), n(180), n(170)) }
        val after = Matting.refine(mlKitLikeMask(), px, p)
        for (y in 0 until h) {
            assertTrue("y=$y x=57", after.values[y * w + 57] > 0.8f)
            assertTrue("y=$y x=62", after.values[y * w + 62] < 0.2f)
        }
    }
}
