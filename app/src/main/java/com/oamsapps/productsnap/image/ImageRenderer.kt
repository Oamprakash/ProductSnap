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
import com.oamsapps.imaging.Framing
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
    /** Canvas shape (width / height), padded around the content; null keeps the content's own shape. */
    val canvasAspect: Float?,
    val paddingFraction: Float = 0.08f,
    val maxOutputEdge: Int,
    /** Exact output size (a marketplace preset); otherwise scaled down to [maxOutputEdge]. */
    val outWidth: Int? = null,
    val outHeight: Int? = null,
    val watermark: String?,           // null for premium
    /** Flaw circles, in [content]'s pixel coordinates. */
    val marks: List<RingMark> = emptyList(),
)

/** A circle around a flaw: centre and radius in content pixels. */
data class RingMark(val x: Float, val y: Float, val r: Float)

object ImageRenderer {

    // Shared with the Compose preview so both draw the same studio light.
    const val STUDIO_CENTER_X = 0.5f
    const val STUDIO_CENTER_Y = 0.42f
    const val STUDIO_RADIUS = 0.75f   // of the longer side

    suspend fun render(spec: RenderSpec): Bitmap = withContext(Dispatchers.Default) {
        val src = spec.content

        // 1. Work out output size and where the content goes (tested in core:imaging).
        val layout = Framing.layout(
            src.width, src.height, spec.canvasAspect, spec.paddingFraction, spec.maxOutputEdge, spec.outWidth, spec.outHeight,
        )
        val w = layout.width
        val h = layout.height

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
        canvas.drawBitmap(src, null, RectF(layout.left, layout.top, layout.left + layout.drawWidth, layout.top + layout.drawHeight), paint)

        // 4. Flaw circles, on top of the product.
        if (spec.marks.isNotEmpty()) {
            val s = layout.drawWidth / src.width
            spec.marks.forEach { m -> drawRing(canvas, layout.left + m.x * s, layout.top + m.y * s, m.r * s) }
        }

        // 5. Watermark for free exports.
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
            // Natural content is the photo itself; nothing to draw behind it.
            BackgroundStyle.Natural -> Unit
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

    /** Red ring with a thin white edge, so it shows on dark and light items alike. Shared with the preview. */
    fun drawRing(canvas: Canvas, x: Float, y: Float, r: Float) {
        val width = max(3f, r * RING_WIDTH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        paint.color = 0xFFFFFFFF.toInt()
        paint.strokeWidth = width * 1.6f
        canvas.drawCircle(x, y, r, paint)
        paint.color = RING_COLOR
        paint.strokeWidth = width
        canvas.drawCircle(x, y, r, paint)
    }

    const val RING_COLOR = 0xFFE53935.toInt()
    const val RING_WIDTH = 0.12f

    /**
     * Free exports: the name in large letters, diagonally across the middle where the product sits, so it
     * can't be cropped off. Semi-transparent white with a dark outline, so it reads on white and dark alike
     * while the product stays visible underneath.
     */
    private fun drawWatermark(canvas: Canvas, text: String, w: Int, h: Int) {
        val diagonal = kotlin.math.hypot(w.toFloat(), h.toFloat())
        val angle = -Math.toDegrees(kotlin.math.atan2(h.toDouble(), w.toDouble())).toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = 0x73FFFFFF
        }
        // Size the text to span about 70% of the diagonal.
        fill.textSize = 100f
        fill.textSize = 100f * (diagonal * 0.7f) / fill.measureText(text)
        val outline = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = fill.textSize * 0.03f
            color = 0x4D000000
        }
        val baseline = -(fill.descent() + fill.ascent()) / 2f
        canvas.save()
        canvas.rotate(angle, w / 2f, h / 2f)
        canvas.drawText(text, w / 2f, h / 2f + baseline, outline)
        canvas.drawText(text, w / 2f, h / 2f + baseline, fill)
        canvas.restore()
    }
}
