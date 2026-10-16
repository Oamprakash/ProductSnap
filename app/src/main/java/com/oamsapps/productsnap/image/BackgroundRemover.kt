package com.oamsapps.productsnap.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.oamsapps.imaging.ImageIo
import com.oamsapps.imaging.ListingPhotoPipeline
import com.oamsapps.imaging.MaskVerdict
import com.oamsapps.imaging.MlKitSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device background removal, now backed by the tested core:imaging pipeline.
 *
 * The previous version used ML Kit's whole-image foregroundBitmap, which came back as horizontal stripes on
 * the test phone. The pipeline uses per-subject masks, segments at 1024 px, rejects corrupted masks, cleans
 * the edges and composites at the source's own size.
 */
class BackgroundRemover(context: Context) {

    private val segmenter = MlKitSegmenter(context.applicationContext)
    // The app has its own brightness/contrast controls, so auto-brighten stays off here for now.
    private val pipeline = ListingPhotoPipeline(segmenter, autoLight = false)
    private val prepareLock = Mutex()
    private var prepared = false

    /** Last verdict, so callers can tell "nothing found" from "corrupted" if they want to. */
    @Volatile var lastVerdict: MaskVerdict? = null
        private set

    /** Returns the subject on a transparent background at the source's size, or null if no usable cutout. */
    suspend fun removeBackground(source: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        prepareLock.withLock {
            if (!prepared) { segmenter.prepare(); prepared = true }
        }
        val full = ImageIo.normalize(source)
        val result = pipeline.process(full)
        if (full !== source) full.recycle()
        lastVerdict = result.verdict
        if (result.verdict != MaskVerdict.OK) {
            Log.w(TAG, "No usable cutout: ${result.verdict}")
            return@withContext null
        }
        Log.d(TAG, "Cutout via ${result.compositePath}: ${result.timings.totalMs} ms " +
            "(segment ${result.timings.segmentMs}, clean ${result.timings.cleanMs}, composite ${result.timings.compositeMs})")
        result.cutout
    }

    fun close() = segmenter.close()

    private companion object { const val TAG = "BackgroundRemover" }
}
