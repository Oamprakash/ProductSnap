package com.oamsapps.productsnap.image

import android.graphics.Bitmap
import android.graphics.Rect
import com.oamsapps.imaging.Framing
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Crop applied to every photo in a batch. */
enum class BatchCrop(val label: String, val aspect: Float?) {
    NONE("Off", null),
    TIGHT("Fit product", null),
    SQUARE("1:1", 1f),
    PORTRAIT_4_5("4:5", 4f / 5f),
    PORTRAIT_3_4("3:4", 3f / 4f),
}

object Cropper {

    /** Bounding box of pixels with alpha above [threshold] (the product in a cutout), or null if empty. */
    fun subjectBounds(bmp: Bitmap, threshold: Int = 24): Rect? {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var first = -1
            for (x in 0 until w) if ((row[x] ushr 24) > threshold) { first = x; break }
            if (first < 0) continue
            var last = first
            for (x in w - 1 downTo first) if ((row[x] ushr 24) > threshold) { last = x; break }
            if (first < minX) minX = first
            if (last > maxX) maxX = last
            if (y < minY) minY = y
            maxY = y
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Grows [r] by [fraction] of its longer side on every edge, kept inside w×h. */
    fun padded(r: Rect, fraction: Float, w: Int, h: Int): Rect {
        val pad = (max(r.width(), r.height()) * fraction).roundToInt()
        return Rect(max(0, r.left - pad), max(0, r.top - pad), min(w, r.right + pad), min(h, r.bottom + pad))
    }

    /** Largest rect of [aspect] (width/height) that fits in w×h, centred on (cx, cy) as far as the edges allow. */
    fun aspectRect(w: Int, h: Int, aspect: Float, cx: Float, cy: Float): Rect {
        val (rw, rh) = if (w.toFloat() / h > aspect) {
            min(w, (h * aspect).roundToInt()) to h
        } else {
            w to min(h, (w / aspect).roundToInt())
        }
        val left = (cx - rw / 2f).roundToInt().coerceIn(0, w - rw)
        val top = (cy - rh / 2f).roundToInt().coerceIn(0, h - rh)
        return Rect(left, top, left + rw, top + rh)
    }

    /**
     * Natural background: the photo cropped to [aspect] around the product in [cutout] (same size as [photo]),
     * or the photo itself without a shape or a product. Returns [photo] itself when nothing is cut.
     */
    fun natural(photo: Bitmap, cutout: Bitmap?, aspect: Float?): Bitmap {
        val r = naturalRect(photo, cutout, aspect) ?: return photo
        return crop(photo, r)
    }

    /** The rect [natural] crops to, or null when it keeps the whole photo. */
    fun naturalRect(photo: Bitmap, cutout: Bitmap?, aspect: Float?): Rect? {
        if (aspect == null) return null
        var cx = photo.width / 2f
        var cy = photo.height / 2f
        if (cutout != null) {
            subjectBounds(cutout)?.let { b ->
                cx = b.exactCenterX() * photo.width / cutout.width
                cy = b.exactCenterY() * photo.height / cutout.height
            }
        }
        val r = Framing.cropAround(photo.width, photo.height, aspect, cx, cy)
        return Rect(r[0], r[1], r[2], r[3])
    }

    /** Returns [bmp] itself when [r] covers it completely. */
    fun crop(bmp: Bitmap, r: Rect): Bitmap {
        val safe = Rect(r).apply { intersect(0, 0, bmp.width, bmp.height) }
        if (safe.width() <= 0 || safe.height() <= 0) return bmp
        if (safe.left == 0 && safe.top == 0 && safe.width() == bmp.width && safe.height() == bmp.height) return bmp
        return Bitmap.createBitmap(bmp, safe.left, safe.top, safe.width(), safe.height())
    }

    /**
     * Batch crop. With a cutout ([subjectAware]), ratio crops are centred on the product
     * rather than the photo, and "Fit product" trims away the empty area around it.
     */
    fun applyBatchCrop(content: Bitmap, crop: BatchCrop, subjectAware: Boolean): Bitmap {
        if (crop == BatchCrop.NONE) return content
        val bounds = if (subjectAware) subjectBounds(content) else null
        val rect = when (crop) {
            BatchCrop.TIGHT -> padded(bounds ?: return content, 0.02f, content.width, content.height)
            else -> aspectRect(
                content.width,
                content.height,
                crop.aspect ?: return content,
                bounds?.exactCenterX() ?: (content.width / 2f),
                bounds?.exactCenterY() ?: (content.height / 2f),
            )
        }
        return crop(content, rect)
    }
}
