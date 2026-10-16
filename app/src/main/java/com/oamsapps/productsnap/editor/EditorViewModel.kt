package com.oamsapps.productsnap.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oamsapps.productsnap.billing.PremiumManager
import com.oamsapps.productsnap.billing.FreeAllowance
import com.oamsapps.productsnap.image.Adjustments
import com.oamsapps.productsnap.image.BackgroundRemover
import com.oamsapps.productsnap.image.BitmapLoader
import com.oamsapps.productsnap.image.Cropper
import com.oamsapps.productsnap.image.ExportSettings
import com.oamsapps.productsnap.image.ImageRenderer
import com.oamsapps.productsnap.image.ImageSaver
import com.oamsapps.productsnap.image.ShadowMaker
import com.oamsapps.productsnap.image.background.BackgroundPhotos
import com.oamsapps.productsnap.image.background.BackgroundPresets
import com.oamsapps.productsnap.image.background.BackgroundStyle
import com.oamsapps.productsnap.image.erase.EraseStroke
import com.oamsapps.productsnap.image.erase.MagicEraser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import android.graphics.Rect as PixelRect

private val FullFrame = Rect(0f, 0f, 1f, 1f)

data class EditorState(
    val original: Bitmap? = null,
    val cutout: Bitmap? = null,
    val useCutout: Boolean = false,
    val background: BackgroundStyle = BackgroundPresets.WHITE,
    /** Ready-to-draw bitmap when [background] is a photo. */
    val backgroundPhoto: Bitmap? = null,
    val isPreparingBackground: Boolean = false,
    /** Soft shadow under the product; only shown around a cutout. */
    val shadow: Boolean = true,
    val cutoutWithShadow: Bitmap? = null,
    val adjustments: Adjustments = Adjustments(),
    val squareCanvas: Boolean = true,
    val isLoading: Boolean = false,
    val isRemovingBackground: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    /** Photos before each erase or crop, newest last, for undo. */
    val history: List<Bitmap> = emptyList(),
    /** Erase and crop work on the full photo; this brings the cutout back afterwards. */
    val restoreCutoutAfterEdit: Boolean = false,
    // Magic eraser
    val isErasing: Boolean = false,
    val eraseStrokes: List<EraseStroke> = emptyList(),
    val brushSizeDp: Float = 28f,
    val isApplyingErase: Boolean = false,
    /** Set when a save needs Premium; the screen opens the paywall and clears it. */
    val paywallRequested: Boolean = false,
    // Crop
    val isCropping: Boolean = false,
    val cropRect: Rect = FullFrame,      // normalised 0..1
    val cropAspect: Float? = null,       // width / height in pixels; null = free
) {
    val displayBitmap: Bitmap?
        get() = when {
            !useCutout -> original
            shadow -> cutoutWithShadow ?: cutout
            else -> cutout
        }
    val hasImage get() = original != null
    val canUndo get() = history.isNotEmpty()
    val isInTool get() = isErasing || isCropping
    val cropChanged get() = cropRect != FullFrame
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    val isPremium = PremiumManager.isPremium
    private val remover = BackgroundRemover(app)
    private val eraser = MagicEraser()

    init {
        // Keep the shadowed cutout in step with the cutout and the toggle.
        viewModelScope.launch {
            _state.map { it.cutout to it.shadow }.distinctUntilChanged().collectLatest { (cut, on) ->
                if (cut == null || !on) {
                    _state.update { it.copy(cutoutWithShadow = null) }
                    return@collectLatest
                }
                val shadowed = runCatching { withContext(Dispatchers.Default) { ShadowMaker.apply(cut) } }.getOrNull()
                _state.update { if (it.cutout === cut) it.copy(cutoutWithShadow = shadowed) else it }
            }
        }
    }

    fun loadImage(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, message = null) }
        runCatching { BitmapLoader.load(getApplication(), uri) }
            .onSuccess { bmp ->
                // Keep the user's look (background, canvas, adjustments, brush) across photos.
                _state.update { s ->
                    EditorState(
                        original = bmp,
                        background = s.background,
                        backgroundPhoto = s.backgroundPhoto,
                        squareCanvas = s.squareCanvas,
                        adjustments = s.adjustments,
                        brushSizeDp = s.brushSizeDp,
                        shadow = s.shadow,
                    )
                }
            }
            .onFailure { _state.update { s -> s.copy(isLoading = false, message = "Couldn't open that photo. Try a different one.") } }
    }

    fun removeBackground() {
        val s = _state.value
        val original = s.original ?: return
        if (s.cutout != null) { _state.update { it.copy(useCutout = true) }; return }

        viewModelScope.launch {
            _state.update { it.copy(isRemovingBackground = true, message = null) }
            runCatching { remover.removeBackground(original) }
                .onSuccess { cut ->
                    _state.update {
                        // Ignore a stale result if the photo changed while this was running.
                        if (it.original !== original) it.copy(isRemovingBackground = false)
                        else if (cut == null) it.copy(isRemovingBackground = false, message = "No product found. Try a photo with a clear subject.")
                        else it.copy(cutout = cut, useCutout = true, isRemovingBackground = false)
                    }
                }
                .onFailure {
                    _state.update { s2 ->
                        s2.copy(isRemovingBackground = false, message = "Background removal isn't ready yet. Check your connection so the model can download, then try again.")
                    }
                }
        }
    }

    fun showOriginal() = _state.update { it.copy(useCutout = false) }
    fun setShadow(on: Boolean) = _state.update { it.copy(shadow = on) }
    // ---------- Background swap ----------

    /** Anything beyond a plain colour only makes sense around a cutout, so get one. */
    fun setBackground(style: BackgroundStyle) {
        _state.update { it.copy(background = style, backgroundPhoto = if (style is BackgroundStyle.Photo) it.backgroundPhoto else null) }
        ensureCutoutFor(style)
    }

    fun setBackgroundPhoto(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isPreparingBackground = true, message = null) }
            runCatching {
                val file = BackgroundPhotos.import(getApplication(), uri)
                val style = BackgroundStyle.Photo(file.absolutePath, blur = true)
                style to BackgroundPhotos.prepare(style, BACKGROUND_MAX_EDGE)
            }.onSuccess { (style, bmp) ->
                _state.update { it.copy(background = style, backgroundPhoto = bmp, isPreparingBackground = false) }
                ensureCutoutFor(style)
            }.onFailure {
                _state.update { it.copy(isPreparingBackground = false, message = "Couldn't use that photo as a background. Try another one.") }
            }
        }
    }

    fun setBackgroundBlur(blur: Boolean) {
        val current = _state.value.background as? BackgroundStyle.Photo ?: return
        val style = current.copy(blur = blur)
        viewModelScope.launch {
            _state.update { it.copy(isPreparingBackground = true) }
            val bmp = runCatching { BackgroundPhotos.prepare(style, BACKGROUND_MAX_EDGE) }.getOrNull()
            _state.update { it.copy(background = style, backgroundPhoto = bmp, isPreparingBackground = false) }
        }
    }

    private fun ensureCutoutFor(style: BackgroundStyle) {
        val s = _state.value
        if (!style.isPlainColor && !s.useCutout && s.hasImage && !s.isInTool) removeBackground()
    }

    fun setSquare(on: Boolean) = _state.update { it.copy(squareCanvas = on) }
    fun setBrightness(v: Float) = _state.update { it.copy(adjustments = it.adjustments.copy(brightness = v)) }
    fun setContrast(v: Float) = _state.update { it.copy(adjustments = it.adjustments.copy(contrast = v)) }
    fun resetAdjustments() = _state.update { it.copy(adjustments = Adjustments()) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---------- Undo (erase and crop) ----------

    fun undo() {
        val s = _state.value
        val previous = s.history.lastOrNull() ?: return
        if (s.isApplyingErase) return
        val showCutout = s.useCutout
        _state.update {
            it.copy(
                original = previous,
                cutout = null,
                useCutout = false,
                eraseStrokes = emptyList(),
                history = it.history.dropLast(1),
            )
        }
        if (showCutout && !s.isInTool) removeBackground()
    }

    /** Leaves erase/crop mode and brings the cutout back if it was showing before. */
    private fun leaveTool() {
        val restore = _state.value.restoreCutoutAfterEdit
        _state.update {
            it.copy(isErasing = false, isCropping = false, eraseStrokes = emptyList(), restoreCutoutAfterEdit = false)
        }
        if (restore) removeBackground() // reuses the cutout if it's still valid
    }

    // ---------- Magic eraser ----------

    fun startErase() = _state.update {
        if (!it.hasImage || it.isRemovingBackground || it.isInTool) it
        else it.copy(isErasing = true, eraseStrokes = emptyList(), restoreCutoutAfterEdit = it.useCutout, useCutout = false)
    }

    fun finishErase() {
        val s = _state.value
        if (!s.isErasing || s.isApplyingErase) return
        leaveTool()
    }

    fun addEraseStroke(stroke: EraseStroke) = _state.update {
        if (it.isApplyingErase) it else it.copy(eraseStrokes = it.eraseStrokes + stroke)
    }

    fun undoEraseStroke() = _state.update { it.copy(eraseStrokes = it.eraseStrokes.dropLast(1)) }

    fun setBrushSize(dp: Float) = _state.update { it.copy(brushSizeDp = dp) }

    /**
     * Erases background clutter only. The product is found first (the existing cutout, or a fresh one) and its
     * pixels are put back over the filled result, so strokes that stray onto the item can't change it: stains,
     * holes and wear stay visible to buyers. Without a product (none found), the eraser works as before.
     */
    fun applyErase() {
        val s = _state.value
        val original = s.original ?: return
        if (s.eraseStrokes.isEmpty() || s.isApplyingErase) return
        val strokes = s.eraseStrokes

        viewModelScope.launch {
            _state.update { it.copy(isApplyingErase = true, message = null) }
            runCatching {
                val product = s.cutout ?: runCatching { remover.removeBackground(original) }.getOrNull()
                val filled = eraser.erase(original, strokes)
                (if (product != null) keepProduct(filled, product) else filled) to product
            }
                .onSuccess { (result, product) ->
                    _state.update {
                        if (it.original !== original) it.copy(isApplyingErase = false)
                        else it.copy(
                            original = result,
                            // The product's pixels are unchanged, so its cutout still matches the photo.
                            cutout = product,
                            eraseStrokes = emptyList(),
                            isApplyingErase = false,
                            history = (it.history + original).takeLast(MAX_UNDO),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            isApplyingErase = false,
                            message = if (e is OutOfMemoryError) "Not enough memory to erase that area. Try a smaller area."
                            else "Couldn't erase that area. Try again with a smaller stroke.",
                        )
                    }
                }
        }
    }

    /** Draws the product (a same-size cutout) back over the erased photo. */
    private fun keepProduct(filled: Bitmap, product: Bitmap): Bitmap {
        val out = if (filled.isMutable) filled else filled.copy(Bitmap.Config.ARGB_8888, true)
        android.graphics.Canvas(out).drawBitmap(product, null, android.graphics.Rect(0, 0, out.width, out.height), null)
        return out
    }

    // ---------- Crop ----------

    fun startCrop() = _state.update {
        if (!it.hasImage || it.isRemovingBackground || it.isInTool) it
        else it.copy(
            isCropping = true,
            cropRect = FullFrame,
            cropAspect = null,
            restoreCutoutAfterEdit = it.useCutout,
            useCutout = false,
        )
    }

    fun cancelCrop() {
        if (_state.value.isCropping) leaveTool()
    }

    fun setCropRect(rect: Rect) = _state.update { it.copy(cropRect = rect) }

    fun resetCrop() = _state.update { it.copy(cropRect = FullFrame, cropAspect = null) }

    /** Switches to a fixed ratio: the largest frame of that shape, centred where the current frame is. */
    fun setCropAspect(aspect: Float?) {
        val s = _state.value
        val bmp = s.original ?: return
        if (aspect == null) { _state.update { it.copy(cropAspect = null) }; return }
        val r = s.cropRect
        val px = Cropper.aspectRect(
            bmp.width, bmp.height, aspect,
            r.center.x * bmp.width, r.center.y * bmp.height,
        )
        _state.update { it.copy(cropAspect = aspect, cropRect = px.normalizedIn(bmp)) }
    }

    /** Frames the product with a little margin. Needs the cutout, which may be cached from earlier. */
    fun fitCropToProduct() {
        val cutout = _state.value.cutout ?: return
        viewModelScope.launch {
            val bounds = withContext(Dispatchers.Default) {
                Cropper.subjectBounds(cutout)?.let { Cropper.padded(it, 0.04f, cutout.width, cutout.height) }
            }
            if (bounds == null) {
                _state.update { it.copy(message = "No product found to fit the crop to.") }
            } else {
                _state.update { it.copy(cropAspect = null, cropRect = bounds.normalizedIn(cutout)) }
            }
        }
    }

    fun applyCrop() {
        val s = _state.value
        val original = s.original ?: return
        if (!s.isCropping) return
        if (!s.cropChanged) { cancelCrop(); return }

        val cropped = Cropper.crop(original, s.cropRect.pixelsIn(original))
        // The cutout is the same photo, so crop it the same way instead of recomputing it.
        val cutout = s.cutout?.let { Cropper.crop(it, s.cropRect.pixelsIn(it)) }
        _state.update {
            it.copy(
                original = cropped,
                cutout = cutout,
                history = (it.history + original).takeLast(MAX_UNDO),
            )
        }
        leaveTool()
    }

    // ---------- Save ----------

    fun paywallShown() = _state.update { it.copy(paywallRequested = false) }

    fun save() {
        val s = _state.value
        val content = s.displayBitmap ?: return
        val freeSave = !isPremium.value
        if (freeSave && FreeAllowance.remaining() == 0) {
            _state.update { it.copy(paywallRequested = true) }
            return
        }
        val settings = ExportSettings(
            removeBackground = s.useCutout,
            background = s.background,
            squareCanvas = s.squareCanvas,
            adjustments = s.adjustments,
            premium = isPremium.value,
        )

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, message = null) }
            runCatching {
                val out = ImageRenderer.render(settings.renderSpec(content, s.backgroundPhoto))
                try {
                    ImageSaver.saveToGallery(getApplication(), out, transparent = settings.transparent)
                } finally {
                    out.recycle()
                }
            }.onSuccess {
                if (freeSave) FreeAllowance.markUsed(getApplication())
                val message = if (freeSave) "Saved your free photo to Pictures/ProductSnap. Go Premium to save more without the watermark."
                else "Saved to Pictures/ProductSnap"
                _state.update { it.copy(isSaving = false, message = message) }
            }.onFailure { e ->
                _state.update { it.copy(isSaving = false, message = "Couldn't save: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        remover.close()
    }

    private fun PixelRect.normalizedIn(bmp: Bitmap) = Rect(
        left / bmp.width.toFloat(),
        top / bmp.height.toFloat(),
        right / bmp.width.toFloat(),
        bottom / bmp.height.toFloat(),
    )

    private fun Rect.pixelsIn(bmp: Bitmap) = PixelRect(
        (left * bmp.width).roundToInt().coerceIn(0, bmp.width - 1),
        (top * bmp.height).roundToInt().coerceIn(0, bmp.height - 1),
        (right * bmp.width).roundToInt().coerceIn(1, bmp.width),
        (bottom * bmp.height).roundToInt().coerceIn(1, bmp.height),
    )

    private companion object {
        // Each step keeps a full-size photo in memory (up to ~16 MB at 2048px).
        const val MAX_UNDO = 3
        const val BACKGROUND_MAX_EDGE = 2048
    }
}
