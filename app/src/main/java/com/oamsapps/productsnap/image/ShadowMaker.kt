package com.oamsapps.productsnap.image

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.max

/**
 * Adds a soft drop shadow under a cutout so it sits on the new background instead of
 * looking pasted on. Returns a slightly larger bitmap (room for the shadow), with the
 * shadow baked in, so the preview and the export draw exactly the same thing.
 */
object ShadowMaker {

    private const val OPACITY = 0x55            // ~33% black
    private const val BLUR_FRACTION = 0.035f    // of the product's longer side
    private const val DROP_FRACTION = 0.025f    // how far the shadow sits below the product

    fun apply(cutout: Bitmap): Bitmap {
        val bounds = Cropper.subjectBounds(cutout) ?: return cutout
        val size = max(bounds.width(), bounds.height()).toFloat()
        val blur = max(2f, size * BLUR_FRACTION)
        val drop = size * DROP_FRACTION
        val margin = ceil(blur * 2 + drop).toInt()

        val out = Bitmap.createBitmap(cutout.width + margin * 2, cutout.height + margin * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out) // software canvas, so the blur mask filter is honoured

        val alpha = cutout.extractAlpha()
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OPACITY shl 24
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(alpha, margin.toFloat(), margin + drop, shadowPaint)
        alpha.recycle()

        canvas.drawBitmap(cutout, margin.toFloat(), margin.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
