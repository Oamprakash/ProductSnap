package com.oamsapps.productsnap.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.oamsapps.imaging.ImageIo
import com.oamsapps.imaging.LightFix
import com.oamsapps.imaging.PhotoCheck
import com.oamsapps.imaging.ListingPhotoPipeline
import com.oamsapps.imaging.MaskVerdict
import com.oamsapps.imaging.MlKitSegmenter
import com.oamsapps.imaging.SegmentInput
import com.oamsapps.imaging.Segmented
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A cutout, or why there isn't one. */
class Removal(
    val cutout: Bitmap?,
    val verdict: MaskVerdict,
    /** Measured lighting fix for the item, applied at render time when the user has Auto light on. */
    val light: LightFix = LightFix.NONE,
    /** The cutout runs along the photo's edge; worth a check (see [PhotoCheck]). */
    val runsOffEdge: Boolean = false,
    /** The normalized photo, when [BackgroundRemover.finish] was asked to keep it; the caller frees it. */
    val photo: Bitmap? = null,
) {
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
    // Light is measured here but applied at render time, so the user can switch it off and the cutout keeps the
    // photo's true pixels (the Fix cutout brush restores from those).
    private val pipeline = ListingPhotoPipeline(segmenter, autoLight = false, measureLight = true)
    private val prepareLock = Mutex()
    private var prepared = false

    /** Waits for the ML Kit model (downloaded by Play services) and runs it once. Called by [segment] anyway. */
    suspend fun warmUp() = withContext(Dispatchers.Default) {
        prepareLock.withLock {
            if (!prepared) { segmenter.prepare(); prepared = true }
        }
    }

    /** CPU prep for ML Kit. The returned value owns a normalized copy of [source]; pass it to [segment]. */
    suspend fun prepareInput(source: Bitmap): SegmentInput = withContext(Dispatchers.Default) {
        pipeline.prepareInput(ImageIo.normalize(source))
    }

    /** ML Kit only. Pass the result to [finish], which frees the normalized copy. */
    suspend fun segment(input: SegmentInput): Segmented = withContext(Dispatchers.Default) {
        warmUp()
        pipeline.segment(input)
    }

    suspend fun segment(source: Bitmap): Segmented = segment(prepareInput(source))

    /**
     * Stage 2: cleanup and compositing. Frees the normalized copy, unless [keepPhoto] (the Natural background
     * needs the photo itself), in which case it's returned in [Removal.photo].
     */
    suspend fun finish(s: Segmented, keepPhoto: Boolean = false): Removal = withContext(Dispatchers.Default) {
        var kept = false
        try {
            val result = pipeline.finish(s)
            if (result.verdict == MaskVerdict.OK) {
                Log.d(TAG, "Cutout via ${result.compositePath}: ${result.timings.totalMs} ms " +
                    "(segment ${result.timings.segmentMs}, clean ${result.timings.cleanMs}, composite ${result.timings.compositeMs})")
            } else {
                Log.w(TAG, "No usable cutout: ${result.verdict}")
            }
            Removal(
                result.cutout,
                result.verdict,
                result.light,
                runsOffEdge = result.cutout != null && PhotoCheck.runsOffEdge(result.mask),
                photo = if (keepPhoto) s.full.also { kept = true } else null,
            )
        } finally {
            if (!kept) s.full.recycle()
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
