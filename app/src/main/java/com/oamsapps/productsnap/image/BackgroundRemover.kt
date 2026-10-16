package com.oamsapps.productsnap.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.oamsapps.imaging.ImageIo
import com.oamsapps.imaging.ListingPhotoPipeline
import com.oamsapps.imaging.MaskVerdict
import com.oamsapps.imaging.MlKitSegmenter
import com.oamsapps.imaging.Segmented
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A cutout, or why there isn't one. */
class Removal(val cutout: Bitmap?, val verdict: MaskVerdict) {
    /** Something was found but the mask is unusable (garbled, or everything): worth a person's look. */
    val needsLook get() = verdict == MaskVerdict.CORRUPTED || verdict == MaskVerdict.FULL
}

/**
 * On-device background removal, backed by the tested core:imaging pipeline: per-subject masks segmented at
 * 1024 px, a check that rejects empty/full/corrupted masks, edge cleanup, and a verified composite at the
 * source's own size. (The old version used ML Kit's foregroundBitmap, which came back striped on the test phone.)
 *
 * Batch uses [segment] and [finish] separately so ML Kit can work on the next photo while the previous one is
 * finished; the editor uses [remove]. Get it with [shared].
 */
class BackgroundRemover private constructor(context: Context) {

    private val segmenter = MlKitSegmenter(context.applicationContext)
    // The app has its own brightness/contrast controls, so auto-brighten stays off here for now.
    private val pipeline = ListingPhotoPipeline(segmenter, autoLight = false)
    private val prepareLock = Mutex()
    private var prepared = false

    /** Waits for the ML Kit model (downloaded by Play services) and runs it once. Called by [segment] anyway. */
    suspend fun warmUp() = withContext(Dispatchers.Default) {
        prepareLock.withLock {
            if (!prepared) { segmenter.prepare(); prepared = true }
        }
    }

    /** Stage 1 (ML Kit). The returned value owns a normalized copy of [source]; pass it to [finish]. */
    suspend fun segment(source: Bitmap): Segmented = withContext(Dispatchers.Default) {
        warmUp()
        pipeline.segment(ImageIo.normalize(source))
    }

    /** Stage 2: cleanup and compositing. Frees the normalized copy. */
    suspend fun finish(s: Segmented): Removal = withContext(Dispatchers.Default) {
        try {
            val result = pipeline.finish(s)
            if (result.verdict == MaskVerdict.OK) {
                Log.d(TAG, "Cutout via ${result.compositePath}: ${result.timings.totalMs} ms " +
                    "(segment ${result.timings.segmentMs}, clean ${result.timings.cleanMs}, composite ${result.timings.compositeMs})")
            } else {
                Log.w(TAG, "No usable cutout: ${result.verdict}")
            }
            Removal(result.cutout, result.verdict)
        } finally {
            s.full.recycle()
        }
    }

    suspend fun remove(source: Bitmap): Removal = finish(segment(source))

    /** The subject on a transparent background at the source's size, or null if there's no usable cutout. */
    suspend fun removeBackground(source: Bitmap): Bitmap? = remove(source).cutout

    companion object {
        private const val TAG = "BackgroundRemover"
        @Volatile private var instance: BackgroundRemover? = null

        /**
         * One remover for the whole process, shared by the editor and batch and never closed, so the ML Kit
         * model is loaded and warmed up once. A fresh one per batch run cost about 4 s before the first photo.
         */
        fun shared(context: Context): BackgroundRemover =
            instance ?: synchronized(this) { instance ?: BackgroundRemover(context.applicationContext).also { instance = it } }
    }
}
