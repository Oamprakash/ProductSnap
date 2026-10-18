package com.oamsapps.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the content goes in the exported image. Pure arithmetic, unit-tested.
 *
 * With a canvas shape ([aspect], width / height), the content is padded by [padding] of its own size on every
 * side and the canvas grows in one direction until it has that shape; the content stays centred. Without one,
 * the canvas is the content. The result is then scaled to [outWidth] x [outHeight] when given (a marketplace
 * size, which may enlarge), or down to fit [maxEdge] otherwise (never enlarged).
 */
object Framing {

    class Layout(val width: Int, val height: Int, val left: Float, val top: Float, val drawWidth: Float, val drawHeight: Float)

    fun layout(
        contentW: Int,
        contentH: Int,
        aspect: Float?,
        padding: Float,
        maxEdge: Int,
        outWidth: Int? = null,
        outHeight: Int? = null,
    ): Layout {
        require(contentW > 0 && contentH > 0) { "Empty content" }
        var canvasW: Float
        var canvasH: Float
        if (aspect == null) {
            canvasW = contentW.toFloat()
            canvasH = contentH.toFloat()
        } else {
            val cw = contentW * (1f + 2f * padding)
            val ch = contentH * (1f + 2f * padding)
            if (cw / ch > aspect) { canvasW = cw; canvasH = cw / aspect } else { canvasH = ch; canvasW = ch * aspect }
        }
        val w: Int
        val h: Int
        val scale: Float
        if (outWidth != null && outHeight != null) {
            w = outWidth
            h = outHeight
            // Shapes match when aspect = outWidth / outHeight; if not, fit inside.
            scale = min(outWidth / canvasW, outHeight / canvasH)
        } else {
            scale = min(1f, maxEdge / max(canvasW, canvasH))
            w = max(1, (canvasW * scale).roundToInt())
            h = max(1, (canvasH * scale).roundToInt())
        }
        val dw = contentW * scale
        val dh = contentH * scale
        return Layout(w, h, (w - dw) / 2f, (h - dh) / 2f, dw, dh)
    }

    /**
     * The largest rect of [aspect] inside a [w] x [h] photo, centred on the product's centre ([cx], [cy]) as far
     * as the photo's edges allow. Used for the Natural background, which crops instead of padding.
     * Returns left, top, right, bottom.
     */
    fun cropAround(w: Int, h: Int, aspect: Float, cx: Float, cy: Float): IntArray {
        val rw: Int
        val rh: Int
        if (w.toFloat() / h > aspect) { rh = h; rw = min(w, (h * aspect).roundToInt()) } else { rw = w; rh = min(h, (w / aspect).roundToInt()) }
        val left = (cx - rw / 2f).roundToInt().coerceIn(0, w - rw)
        val top = (cy - rh / 2f).roundToInt().coerceIn(0, h - rh)
        return intArrayOf(left, top, left + rw, top + rh)
    }
}
