package com.oamsapps.productsnap.batch

import android.util.Log
import com.oamsapps.productsnap.image.BackgroundRemover
import com.oamsapps.productsnap.image.LightStrength
import com.oamsapps.productsnap.image.Marketplace
import com.oamsapps.imaging.PhotoWarning
import android.app.Application
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.oamsapps.productsnap.billing.PremiumManager
import com.oamsapps.productsnap.editor.EditorState
import com.oamsapps.productsnap.billing.FreeAllowance
import com.oamsapps.productsnap.image.Adjustments
import com.oamsapps.productsnap.image.BatchCrop
import com.oamsapps.productsnap.image.background.BackgroundPhotos
import com.oamsapps.productsnap.image.background.BackgroundPresets
import com.oamsapps.productsnap.image.background.BackgroundStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface ItemStatus {
    data object Pending : ItemStatus
    data object Working : ItemStatus
    data object Saved : ItemStatus
    data object NoSubject : ItemStatus
    data object NeedsLook : ItemStatus
    data class Failed(val reason: String) : ItemStatus
}

data class BatchItem(
    val id: Int,
    val thumbnail: Bitmap? = null,
    val status: ItemStatus = ItemStatus.Pending,
    /** The photo's private copy, when it can be opened in the editor (not saved, or saved with a warning). */
    val openable: File? = null,
    val warnings: List<PhotoWarning> = emptyList(),
)

data class BatchState(
    val items: List<BatchItem> = emptyList(),
    val removeBackground: Boolean = true,
    val background: BackgroundStyle = BackgroundPresets.WHITE,
    val backgroundThumb: Bitmap? = null,
    val isPreparingBackground: Boolean = false,
    val squareCanvas: Boolean = true,
    val adjustments: Adjustments = Adjustments(),
    val crop: BatchCrop = BatchCrop.NONE,
    val shadow: Boolean = true,
    val lightStrength: LightStrength = LightStrength.DEFAULT,
    val marketplace: Marketplace? = null,
    val isRunning: Boolean = false,
    val isImporting: Boolean = false,
    val message: String? = null,
    /** The message is about the free batch limit, so offer an upgrade with it. */
    val messageOffersUpgrade: Boolean = false,
    /** Per-step timing of the last run, for tuning batch speed. */
    val timing: String? = null,
    /** Gallery entries of the saved photos, in batch order, for sharing. */
    val savedUris: List<Uri> = emptyList(),
) {
    val savedCount get() = items.count { it.status == ItemStatus.Saved }
    val finishedCount get() = items.count { it.status != ItemStatus.Pending && it.status != ItemStatus.Working }
    /** Photos a "Save" tap would process: never tried, or failed last time. */
    val queuedCount get() = items.count { it.status == ItemStatus.Pending || it.status is ItemStatus.Failed }
    val failedCount get() = items.count { it.status is ItemStatus.Failed }
    val progress get() = if (items.isEmpty()) 0f else finishedCount.toFloat() / items.size
    val canEdit get() = !isRunning && !isImporting

    /** The batch's look, for opening one of its photos in the editor. */
    val settings get() = BatchSettings(
        removeBackground = removeBackground,
        background = background,
        squareCanvas = squareCanvas,
        adjustments = adjustments,
        crop = crop,
        shadow = shadow,
        lightStrength = lightStrength,
        marketplace = marketplace,
    )
}

/**
 * Thin UI layer over [BatchStore] (the batch itself) and WorkManager (the run).
 * Nothing important lives only in memory, so the screen rebuilds itself after process death.
 */
class BatchViewModel(app: Application) : AndroidViewModel(app) {

    private val store = BatchStore.get(app)
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state.asStateFlow()

    val isPremium = PremiumManager.isPremium
    val freeAvailable = FreeAllowance.available

    private val thumbnails = ConcurrentHashMap<Int, Bitmap>()
    private val loadingThumbnails = ConcurrentHashMap.newKeySet<Int>()
    private var wasRunning: Boolean? = null

