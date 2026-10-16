package com.oamsapps.productsnap.batch

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.oamsapps.productsnap.billing.FreeAllowance
import com.oamsapps.productsnap.image.BackgroundRemover
import com.oamsapps.productsnap.image.BitmapLoader
import com.oamsapps.productsnap.image.Cropper
import com.oamsapps.productsnap.image.ExportSettings
import com.oamsapps.productsnap.image.ImageRenderer
import com.oamsapps.productsnap.image.ImageSaver
import com.oamsapps.productsnap.image.ShadowMaker
import com.oamsapps.productsnap.image.background.BackgroundPhotos
import com.oamsapps.productsnap.image.background.BackgroundStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Processes the batch stored in [BatchStore], one photo at a time, as a foreground job.
 *
 * Survives the app going to the background. If Android kills the process anyway,
 * WorkManager restarts this worker and it carries on from the first photo not yet
 * finished, because every status change is written to disk as it happens.
 */
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val store = BatchStore.get(context)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val job = store.read()
        return BatchNotifications.progress(applicationContext, id, job.finishedCount, job.items.size)
    }

    override suspend fun doWork(): Result {
        val start = store.read()
        // WORKING here means a previous run was interrupted mid-photo; redo it.
        val queue = start.items.filter { it.status == StoredStatus.PENDING || it.status == StoredStatus.WORKING }
        if (queue.isEmpty()) return Result.success()

        val settings = start.settings.toExport(start.premium)
        showProgress(start)

        // Prepared once and shared by every photo in the run.
        val backgroundPhoto = (settings.background as? BackgroundStyle.Photo)?.let {
            runCatching { BackgroundPhotos.prepare(it, settings.maxEdge) }.getOrNull()
        }
        val remover = BackgroundRemover(applicationContext)
        try {
            for (item in queue) {
                // Free users get one finished photo; anything after it waits for Premium.
                if (!start.premium && FreeAllowance.remaining() == 0) break
                store.update { it.withStatus(item.id, StoredStatus.WORKING) }
                publish()

                val (status, reason) = try {
                    process(File(item.inputPath), settings, remover, backgroundPhoto) to null
                } catch (e: CancellationException) {
                    // Stopped by the user or the system: leave this photo for the next run.
                    withContext(NonCancellable) { store.update { it.withStatus(item.id, StoredStatus.PENDING) } }
                    throw e
                } catch (e: OutOfMemoryError) {
                    StoredStatus.FAILED to "Not enough memory for this photo"
                } catch (e: Exception) {
                    Log.w(TAG, "Photo ${item.id} failed", e)
                    StoredStatus.FAILED to (e.message ?: "Unknown error").take(120)
                }

                val job = store.update { it.withStatus(item.id, status, reason) }
                if (status == StoredStatus.SAVED && !start.premium) FreeAllowance.markUsed(applicationContext)
                // Keep the copy only if the user might retry it.
                if (status != StoredStatus.FAILED) File(item.inputPath).delete()
                showProgress(job)
                publish()
            }
        } finally {
            remover.close()
            backgroundPhoto?.recycle()
        }

        val done = store.read()
        BatchNotifications.showFinished(applicationContext, done.savedCount, done.items.size)
        return Result.success()
    }

    /** Only one full-size bitmap chain is alive at a time; all freed before the next photo. */
    private suspend fun process(
        input: File,
        settings: ExportSettings,
        remover: BackgroundRemover,
        backgroundPhoto: Bitmap?,
    ): StoredStatus {
        val original = BitmapLoader.load(input, maxEdge = settings.maxEdge)
        var cutout: Bitmap? = null
        var cropped: Bitmap? = null
        var shadowed: Bitmap? = null
        var output: Bitmap? = null
        try {
            val uncropped = if (settings.removeBackground) {
                val cut = remover.removeBackground(original) ?: return StoredStatus.NO_SUBJECT
                cutout = cut
                cut
            } else {
                original
            }
            // Crop after background removal so the crop can follow the product.
            val croppedContent = Cropper.applyBatchCrop(uncropped, settings.crop, subjectAware = settings.removeBackground)
            if (croppedContent !== uncropped) cropped = croppedContent

            // Shadow after cropping, so a tight crop still leaves room for it.
            val content = if (settings.removeBackground && settings.shadow) ShadowMaker.apply(croppedContent) else croppedContent
            if (content !== croppedContent) shadowed = content

            val rendered = ImageRenderer.render(settings.renderSpec(content, backgroundPhoto))
            output = rendered
            ImageSaver.saveToGallery(applicationContext, rendered, transparent = settings.transparent)
            return StoredStatus.SAVED
        } finally {
            original.recycle()
            cutout?.recycle()
            cropped?.recycle()
            shadowed?.recycle()
            output?.recycle()
        }
    }

    private suspend fun showProgress(job: BatchJob) {
        runCatching {
            setForeground(BatchNotifications.progress(applicationContext, id, job.finishedCount, job.items.size))
        }.onFailure { Log.w(TAG, "Couldn't start foreground; continuing as background work", it) }
    }

    /** Nudges observers (the UI) to re-read the store. */
    private suspend fun publish() {
        setProgress(workDataOf(KEY_TICK to System.nanoTime()))
    }

    companion object {
        const val UNIQUE_NAME = "product_batch"
        private const val KEY_TICK = "tick"
        private const val TAG = "BatchWorker"
    }
}
