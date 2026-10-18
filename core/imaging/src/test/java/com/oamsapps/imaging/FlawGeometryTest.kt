package com.oamsapps.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FlawGeometryTest {

    @Test fun closeUpIsCentredWithContext() {
        // Radius 100: a 600 px square around the flaw.
        assertArrayEquals(intArrayOf(700, 400, 1300, 1000), FlawGeometry.closeUp(2048, 1536, 1000f, 700f, 100f))
    }

    @Test fun smallFlawStillGetsAReadableCloseUp() {
        assertArrayEquals(intArrayOf(760, 460, 1240, 940), FlawGeometry.closeUp(2048, 1536, 1000f, 700f, 10f))
    }

    @Test fun closeUpStaysInsideThePhoto() {
        // Flaw in the corner: the square moves in rather than shrinking.
        assertArrayEquals(intArrayOf(0, 936, 600, 1536), FlawGeometry.closeUp(2048, 1536, 20f, 1530f, 100f))
    }

    @Test fun neverLargerThanThePhoto() {
        assertArrayEquals(intArrayOf(0, 0, 400, 400), FlawGeometry.closeUp(400, 300 + 100, 200f, 200f, 300f))
    }
}
