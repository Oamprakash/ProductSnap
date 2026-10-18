package com.oamsapps.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLightTest {

    private val w = 300
    private val h = 200
    private val item = Fixtures.rect(w, h, 100, 50, 199, 149)

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun scene(bg: Int, fg: Int) = IntArray(w * h) { i -> if (item.values[i] > 0.5f) fg else bg }

    @Test fun blackItemInBrightRoomStaysBlack() {
        // The case that turned black jeans grey when the item alone was measured.
        val f = AutoLight.analyzePixels(scene(rgb(235, 235, 235), rgb(30, 30, 32)), item)
        assertEquals(1f, f.exposure, 0.06f)
    }

    @Test fun veryDarkPhotoIsLiftedFurtherButCapped() {
        // Brightest areas around 60 of 255: taken in too little light (like the test sweater shots at 66-78).
        val f = AutoLight.analyzePixels(scene(rgb(60, 60, 60), rgb(40, 40, 40)), item)
        assertEquals(AutoLight.DEFAULT_DIM_CAP, f.exposure, 1e-4f)
    }

    @Test fun strengthCanChangeWithoutMeasuringAgain() {
        val f = AutoLight.analyzePixels(scene(rgb(60, 60, 60), rgb(40, 40, 40)), item)
        assertEquals(AutoLight.MAX_EXPOSURE, AutoLight.withDimCap(f, 1.6f).exposure, 1e-4f)
        assertEquals(2.8f, AutoLight.withDimCap(f, 2.8f).exposure, 1e-4f)
        // A normally exposed photo is the same at every strength.
        val normal = AutoLight.analyzePixels(scene(rgb(130, 130, 130), rgb(40, 40, 40)), item)
        assertEquals(normal.exposure, AutoLight.withDimCap(normal, 2.8f).exposure, 1e-4f)
        assertEquals(LightFix.NONE, AutoLight.withDimCap(LightFix.NONE, 2.8f))
    }

    @Test fun moderatelyDarkPhotoKeepsTheNormalCap() {
        // Brightest areas around 130: a dark scene, but exposed normally (like the earlier dark-on-dark shots).
        val f = AutoLight.analyzePixels(scene(rgb(130, 130, 130), rgb(40, 40, 40)), item)
        assertEquals(AutoLight.MAX_EXPOSURE, f.exposure, 1e-4f)
    }

    @Test fun capRisesSmoothlyBetweenNormalAndVeryDark() {
        assertEquals(AutoLight.MAX_EXPOSURE, AutoLight.exposureCap(AutoLight.DIM_START), 1e-4f)
        assertEquals(AutoLight.MAX_EXPOSURE_DIM, AutoLight.exposureCap(AutoLight.DIM_FULL, 2.8f), 1e-4f)
        val mid = AutoLight.exposureCap((AutoLight.DIM_START + AutoLight.DIM_FULL) / 2, 2.8f)
        assertEquals((AutoLight.MAX_EXPOSURE + AutoLight.MAX_EXPOSURE_DIM) / 2, mid, 1e-4f)
    }

    @Test fun itemHighlightsLimitTheLift() {
        // Dim room, but the item itself is already near white: lifting more would clip it.
        val f = AutoLight.analyzePixels(scene(rgb(90, 90, 90), rgb(220, 220, 220)), item)
        assertTrue("exposure ${f.exposure}", f.exposure <= 240f / 220f + 0.01f)
    }

    @Test fun colourCastIsCorrectedWithinLimit() {
        // A grey item under warm light.
        val f = AutoLight.analyzePixels(scene(rgb(200, 190, 170), rgb(150, 142, 135)), item)
        assertTrue(f.wbApplied)
        for (g in listOf(f.gainR, f.gainG, f.gainB)) assertTrue("gain $g", g in (1f - AutoLight.MAX_WB_SHIFT)..(1f + AutoLight.MAX_WB_SHIFT))
        assertTrue("blue lifted ${f.gainB} vs red ${f.gainR}", f.gainB > f.gainR)
    }

    @Test fun colourfulItemKeepsItsColours() {
        // Saturated pink with no neutral areas: no colour correction.
        val f = AutoLight.analyzePixels(scene(rgb(230, 230, 230), rgb(210, 40, 110)), item)
        assertFalse(f.wbApplied)
        assertEquals(1f, f.gainR, 0f); assertEquals(1f, f.gainG, 0f); assertEquals(1f, f.gainB, 0f)
    }

    @Test fun emptyMaskChangesNothing() {
        val f = AutoLight.analyzePixels(scene(rgb(50, 50, 50), rgb(50, 50, 50)), Mask(FloatArray(w * h), w, h))
        assertEquals(LightFix.NONE, f)
    }
}
