package com.oamsapps.productsnap.batch

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
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
import com.oamsapps.imaging.LightFix
import com.oamsapps.imaging.PhotoCheck
import com.oamsapps.imaging.PhotoWarning
import com.oamsapps.imaging.SegmentInput
import com.oamsapps.imaging.Segmented
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Processes the batch stored in [BatchStore] as a foreground job, in four overlapping stages:
 * 1. load and prepare, 2. ML Kit, 3. cutout, crop, shadow, render, 4. encode and save to the gallery.
 * ML Kit is the slowest step, so the others are arranged to keep it busy.
 * Each stage hands over through a rendezvous channel, so at most one photo waits per stage.
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
        val waiting = start.items.filter { it.status == StoredStatus.PENDING || it.status == StoredStatus.WORKING }
        // Free users get one finished photo; anything after it stays queued for Premium.
        val queue = if (start.premium) waiting else waiting.take(FreeAllowance.remaining())
        if (queue.isEmpty()) return Result.success()

        val clock = RunClock()
        val settings = start.settings.toExport(start.premium)
        showProgress(start)

        // Prepared once and shared by every photo in the run.
        val backgroundPhoto = (settings.background as? BackgroundStyle.Photo)?.let {
            runCatching { BackgroundPhotos.prepare(it, settings.maxEdge) }.getOrNull()
        }
        val remover = BackgroundRemover.shared(applicationContext)
        try {
            if (settings.removeBackground) clock.time(RunClock.MODEL) { remover.warmUp() }
            coroutineScope {
                val loaded = Channel<Loaded>(capacity = 0)
                val segmented = Channel<Prepared>(capacity = 0)
                val rendered = Channel<Rendered>(capacity = 0)

                // Stage 1: load the photo and get its ML Kit input ready.
                launch {
                    for (item in queue) {
                        store.update { it.withStatus(item.id, StoredStatus.WORKING) }
                        publish()
                        loaded.send(load(item, settings, remover, clock))
                    }
                    loaded.close()
                }

                // Stage 2: ML Kit, kept busy back to back; it is the slowest step.
                launch {
                    for (l in loaded) segmented.send(segment(l, remover, clock))
                    segmented.close()
                }

                // Stage 3: cutout, crop, shadow, render. Photos that stop here are recorded straight away.
                launch {
                    for (p in segmented) {
                        when (val r = finish(p, settings, remover, backgroundPhoto, clock)) {
                            is Rendered -> rendered.send(r)
                            is Outcome -> record(p.item, r.status, r.reason, start.premium)
                        }
                    }
                    rendered.close()
                }

                // Stage 4: encode and save.
                for (r in rendered) {
                    val outcome = save(r, settings, clock)
                    record(r.item, outcome.status, outcome.reason, start.premium)
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
            backgroundPhoto?.recycle()
        }

        val summary = clock.summary(queue.size)
        Log.i(TAG, summary)
        val done = store.update { it.copy(timing = summary) }
        publish()
        BatchNotifications.showFinished(applicationContext, done.savedCount, done.items.size)
        return Result.success()
    }

    private suspend fun record(item: StoredItem, status: StoredStatus, reason: String?, premium: Boolean) {
        val job = store.update { it.withStatus(item.id, status, reason) }
        if (status == StoredStatus.SAVED && !premium) FreeAllowance.markUsed(applicationContext)
        // Keep the copy when the user may want it back: to retry, to fix it in the editor, or to check a warning.
        val warned = job.items.firstOrNull { it.id == item.id }?.warnings?.isNotEmpty() == true
        if (status == StoredStatus.SAVED && !warned) File(item.inputPath).delete()
        showProgress(job)
        publish()
    }

    /** A photo after stage 1: loaded, with its ML Kit input when needed. Or the reason it couldn't be. */
    private class Loaded(
        val item: StoredItem,
        val original: Bitmap?,
        val input: SegmentInput?,
        val failure: Outcome? = null,
    )

    /** A photo after stage 2: loaded and, when needed, segmented. Or the reason it couldn't be. */
    private class Prepared(
        val item: StoredItem,
        val original: Bitmap?,
        val segmented: Segmented?,
        val failure: Outcome? = null,
    )

    private sealed interface StageResult

    /** Final status for a photo. */
    private class Outcome(val status: StoredStatus, val reason: String? = null) : StageResult

    /** A photo after stage 3: the finished image, owned by stage 4. */
    private class Rendered(val item: StoredItem, val bitmap: Bitmap) : StageResult

    private suspend fun load(item: StoredItem, settings: ExportSettings, remover: BackgroundRemover, clock: RunClock): Loaded {
        var original: Bitmap? = null
        return try {
            clock.time(RunClock.LOAD) {
                original = BitmapLoader.load(File(item.inputPath), maxEdge = settings.maxEdge)
                if (PhotoCheck.isTooDark(original!!)) store.update { it.withWarning(item.id, PhotoWarning.TOO_DARK.name) }
                if (settings.removeBackground) {
                    // The input holds its own normalized copy, so the decoded photo can go now.
                    val input = remover.prepareInput(original!!)
                    original!!.recycle()
                    original = null
                    Loaded(item, null, input)
                } else {
                    Loaded(item, original, null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            original?.recycle()
            Loaded(item, null, null, failure(item, e))
        }
    }

    private suspend fun segment(l: Loaded, remover: BackgroundRemover, clock: RunClock): Prepared {
        if (l.failure != null) return Prepared(l.item, null, null, l.failure)
        val input = l.input ?: return Prepared(l.item, l.original, null)
        return try {
            Prepared(l.item, l.original, clock.time(RunClock.SEGMENT) { remover.segment(input) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            input.full.recycle()
            Prepared(l.item, null, null, failure(l.item, e))
        }
    }

    private fun failure(item: StoredItem, e: Throwable): Outcome = when (e) {
        is OutOfMemoryError -> Outcome(StoredStatus.FAILED, "Not enough memory for this photo")
        is Exception -> {
            Log.w(TAG, "Photo ${item.id} failed to load or segment", e)
            Outcome(StoredStatus.FAILED, (e.message ?: "Unknown error").take(120))
        }
        else -> throw e
    }

    /** Stage 3. Frees everything it was given; the rendered image goes to stage 4. */
    private suspend fun finish(
        p: Prepared,
        settings: ExportSettings,
        remover: BackgroundRemover,
        backgroundPhoto: Bitmap?,
        clock: RunClock,
    ): StageResult {
        p.failure?.let { return it }
        val original = p.original
        if (p.segmented == null && original == null) return Outcome(StoredStatus.FAILED, "Photo couldn't be read")
        var cutout: Bitmap? = null
        var cropped: Bitmap? = null
        var shadowed: Bitmap? = null
        var light = LightFix.NONE
        try {
            val uncropped = if (p.segmented != null) {
                val removal = clock.time(RunClock.CUTOUT) { remover.finish(p.segmented) }
                when {
                    removal.cutout != null -> removal.cutout.also {
                        cutout = it
                        light = removal.light
                        if (removal.runsOffEdge) store.update { j -> j.withWarning(p.item.id, PhotoWarning.RUNS_OFF_EDGE.name) }
                    }
                    removal.needsLook -> return Outcome(StoredStatus.NEEDS_LOOK)
                    else -> return Outcome(StoredStatus.NO_SUBJECT)
                }
            } else {
                original!!
            }
            val content = clock.time(RunClock.CROP_SHADOW) {
                // Crop after background removal so the crop can follow the product.
                val croppedContent = Cropper.applyBatchCrop(uncropped, settings.crop, subjectAware = settings.removeBackground)
                if (croppedContent !== uncropped) cropped = croppedContent
                // Shadow after cropping, so a tight crop still leaves room for it.
                val withShadow = if (settings.removeBackground && settings.shadow) ShadowMaker.apply(croppedContent) else croppedContent
                if (withShadow !== croppedContent) shadowed = withShadow
                withShadow
            }
            val spec = settings.renderSpec(content, backgroundPhoto).let { spec ->
                if (settings.autoLight && light != LightFix.NONE) spec.copy(adjustments = spec.adjustments.copy(light = light)) else spec
            }
            val out = clock.time(RunClock.RENDER) { ImageRenderer.render(spec) }
            return Rendered(p.item, out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            return Outcome(StoredStatus.FAILED, "Not enough memory for this photo")
        } catch (e: Exception) {
            Log.w(TAG, "Photo ${p.item.id} failed", e)
            return Outcome(StoredStatus.FAILED, (e.message ?: "Unknown error").take(120))
        } finally {
            original?.recycle()
            cutout?.recycle()
            cropped?.recycle()
            shadowed?.recycle()
        }
    }

    /** Stage 4. Frees the rendered image. */
    private suspend fun save(r: Rendered, settings: ExportSettings, clock: RunClock): Outcome =
        try {
            clock.time(RunClock.SAVE) { ImageSaver.saveToGallery(applicationContext, r.bitmap, transparent = settings.transparent) }
            Outcome(StoredStatus.SAVED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Photo ${r.item.id} couldn't be saved", e)
            Outcome(StoredStatus.FAILED, (e.message ?: "Couldn't save").take(120))
        } finally {
            r.bitmap.recycle()
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

/** Adds up how long each step takes across a run (stages overlap, so steps run in parallel). */
private class RunClock {
    private val started = SystemClock.elapsedRealtime()
    private val totals = ConcurrentHashMap<String, AtomicLong>()
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    inline fun <T> time(step: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            add(step, SystemClock.elapsedRealtime() - t0)
        }
    }

    fun add(step: String, ms: Long) {
        totals.getOrPut(step) { AtomicLong() }.addAndGet(ms)
        counts.getOrPut(step) { AtomicLong() }.incrementAndGet()
    }

    /** e.g. "10 photos in 31.2 s (3.1 s each, model start 1.4 s). Per photo: load 0.2 · ML Kit 1.7 · …" */
    fun summary(photos: Int): String {
        val wall = (SystemClock.elapsedRealtime() - started) / 1000.0
        val model = totals[MODEL]?.get()?.let { ", model start ${s(it / 1000.0)} s" } ?: ""
        val steps = listOf(LOAD, SEGMENT, CUTOUT, CROP_SHADOW, RENDER, SAVE).mapNotNull { step ->
            val n = counts[step]?.get() ?: return@mapNotNull null
            "$step ${s(totals.getValue(step).get() / 1000.0 / n)}"
        }
        return "$photos photos in ${s(wall)} s (${s(wall / photos)} s each$model). " +
            "Per photo: ${steps.joinToString(" · ")} s"
    }

    private fun s(v: Double) = String.format(Locale.US, "%.1f", v)

    companion object {
        const val MODEL = "model"
        const val LOAD = "load+prep"
        const val SEGMENT = "ML Kit"
        const val CUTOUT = "cutout"
        const val CROP_SHADOW = "crop+shadow"
        const val RENDER = "render"
        const val SAVE = "save"
    }
}
