package com.oamsapps.productsnap.image

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Adds a soft drop shadow under a cutout so it sits on the new background instead of
 * looking pasted on. Returns a slightly larger bitmap (room for the shadow), with the
 * shadow baked in, so the preview and the export draw exactly the same thing.
 */
object ShadowMaker {

    private const val OPACITY = 0x55            // ~33% black
    private const val BLUR_FRACTION = 0.035f    // of the product's longer side
    private const val DROP_FRACTION = 0.025f    // how far the shadow sits below the product
    /**
     * The shadow is a wide blur, so it's computed on a copy this many times smaller and scaled back up: it
     * looks the same, and blurring a 2048 px photo with a ~70 px radius was a large part of batch time.
     */
    private const val SHADOW_DOWNSCALE = 4

    fun apply(cutout: Bitmap): Bitmap {
        val bounds = Cropper.subjectBounds(cutout) ?: return cutout
        val size = max(bounds.width(), bounds.height()).toFloat()
        val blur = max(2f, size * BLUR_FRACTION)
        val drop = size * DROP_FRACTION
        val margin = ceil(blur * 2 + drop).toInt()

        val out = Bitmap.createBitmap(cutout.width + margin * 2, cutout.height + margin * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out) // software canvas, so the blur mask filter is honoured

        val shadow = smallShadow(cutout, blur)
        val sx = cutout.width.toFloat() / shadow.subjectW
        val sy = cutout.height.toFloat() / shadow.subjectH
        val left = margin - shadow.pad * sx
        val top = margin + drop - shadow.pad * sy
        canvas.drawBitmap(
            shadow.bitmap, null,
            RectF(left, top, left + shadow.bitmap.width * sx, top + shadow.bitmap.height * sy),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        shadow.bitmap.recycle()

        canvas.drawBitmap(cutout, margin.toFloat(), margin.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private class SmallShadow(val bitmap: Bitmap, val subjectW: Int, val subjectH: Int, val pad: Int)

    /** The blurred shadow at reduced size, with [SmallShadow.pad] px of room around the shrunken cutout. */
    private fun smallShadow(cutout: Bitmap, blur: Float): SmallShadow {
        val w = max(1, (cutout.width.toFloat() / SHADOW_DOWNSCALE).roundToInt())
        val h = max(1, (cutout.height.toFloat() / SHADOW_DOWNSCALE).roundToInt())
        val small = Bitmap.createScaledBitmap(cutout, w, h, true)
        val alpha = small.extractAlpha()
        if (small !== cutout) small.recycle()
        val smallBlur = max(1f, blur / SHADOW_DOWNSCALE)
        val pad = ceil(smallBlur * 2).toInt() + 1
        val out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OPACITY shl 24
            maskFilter = BlurMaskFilter(smallBlur, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(out).drawBitmap(alpha, pad.toFloat(), pad.toFloat(), paint)
        alpha.recycle()
        return SmallShadow(out, w, h, pad)
    }
}
