package com.oamsapps.productsnap.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.oamsapps.productsnap.image.background.BackgroundStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Everything needed to produce the final image. Mirrors what the preview shows. */
data class RenderSpec(
    val content: Bitmap,              // cutout (transparent bg) or original photo
    val adjustments: Adjustments,
    val background: BackgroundStyle?, // null = keep transparent / original
    val backgroundPhoto: Bitmap? = null, // prepared bitmap for BackgroundStyle.Photo
    val squareCanvas: Boolean,        // marketplace-style 1:1 with padding
    val paddingFraction: Float = 0.08f,
    val maxOutputEdge: Int,           // free vs premium resolution
    val watermark: String?,           // null for premium
)

object ImageRenderer {

    // Shared with the Compose preview so both draw the same studio light.
    const val STUDIO_CENTER_X = 0.5f
    const val STUDIO_CENTER_Y = 0.42f
    const val STUDIO_RADIUS = 0.75f   // of the longer side

    suspend fun render(spec: RenderSpec): Bitmap = withContext(Dispatchers.Default) {
        val src = spec.content

        // 1. Work out output size.
        val (outW, outH) = if (spec.squareCanvas) {
            val side = (max(src.width, src.height) * (1f + 2 * spec.paddingFraction)).roundToInt()
            side to side
        } else {
            src.width to src.height
        }
        val scale = minOf(1f, spec.maxOutputEdge.toFloat() / max(outW, outH))
        val w = (outW * scale).roundToInt()
        val h = (outH * scale).roundToInt()

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // 2. Background.
        spec.background?.let { drawBackground(canvas, it, spec.backgroundPhoto, w, h) }

        // 3. Subject, with colour adjustments applied only to it.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            if (!spec.adjustments.isNeutral) {
                colorFilter = ColorMatrixColorFilter(spec.adjustments.toColorMatrix())
            }
        }
        val drawW = src.width * scale
        val drawH = src.height * scale
        val left = (w - drawW) / 2f
        val top = (h - drawH) / 2f
        canvas.drawBitmap(src, null, RectF(left, top, left + drawW, top + drawH), paint)

        // 4. Watermark for free exports.
        spec.watermark?.let { drawWatermark(canvas, it, w, h) }

        out
    }

    fun drawBackground(canvas: Canvas, style: BackgroundStyle, photo: Bitmap?, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when (style) {
            is BackgroundStyle.Solid -> canvas.drawColor(style.color)
            is BackgroundStyle.Gradient -> {
                paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), style.top, style.bottom, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            is BackgroundStyle.Studio -> {
                paint.shader = RadialGradient(
                    w * STUDIO_CENTER_X, h * STUDIO_CENTER_Y, max(w, h) * STUDIO_RADIUS,
                    style.center, style.edge, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            is BackgroundStyle.Photo -> {
                if (photo == null) { canvas.drawColor(0xFFFFFFFF.toInt()); return }
                // Cover: fill the canvas, crop the overflow, keep it centred.
                val s = max(w / photo.width.toFloat(), h / photo.height.toFloat())
                val dw = photo.width * s
                val dh = photo.height * s
                val l = (w - dw) / 2f
                val t = (h - dh) / 2f
                canvas.drawBitmap(photo, null, RectF(l, t, l + dw, t + dh), paint)
            }
        }
    }

    private fun drawWatermark(canvas: Canvas, text: String, w: Int, h: Int) {
        val size = max(w, h) * 0.028f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            color = 0x99FFFFFF.toInt()
            setShadowLayer(size * 0.15f, 0f, 0f, 0x66000000)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(text, w - size, h - size, paint)
    }
}
