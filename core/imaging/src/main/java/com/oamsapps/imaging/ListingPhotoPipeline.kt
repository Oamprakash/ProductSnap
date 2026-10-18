package com.oamsapps.imaging

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.max

/** Sizes settled by testing: segment at 1024 px (same quality as 1600/2048, faster), export at 2048 px. */
object PipelineDefaults {
    const val SEGMENT_LONG_EDGE = 1024
    const val EXPORT_LONG_EDGE = 2048
}

class StepTimings(
    val segmentMs: Long,
    val cleanMs: Long,
    val compositeMs: Long,
    val lightMs: Long,
) {
    val totalMs get() = segmentMs + cleanMs + compositeMs + lightMs
}

/** Result for one photo. On anything but [MaskVerdict.OK], [cutout] is null and the UI offers retry or the brush. */
class ProcessedPhoto(
    val verdict: MaskVerdict,
    /** Full-size transparent cutout, edges cleaned and lighting corrected. Background is chosen at export. */
    val cutout: Bitmap?,
    /** The cleaned mask at segmentation size; the editor's brush edits this, then the cutout is rebuilt. */
    val mask: Mask,
    val light: LightFix,
    val compositePath: Compositor.Path?,
    val subjectBoxShares: List<Float>,
    val timings: StepTimings,
)

/**
 * One photo in, one listing-ready cutout out. Runs entirely on the device. [full] must already be normalized at
 * export size (see [ImageIo]); the caller owns threading (call it from a background dispatcher).
 */
class ListingPhotoPipeline(
    private val segmenter: Segmenter,
    private val segmentLongEdge: Int = PipelineDefaults.SEGMENT_LONG_EDGE,
    /** Honest auto-brighten on the cutout. Off when the caller has its own lighting controls. */
    private val autoLight: Boolean = true,
    /**
     * Measure the lighting fix even when not applying it, so a caller can apply it at render time (where the user
     * can turn it off) instead of baking it into the cutout. Cheap: it runs on the segmentation-size image.
     */
    private val measureLight: Boolean = autoLight,
) {

    /**
     * Stage 1, the slow part (ML Kit, about 1.7 s): segment and check the mask. Split from [finish] so a batch
     * can segment the next photo while the previous one is being finished and saved.
     */
    suspend fun segment(full: Bitmap): Segmented = segment(prepareInput(full))

    /**
     * The CPU work before ML Kit (the downscaled copy it runs on). Split out so a batch can do it for the next
     * photo while ML Kit is busy with the current one.
     */
    fun prepareInput(full: Bitmap): SegmentInput = SegmentInput(full, ImageIo.downscale(full, segmentLongEdge))

    suspend fun segment(input: SegmentInput): Segmented {
        val seg = segmenter.segment(input.small)
        return Segmented(input.full, input.small, seg, MaskCheck.check(seg.mask))
    }

    /** Stage 2: edge cleanup, compositing at full size, lighting. CPU only. */
    fun finish(s: Segmented): ProcessedPhoto {
        val seg = s.result
        if (s.verdict != MaskVerdict.OK) {
            return ProcessedPhoto(s.verdict, null, seg.mask, LightFix.NONE, null, seg.subjectBoxShares,
                StepTimings(seg.latencyMs, 0, 0, 0))
        }
        val full = s.full
        val small = s.small
        val t0 = SystemClock.elapsedRealtime()
        val r = MaskOps.edgeRadius(max(small.width, small.height), max(full.width, full.height))
        val clean = MaskOps.cleanEdges(seg.mask, erodePx = r, featherPx = r)
        val t1 = SystemClock.elapsedRealtime()
        val cut = Compositor.cutout(full, clean)
        val t2 = SystemClock.elapsedRealtime()
        val light = if (autoLight || measureLight) AutoLight.analyze(small, clean) else LightFix.NONE
        val lit = if (autoLight) AutoLight.apply(cut.bitmap, light).also { cut.bitmap.recycle() } else cut.bitmap
        val t3 = SystemClock.elapsedRealtime()
        return ProcessedPhoto(MaskVerdict.OK, lit, clean, light, cut.path, seg.subjectBoxShares,
            StepTimings(seg.latencyMs, t1 - t0, t2 - t1, t3 - t2))
    }

    suspend fun process(full: Bitmap): ProcessedPhoto = finish(segment(full))
}

/** Output of [ListingPhotoPipeline.prepareInput]: the normalized photo and the copy ML Kit runs on. */
class SegmentInput internal constructor(
    val full: Bitmap,
    internal val small: Bitmap,
)

/** Output of [ListingPhotoPipeline.segment]: the photo, its downscaled copy, the mask and its verdict. */
class Segmented internal constructor(
    val full: Bitmap,
    internal val small: Bitmap,
    val result: SegmentResult,
    val verdict: MaskVerdict,
)
