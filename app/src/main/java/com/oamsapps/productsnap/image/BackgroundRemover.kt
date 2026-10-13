package com.oamsapps.productsnap.image

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device background removal via ML Kit Subject Segmentation.
 * Free to run (no per-image cloud cost), works offline once the model is downloaded.
 */
class BackgroundRemover {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
    )

    /** Returns the subject on a transparent background, or null if nothing was found. */
    suspend fun removeBackground(source: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val result = segmenter.process(InputImage.fromBitmap(source, 0)).await()
        result.foregroundBitmap
    }

    fun close() = segmenter.close()
}
