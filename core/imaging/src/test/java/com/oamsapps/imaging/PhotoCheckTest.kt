package com.oamsapps.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCheckTest {

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun dimPhotoIsTooDark() {
        // Like the sweater shot: even the brightest pattern is around 70.
        val px = IntArray(10_000) { if (it % 10 == 0) grey(70) else grey(25) }
        assertTrue(PhotoCheck.isTooDark(px))
    }

    @Test fun darkSceneWithNormalHighlightsIsFine() {
        // Like the earlier dark-on-dark shots that cut out well: mostly dark, highlights around 120.
        val px = IntArray(10_000) { if (it % 10 == 0) grey(120) else grey(30) }
        assertFalse(PhotoCheck.isTooDark(px))
    }

    @Test fun aFewBrightPixelsDontHideADarkPhoto() {
        // A tiny bright label (under 1% of the photo) shouldn't count as good light.
        val px = IntArray(10_000) { if (it < 50) grey(250) else grey(30) }
        assertTrue(PhotoCheck.isTooDark(px))
    }

    @Test fun itemInsideThePhotoDoesntRunOffTheEdge() {
        val mask = Fixtures.rect(300, 200, 50, 40, 249, 159)
        assertEquals(0f, PhotoCheck.edgeShare(mask), 0f)
        assertFalse(PhotoCheck.runsOffEdge(mask))
    }

    @Test fun itemRunningAlongTwoSidesIsFlagged() {
        // Covers the whole right side and the right half of the bottom, like the merged dress.
        val mask = Fixtures.rect(300, 200, 100, 50, 299, 199)
        assertTrue("share ${PhotoCheck.edgeShare(mask)}", PhotoCheck.runsOffEdge(mask))
    }

    @Test fun smallTouchIsTolerated() {
        // A hanger hook touching the top: a few percent of the border.
        val mask = Fixtures.rect(300, 200, 140, 0, 160, 120)
        assertFalse(PhotoCheck.runsOffEdge(mask))
    }
}
