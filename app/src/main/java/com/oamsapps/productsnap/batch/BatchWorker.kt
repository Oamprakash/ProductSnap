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
import com.oamsapps.imaging.Segmented
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Processes the batch stored in [BatchStore] as a foreground job, two photos in flight: ML Kit segments the
 * next photo while the current one is finished and saved.
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
            coroutineScope {
                // Two-stage pipeline: while one photo is finished and saved, ML Kit already works on the next.
                // At most two full-size photos are in memory at once.
                val ready = Channel<Prepared>(capacity = 0)
                launch {
                    for (item in queue) {
                        // Free users get one finished photo; anything after it waits for Premium.
                        if (!start.premium && FreeAllowance.remaining() == 0) break
                        store.update { it.withStatus(item.id, StoredStatus.WORKING) }
                        publish()
                        ready.send(prepare(item, settings, remover))
                    }
                    ready.close()
                }
                for (p in ready) {
                    val (status, reason) = finish(p, settings, remover, backgroundPhoto)
                    val job = store.update { it.withStatus(p.item.id, status, reason) }
                    if (status == StoredStatus.SAVED && !start.premium) FreeAllowance.markUsed(applicationContext)
                    // Keep the copy when the user may want it back: to retry, or to fix it in the editor.
                    if (status == StoredStatus.SAVED) File(p.item.inputPath).delete()
                    showProgress(job)
                    publish()
                }
            }
        } catch (e: CancellationException) {
            // Stopped by the user or the system: photos in flight go back to the queue for the next run.
            withContext(NonCancellable) {
                store.update { job ->
                    job.copy(items = job.items.map { if (it.status == StoredStatus.WORKING) it.copy(status = StoredStatus.PENDING) else it })
                }
            }
            throw e
        } finally {
            remover.close()
            backgroundPhoto?.recycle()
        }

        val done = store.read()
        BatchNotifications.showFinished(applicationContext, done.savedCount, done.items.size)
        return Result.success()
    }

    /** A photo after stage 1: loaded and, when needed, segmented. Or the reason it couldn't be. */
    private class Prepared(
        val item: StoredItem,
        val original: Bitmap?,
        val segmented: Segmented?,
        val failure: Pair<StoredStatus, String?>? = null,
    )

    private suspend fun prepare(item: StoredItem, settings: ExportSettings, remover: BackgroundRemover): Prepared =
        try {
            val original = BitmapLoader.load(File(item.inputPath), maxEdge = settings.maxEdge)
            val segmented = if (settings.removeBackground) remover.segment(original) else null
            Prepared(item, original, segmented)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Prepared(item, null, null, StoredStatus.FAILED to "Not enough memory for this photo")
        } catch (e: Exception) {
            Log.w(TAG, "Photo ${item.id} failed to load or segment", e)
            Prepared(item, null, null, StoredStatus.FAILED to (e.message ?: "Unknown error").take(120))
        }

    /** Stage 2: cutout, crop, shadow, render, save. Frees everything it was given. */
    private suspend fun finish(
        p: Prepared,
        settings: ExportSettings,
        remover: BackgroundRemover,
        backgroundPhoto: Bitmap?,
    ): Pair<StoredStatus, String?> {
        p.failure?.let { return it }
        val original = p.original ?: return StoredStatus.FAILED to "Photo couldn't be read"
        var cutout: Bitmap? = null
        var cropped: Bitmap? = null
        var shadowed: Bitmap? = null
        var output: Bitmap? = null
        try {
            val uncropped = if (p.segmented != null) {
                val removal = remover.finish(p.segmented)
                when {
                    removal.cutout != null -> removal.cutout.also { cutout = it }
                    removal.needsLook -> return StoredStatus.NEEDS_LOOK to null
                    else -> return StoredStatus.NO_SUBJECT to null
                }
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
            return StoredStatus.SAVED to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            return StoredStatus.FAILED to "Not enough memory for this photo"
        } catch (e: Exception) {
            Log.w(TAG, "Photo ${p.item.id} failed", e)
            return StoredStatus.FAILED to (e.message ?: "Unknown error").take(120)
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
