package com.oamsapps.productsnap.image.erase

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One brush stroke in image pixel coordinates. */
data class EraseStroke(val points: List<PointF>, val radius: Float)

/**
 * Removes whatever the strokes cover and fills it from the surrounding background.
 *
 * Only the area around the strokes is processed, scaled down to [workSize] so it runs
 * in about a second on mid-range phones. The fill is scaled back up and blended in with
 * a feathered edge; everything outside the strokes stays untouched at full resolution.
 *
 * Runs fully on-device. To swap in an ML model later (e.g. LaMa or MI-GAN via TFLite),
 * replace the [PatchMatchInpainter] call; the crop/scale/blend around it stays the same.
 */
class MagicEraser(private val workSize: Int = 400) {

    suspend fun erase(source: Bitmap, strokes: List<EraseStroke>): Bitmap = withContext(Dispatchers.Default) {
        val bounds = strokeBounds(strokes) ?: return@withContext source.copy(Bitmap.Config.ARGB_8888, true)

        // Context around the strokes: the fill can only copy from what's in this region.
        val pad = max(bounds.width(), bounds.height()) * 0.75f + 32f
        val region = Rect(
            floor(bounds.left - pad).toInt(),
            floor(bounds.top - pad).toInt(),
            ceil(bounds.right + pad).toInt(),
            ceil(bounds.bottom + pad).toInt(),
        )
        if (!region.intersect(0, 0, source.width, source.height)) {
            return@withContext source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val rw = region.width()
        val rh = region.height()
        val scale = min(1f, workSize.toFloat() / max(rw, rh))
        val ww = max(1, (rw * scale).roundToInt())
        val wh = max(1, (rh * scale).roundToInt())

        // Grow the hole a little so object edges, halos and soft shadows go too.
        val dilate = 3f / scale

        // 1. Pixels around the strokes, at working size.
        val crop = Bitmap.createBitmap(source, region.left, region.top, rw, rh)
        val small = Bitmap.createScaledBitmap(crop, ww, wh, true)
        val pixels = IntArray(ww * wh).also { small.getPixels(it, 0, ww, 0, 0, ww, wh) }
        if (small !== crop) small.recycle()
        if (crop !== source) crop.recycle()

        // 2. Hole mask at working size.
        val holeBmp = renderMask(strokes, region, ww, wh, scale, extraRadius = dilate, blur = 0f)
        val maskPx = IntArray(ww * wh).also { holeBmp.getPixels(it, 0, ww, 0, 0, ww, wh) }
        holeBmp.recycle()
        val hole = BooleanArray(ww * wh) { (maskPx[it] ushr 24) > 0 }

        // 3. Fill.
        val filled = PatchMatchInpainter().inpaint(ww, wh, pixels, hole)

        // 4. Scale the fill back up and keep it only inside a feathered version of the strokes.
        val fillSmall = Bitmap.createBitmap(filled, ww, wh, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(fillSmall, rw, rh, true)
        val fill = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true)
        if (scaled !== fill && scaled !== fillSmall) scaled.recycle()
        if (fillSmall !== fill) fillSmall.recycle()

        val blend = renderMask(strokes, region, rw, rh, 1f, extraRadius = dilate * 0.5f, blur = dilate * 0.5f)
        Canvas(fill).drawBitmap(blend, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        blend.recycle()

        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(fill, region.left.toFloat(), region.top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        fill.recycle()
        out
    }

    private fun strokeBounds(strokes: List<EraseStroke>): RectF? {
        var bounds: RectF? = null
        for (s in strokes) for (p in s.points) {
            val r = RectF(p.x - s.radius, p.y - s.radius, p.x + s.radius, p.y + s.radius)
            bounds = bounds?.apply { union(r) } ?: r
        }
        return bounds
    }

    /** Draws the strokes (grown by [extraRadius]) for [region] into a [w]×[h] bitmap. */
    private fun renderMask(
        strokes: List<EraseStroke>,
        region: Rect,
        w: Int,
        h: Int,
        scale: Float,
        extraRadius: Float,
        blur: Float,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        canvas.translate(-region.left.toFloat(), -region.top.toFloat())

        val filter = if (blur > 0f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            maskFilter = filter
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            style = Paint.Style.FILL
            maskFilter = filter
        }
        for (s in strokes) {
            val r = s.radius + extraRadius
            if (s.points.size == 1) {
                canvas.drawCircle(s.points[0].x, s.points[0].y, r, dot)
            } else {
                val path = Path().apply {
                    moveTo(s.points[0].x, s.points[0].y)
                    for (i in 1 until s.points.size) lineTo(s.points[i].x, s.points[i].y)
                }
                line.strokeWidth = r * 2f
                canvas.drawPath(path, line)
            }
        }
        return bmp
    }
}
