package com.oamsapps.imaging

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/** Something about a photo that makes a poor cutout likely. Shown as a tip the seller can ignore. */
enum class PhotoWarning {
    /** Taken in too little light: the cutout may miss parts or take in background. */
    TOO_DARK,
    /** The cutout runs along the photo's edge: the item is cut off there, or background was taken in. */
    RUNS_OFF_EDGE,
}

/**
 * Cheap checks, tuned on the test photos (Oct 2026):
 *  - Brightest areas (99th percentile of each pixel's brightest channel): every photo that cut out well was at
 *    116 or more (most 168+); the two that failed in dim light were 65 and 78.
 *  - Share of the photo's border covered by the cutout: good cutouts 0-3%; a dress merged with the bedspread
 *    and a shirt that kept a strip of background both ran along the edge far more (the shirt 17%).
 * Blur was tried and left out: a blurry failure and a sharp good photo measured the same.
 */
object PhotoCheck {

    const val DARK_P99 = 100
    const val EDGE_SHARE = 0.12f
    private const val SAMPLE_EDGE = 256

    /** Brightness of the photo's brightest areas, 0..255, on ARGB pixels. Pure Kotlin for unit tests. */
    fun brightP99(px: IntArray): Int {
        val hist = IntArray(256)
        for (c in px) hist[max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF))]++
        val target = (px.size * 0.99f).toInt()
        var acc = 0
        for (v in hist.indices) { acc += hist[v]; if (acc > target) return v }
        return 255
    }

    fun isTooDark(px: IntArray) = px.isNotEmpty() && brightP99(px) < DARK_P99

    /** Checks a photo for darkness on a small copy (a few ms). */
    fun isTooDark(photo: Bitmap): Boolean {
        val s = SAMPLE_EDGE.toFloat() / max(photo.width, photo.height)
        val small = if (s < 1f) {
            Bitmap.createScaledBitmap(photo, max(1, (photo.width * s).roundToInt()), max(1, (photo.height * s).roundToInt()), true)
        } else photo
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== photo) small.recycle()
        return isTooDark(px)
    }

    /** Share of the border pixels (all four sides) that belong to the item. */
    fun edgeShare(mask: Mask): Float {
        val w = mask.width
        val h = mask.height
        if (w < 2 || h < 2) return 0f
        val m = mask.values
        var on = 0
        for (x in 0 until w) {
            if (m[x] > 0.5f) on++
            if (m[(h - 1) * w + x] > 0.5f) on++
        }
        for (y in 1 until h - 1) {
            if (m[y * w] > 0.5f) on++
            if (m[y * w + w - 1] > 0.5f) on++
        }
        return on.toFloat() / (2 * w + 2 * (h - 2))
    }

    fun runsOffEdge(mask: Mask) = edgeShare(mask) > EDGE_SHARE
}
