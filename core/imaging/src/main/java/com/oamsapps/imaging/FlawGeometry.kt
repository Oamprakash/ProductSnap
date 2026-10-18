package com.oamsapps.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a marked flaw's close-up photo is cut from. Pure arithmetic, unit-tested. */
object FlawGeometry {

    /** A close-up never goes below this many pixels a side (smaller looks like a blur on a listing). */
    const val MIN_CLOSE_UP = 480
    /** ...and shows the flaw with some surroundings, so a buyer can tell where on the item it is. */
    const val CONTEXT = 3f

    /**
     * Square around a flaw at ([cx], [cy]) with radius [r], in a [w] x [h] photo: [CONTEXT] times the circle's
     * diameter, at least [MIN_CLOSE_UP] (or the photo's shorter side if smaller), moved inside the photo.
     * Returns left, top, right, bottom.
     */
    fun closeUp(w: Int, h: Int, cx: Float, cy: Float, r: Float): IntArray {
        val side = min(min(w, h), max(MIN_CLOSE_UP, (2f * r * CONTEXT).roundToInt()))
        val left = (cx - side / 2f).roundToInt().coerceIn(0, w - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(0, h - side)
        return intArrayOf(left, top, left + side, top + side)
    }
}
