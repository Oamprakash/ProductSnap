package com.oamsapps.imaging

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Brush fixes on a real graphics stack: removal clears alpha, restore brings back the photo's own pixels. */
@RunWith(AndroidJUnit4::class)
class CutoutBrushTest {

    private val photo = CompositorTest.photo()
    private val cutout = Compositor.cutout(photo, CompositorTest.itemMask()).bitmap

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
        (0..20).map { PointF(x0 + (x1 - x0) * it / 20f, y0 + (y1 - y0) * it / 20f) }

    @Test fun removeClearsInsideTheStrokeOnly() {
        val out = CutoutBrush.apply(cutout, photo, listOf(BrushStroke(line(1300f, 1100f, 1700f, 1100f), 40f, restore = false)))
        assertTrue("removed", Color.alpha(out.getPixel(1500, 1100)) <= 5)
        assertTrue("kept above the stroke", Color.alpha(out.getPixel(1500, 900)) >= 250)
        assertTrue("kept below the stroke", Color.alpha(out.getPixel(1500, 1300)) >= 250)
        assertEquals("base untouched", 255, Color.alpha(cutout.getPixel(1500, 1100)))
    }

    @Test fun restoreBringsBackThePhotoPixels() {
        val out = CutoutBrush.apply(cutout, photo, listOf(BrushStroke(line(200f, 300f, 800f, 300f), 50f, restore = true)))
        for (x in listOf(300, 500, 700)) {
            val c = out.getPixel(x, 300)
            val p = photo.getPixel(x, 300)
            assertTrue("opaque at $x", Color.alpha(c) >= 250)
            assertTrue("red at $x", abs(Color.red(c) - Color.red(p)) <= 4)
            assertTrue("green at $x", abs(Color.green(c) - Color.green(p)) <= 4)
            assertTrue("blue at $x", abs(Color.blue(c) - Color.blue(p)) <= 4)
        }
        assertTrue("still clear away from the stroke", Color.alpha(out.getPixel(500, 600)) <= 5)
    }

    @Test fun strokesApplyInOrderAndADotWorks() {
        val strokes = listOf(
            BrushStroke(listOf(PointF(1500f, 1100f)), 100f, restore = false),
            BrushStroke(listOf(PointF(1500f, 1100f)), 30f, restore = true),
        )
        val out = CutoutBrush.apply(cutout, photo, strokes)
        assertTrue("restored centre", Color.alpha(out.getPixel(1500, 1100)) >= 250)
        assertTrue("removed ring", Color.alpha(out.getPixel(1500, 1165)) <= 5)
    }

    @Test fun photoAtAnotherSizeIsMappedOntoTheCutout() {
        val half = Bitmap.createScaledBitmap(photo, photo.width / 2, photo.height / 2, true)
        val out = CutoutBrush.apply(cutout, half, listOf(BrushStroke(listOf(PointF(400f, 300f)), 40f, restore = true)))
        val c = out.getPixel(400, 300)
        val p = photo.getPixel(400, 300)
        assertTrue("opaque", Color.alpha(c) >= 250)
        assertTrue("red follows position", abs(Color.red(c) - Color.red(p)) <= 6)
        assertTrue("green follows position", abs(Color.green(c) - Color.green(p)) <= 6)
    }
}
