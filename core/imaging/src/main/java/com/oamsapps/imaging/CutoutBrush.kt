package com.oamsapps.imaging

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.max

/** One brush stroke on a cutout, in the cutout's pixel coordinates. */
data class BrushStroke(
    val points: List<PointF>,
    val radius: Float,
    /** true paints the photo back in; false removes from the cutout. */
    val restore: Boolean,
)

/**
 * Hand fixes to a cutout: remove what ML Kit wrongly kept (a hanger, background trapped between straps) or
 * restore what it missed. Restored pixels come from the original photo, never invented, so the product stays
 * exactly as photographed. Edges are slightly soft so a fix blends with the cutout's own feathered edge.
 */
object CutoutBrush {

    /** Edge softness, as a fraction of the brush radius. */
    private const val SOFTNESS = 0.25f

    /** A new bitmap: [base] with [strokes] applied in order. [photo] is the original the cutout was made from. */
    fun apply(base: Bitmap, photo: Bitmap, strokes: List<BrushStroke>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        // The photo may be stored at another size than the cutout; map it onto the cutout's pixels.
        val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (photo.width != base.width || photo.height != base.height) {
                setLocalMatrix(Matrix().apply {
                    setScale(base.width / photo.width.toFloat(), base.height / photo.height.toFloat())
                })
            }
        }
        for (s in strokes) draw(canvas, s, shader)
        return out
    }

    private fun draw(canvas: Canvas, s: BrushStroke, photoShader: Shader) {
        if (s.points.isEmpty() || s.radius <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s.radius * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter(max(0.5f, s.radius * SOFTNESS), BlurMaskFilter.Blur.NORMAL)
            if (s.restore) {
                // The photo, painted over the cutout: opaque in the middle, blending at the soft edge.
                shader = photoShader
            } else {
                // Keeps what's already there times (1 - brush coverage).
                color = 0xFF000000.toInt()
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
        }
        if (s.points.size == 1) {
            canvas.drawPoint(s.points[0].x, s.points[0].y, paint)
        } else {
            val path = Path().apply {
                moveTo(s.points[0].x, s.points[0].y)
                for (i in 1 until s.points.size) lineTo(s.points[i].x, s.points[i].y)
            }
            canvas.drawPath(path, paint)
        }
    }
}
