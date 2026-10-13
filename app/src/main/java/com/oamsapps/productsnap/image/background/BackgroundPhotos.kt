package com.oamsapps.productsnap.image.background

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.oamsapps.productsnap.image.BitmapLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object BackgroundPhotos {

    private const val KEEP = 6

    /**
     * Copies a picked photo into app storage so it outlives picker grants and can be
     * used by a batch running in the background. Keeps only the newest few.
     */
    suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }
        val file = File(dir, "bg_${System.currentTimeMillis()}")
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open that photo")
        input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        file
    }

    /** Ready-to-draw bitmap for [style]: full photo up to [maxEdge], or a small soft-blurred version. */
    suspend fun prepare(style: BackgroundStyle.Photo, maxEdge: Int): Bitmap? = withContext(Dispatchers.Default) {
        val file = File(style.path)
        if (!file.exists()) return@withContext null
        val full = BitmapLoader.load(file, maxEdge)
        if (!style.blur) return@withContext full
        Blur.soft(full).also { if (it !== full) full.recycle() }
    }
}

/**
 * Cheap, good-looking background blur: shrink to [workEdge], run three box-blur passes
 * (close to a Gaussian), and let drawing scale it back up with filtering.
 */
object Blur {

    fun soft(src: Bitmap, workEdge: Int = 256, radius: Int = 6): Bitmap {
        val scale = workEdge.toFloat() / max(src.width, src.height)
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        if (small !== src) small.recycle()
        boxBlur(px, w, h, radius, passes = 3)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** In-place separable box blur on opaque ARGB pixels. */
    fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int) {
        if (radius < 1) return
        val tmp = IntArray(px.size)
        repeat(passes) {
            blurLine(px, tmp, w, h, radius, horizontal = true)
            blurLine(tmp, px, w, h, radius, horizontal = false)
        }
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val window = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[idx(i)]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val add = src[idx(i + r + 1)]
                val sub = src[idx(i - r)]
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