    init {
        // Load and warm up the cutout model now, so tapping Save starts on the first photo straight away.
        viewModelScope.launch {
            runCatching { BackgroundRemover.shared(app).warmUp() }
                .onFailure { Log.w("BatchViewModel", "Model warm-up failed; the batch will retry it", it) }
        }
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(BatchWorker.UNIQUE_NAME).collect { infos ->
                val running = infos.any { !it.state.isFinished }
                refresh(running)
                if (wasRunning == true && !running) {
                    val s = _state.value
                    _state.update { it.copy(message = "Saved ${s.savedCount} of ${s.items.size} photos to Pictures/ProductSnap") }
                }
                wasRunning = running
            }
        }
    }

    /** Carry the look the user set up in the single-photo editor into a new, empty batch. */
    fun configureFrom(editor: EditorState) = viewModelScope.launch {
        if (!_state.value.canEdit) return@launch
        store.update { job ->
            if (job.items.isNotEmpty()) job
            else job.copy(
                settings = job.settings.copy(
                    background = editor.background,
                    squareCanvas = editor.squareCanvas,
                    adjustments = editor.adjustments,
                    shadow = editor.shadow,
                    lightStrength = editor.lightStrength,
                    marketplace = editor.marketplace,
                )
            )
        }
        refresh()
    }

    /**
     * Copies each photo into app storage first. Picker and share grants can expire when the
     * process dies, and the worker must be able to read every photo after a restart.
     */
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty() || !_state.value.canEdit) return
        _state.update { it.copy(isImporting = true, message = null) }

        viewModelScope.launch {
            val limit = currentLimit()
            val current = store.read()
            val existing = current.items.map { it.sourceUri }.toSet()
            val fresh = uris.distinct().filterNot { it.toString() in existing }
            val room = (limit - current.items.size).coerceAtLeast(0)
            val accepted = fresh.take(room)

            var nextId = current.nextId
            var unreadable = 0
            val imported = mutableListOf<StoredItem>()
            for (uri in accepted) {
                val id = nextId++
                val file = store.inputFile(id)
                if (copyInto(uri, file)) imported += StoredItem(id, file.absolutePath, uri.toString())
                else unreadable++
            }
            store.update { it.copy(items = it.items + imported) }

            val message = when {
                fresh.size > accepted.size -> limitMessage(limit)
                unreadable == 1 -> "1 photo couldn't be opened and wasn't added."
                unreadable > 1 -> "$unreadable photos couldn't be opened and weren't added."
                else -> null
            }
            val hitFreeLimit = fresh.size > accepted.size && !isPremium.value
            _state.update { it.copy(isImporting = false, message = message, messageOffersUpgrade = hitFreeLimit) }
            refresh()
        }
    }

    fun removeItem(id: Int) {
        if (!_state.value.canEdit) return
        viewModelScope.launch {
            store.update { job -> job.copy(items = job.items.filterNot { it.id == id }) }
            store.pruneInputs()
            refresh()
        }
    }

    fun clear() {
        if (!_state.value.canEdit) return
        viewModelScope.launch {
            store.update { it.copy(items = emptyList(), timing = null) }
            store.pruneInputs()
            _state.update { it.copy(message = null) }
            refresh()
        }
    }

    fun setRemoveBackground(on: Boolean) = editSettings { it.copy(removeBackground = on) }
    fun setSquare(on: Boolean) = editSettings { it.copy(squareCanvas = on) }
    /** Anything beyond a plain colour needs the cutout, so switch background removal on with it. */
    fun setBackground(style: BackgroundStyle) = editSettings {
        it.copy(background = style, removeBackground = it.removeBackground || !style.isPlainColor)
    }

    fun setBackgroundPhoto(uri: Uri) {
        if (!_state.value.canEdit) return
        viewModelScope.launch {
            _state.update { it.copy(isPreparingBackground = true, message = null) }
            runCatching { BackgroundPhotos.import(getApplication(), uri) }
                .onSuccess { file -> setBackground(BackgroundStyle.Photo(file.absolutePath, blur = true)) }
                .onFailure { _state.update { it.copy(message = "Couldn't use that photo as a background. Try another one.") } }
            _state.update { it.copy(isPreparingBackground = false) }
        }
    }

    fun setBackgroundBlur(blur: Boolean) {
        val current = _state.value.background as? BackgroundStyle.Photo ?: return
        setBackground(current.copy(blur = blur))
    }
    fun clearAdjustments() = editSettings { it.copy(adjustments = Adjustments()) }
    fun setCrop(crop: BatchCrop) = editSettings { it.copy(crop = crop) }
    fun setShadow(on: Boolean) = editSettings { it.copy(shadow = on) }
    fun setLightStrength(strength: LightStrength) = editSettings { it.copy(lightStrength = strength) }

    /** Same rule as the editor: Vinted keeps the real background, the others a clean one. */
    fun setMarketplace(m: Marketplace?) = editSettings {
        val bg = when {
            m?.natural == true -> BackgroundStyle.Natural
            m != null && (it.background == BackgroundStyle.Natural || it.background.isTransparent) -> BackgroundPresets.WHITE
            else -> it.background
        }
        it.copy(marketplace = m, background = bg, removeBackground = it.removeBackground || !bg.isPlainColor)
    }
    fun clearMessage() = _state.update { it.copy(message = null, messageOffersUpgrade = false) }

    private fun editSettings(change: (BatchSettings) -> BatchSettings) {
        if (!_state.value.canEdit) return
        viewModelScope.launch {
            store.update { it.copy(settings = change(it.settings)) }
            refresh()
        }
    }

    fun start() {
        if (!_state.value.canEdit) return
        if (!isPremium.value && FreeAllowance.remaining() == 0) {
            _state.update { it.copy(message = FREE_USED_MESSAGE, messageOffersUpgrade = true) }
            return
        }
        _state.update { it.copy(isRunning = true, message = null) }
        viewModelScope.launch {
            val job = store.update { j ->
                j.copy(
                    premium = isPremium.value,
                    timing = null,
                    items = j.items.map {
                        if (it.status == StoredStatus.FAILED) it.copy(status = StoredStatus.PENDING, reason = null) else it
                    },
                )
            }
            if (job.items.none { it.status == StoredStatus.PENDING }) {
                refresh(running = false)
                return@launch
            }
            val request = OneTimeWorkRequestBuilder<BatchWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            workManager.enqueueUniqueWork(BatchWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    fun cancel() {
        workManager.cancelUniqueWork(BatchWorker.UNIQUE_NAME)
    }

    private suspend fun refresh(running: Boolean = _state.value.isRunning) {
        val job = store.read()
        val ids = job.items.map { it.id }.toSet()
        thumbnails.keys.retainAll(ids)

        _state.update { s ->
            s.copy(
                items = job.items.map { item ->
                    val status = item.toItemStatus(running)
                    val warnings = item.warnings.mapNotNull { w -> runCatching { PhotoWarning.valueOf(w) }.getOrNull() }
                    val canOpen = status == ItemStatus.NeedsLook || status == ItemStatus.NoSubject || status is ItemStatus.Failed ||
                        (status == ItemStatus.Saved && warnings.isNotEmpty())
                    BatchItem(item.id, thumbnails[item.id], status, File(item.inputPath).takeIf { canOpen && it.exists() }, warnings)
                },
                removeBackground = job.settings.removeBackground,
                background = job.settings.background,
                squareCanvas = job.settings.squareCanvas,
                adjustments = job.settings.adjustments,
                crop = job.settings.crop,
                shadow = job.settings.shadow,
                lightStrength = job.settings.lightStrength,
                marketplace = job.settings.marketplace,
                isRunning = running,
                timing = job.timing,
                savedUris = job.items.filter { it.status == StoredStatus.SAVED }.mapNotNull { it.savedUri?.let(Uri::parse) },
            )
        }
        job.items.filter { !thumbnails.containsKey(it.id) }.forEach(::loadThumbnail)
        loadBackgroundThumb(job.settings.background)
    }

    private fun loadThumbnail(item: StoredItem) {
        if (!loadingThumbnails.add(item.id)) return
        viewModelScope.launch {
            val thumb = withContext(Dispatchers.IO) {
                runCatching { ThumbnailUtils.createImageThumbnail(File(item.inputPath), Size(192, 192), null) }.getOrNull()
            }
            loadingThumbnails.remove(item.id)
            if (thumb != null) {
                thumbnails[item.id] = thumb
                _state.update { s ->
                    s.copy(items = s.items.map { if (it.id == item.id) it.copy(thumbnail = thumb) else it })
                }
            }
        }
    }

    private var backgroundThumbPath: String? = null

    private fun loadBackgroundThumb(style: BackgroundStyle) {
        val path = (style as? BackgroundStyle.Photo)?.path
        if (path == backgroundThumbPath) return
        backgroundThumbPath = path
        if (path == null) { _state.update { it.copy(backgroundThumb = null) }; return }
        viewModelScope.launch {
            val thumb = withContext(Dispatchers.IO) {
                runCatching { ThumbnailUtils.createImageThumbnail(File(path), Size(192, 192), null) }.getOrNull()
            }
            if (backgroundThumbPath == path) _state.update { it.copy(backgroundThumb = thumb) }
        }
    }

    private suspend fun copyInto(uri: Uri, file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: error("No stream")
            input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
            true
        }.getOrElse {
            file.delete()
            false
        }
    }

    private fun currentLimit() =
        if (isPremium.value) PremiumManager.PREMIUM_BATCH_LIMIT else FreeAllowance.remaining()

    private fun limitMessage(limit: Int) = when {
        isPremium.value -> "A batch holds up to $limit photos. The extra photos weren't added."
        limit == 0 -> FREE_USED_MESSAGE
        else -> "Your free photo is a batch of $limit. Premium handles up to ${PremiumManager.PREMIUM_BATCH_LIMIT}."
    }

    private fun StoredItem.toItemStatus(running: Boolean): ItemStatus = when (status) {
        StoredStatus.PENDING -> ItemStatus.Pending
        StoredStatus.WORKING -> if (running) ItemStatus.Working else ItemStatus.Pending
        StoredStatus.SAVED -> ItemStatus.Saved
        StoredStatus.NO_SUBJECT -> ItemStatus.NoSubject
        StoredStatus.NEEDS_LOOK -> ItemStatus.NeedsLook
        StoredStatus.FAILED -> ItemStatus.Failed(reason ?: "Unknown error")
    }

    private companion object {
        const val FREE_USED_MESSAGE = "You've used your free photo. Go Premium to save more, without the watermark."
    }
}
