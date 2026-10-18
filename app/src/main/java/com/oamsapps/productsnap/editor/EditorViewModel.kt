package com.oamsapps.productsnap.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oamsapps.imaging.BrushStroke
import com.oamsapps.imaging.CutoutBrush
import com.oamsapps.imaging.FlawGeometry
import com.oamsapps.imaging.LightFix
import com.oamsapps.imaging.PhotoCheck
import com.oamsapps.imaging.PhotoWarning
import com.oamsapps.productsnap.batch.BatchSettings
import com.oamsapps.productsnap.billing.PremiumManager
import com.oamsapps.productsnap.billing.FreeAllowance
import com.oamsapps.productsnap.image.Adjustments
import com.oamsapps.productsnap.image.BackgroundRemover
import com.oamsapps.productsnap.image.BitmapLoader
import com.oamsapps.productsnap.image.Cropper
import com.oamsapps.productsnap.image.ExportSettings
import com.oamsapps.productsnap.image.ImageRenderer
import com.oamsapps.productsnap.image.ImageSaver
import com.oamsapps.productsnap.image.RenderSpec
import com.oamsapps.productsnap.image.RingMark
import com.oamsapps.productsnap.image.LightStrength
import com.oamsapps.productsnap.image.Marketplace
import com.oamsapps.productsnap.image.ShadowMaker
import com.oamsapps.productsnap.image.background.BackgroundPhotos
import com.oamsapps.productsnap.image.background.BackgroundPresets
import com.oamsapps.productsnap.image.background.BackgroundStyle
import com.oamsapps.productsnap.image.erase.EraseStroke
import com.oamsapps.productsnap.image.erase.MagicEraser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** How far the cutout sits right and down inside [cutoutWithShadow]. */
    val shadowMargin: Int = 0,
    /** Slider values only; the per-photo light fix is kept apart in [light]. */
    val adjustments: Adjustments = Adjustments(),
    /** Auto light level; carried across photos. */
    val lightStrength: LightStrength = LightStrength.DEFAULT,
    /** Measured lighting fix for this photo's product, from background removal. */
    val light: LightFix = LightFix.NONE,
    val squareCanvas: Boolean = true,
    /** Shape and size for a marketplace; overrides [squareCanvas]. Carried across photos. */
    val marketplace: Marketplace? = null,
    /** Natural background: the photo cropped to the canvas shape around the product. */
    val naturalDisplay: Bitmap? = null,
    /** Where [naturalDisplay] was cut from the photo; null when it's the whole photo. */
    val naturalRect: PixelRect? = null,
    // Flaw marker
    val isMarkingFlaws: Boolean = false,
    /** Marked flaws on this photo, in [original]'s coordinates (0..1). */
    val flaws: List<Flaw> = emptyList(),
    /** Size for the next flaw circle, as a fraction of the photo's shorter side. */
    val flawSize: Float = 0.06f,
    /** Draw circles on the main photo. Off for eBay, which doesn't allow added artwork; close-ups are saved anyway. */
    val circleFlaws: Boolean = true,
    /** Saved photos to hand to the share sheet; the screen opens it and clears this. */
    val shareUris: List<Uri>? = null,
    val isLoading: Boolean = false,
    val isRemovingBackground: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    /** Photo and cutout before each erase, crop or touch-up, newest last, for undo. */
    val history: List<Snapshot> = emptyList(),
    /** Erase and crop work on the full photo; this brings the cutout back afterwards. */
    val restoreCutoutAfterEdit: Boolean = false,
    // Magic eraser
    val isErasing: Boolean = false,
    val eraseStrokes: List<EraseStroke> = emptyList(),
    val brushSizeDp: Float = 28f,
    val isApplyingErase: Boolean = false,
    /** A tip about this photo (too dark, cutout runs off the edge); the user can act on it or ignore it. */
    val warning: PhotoWarning? = null,
    /** Set when a save needs Premium; the screen opens the paywall and clears it. */
    val paywallRequested: Boolean = false,
    // Touch up cutout: hand fixes to what background removal kept
    val isRefining: Boolean = false,
    /** The cutout when touch-up started; strokes are applied to it. */
    val refineBase: Bitmap? = null,
    /** [refineBase] with [refineStrokes] applied; what the canvas shows. */
    val refinePreview: Bitmap? = null,
    val refineStrokes: List<BrushStroke> = emptyList(),
    /** Next stroke paints the photo back in (true) or removes from the cutout (false). */
    val refineRestore: Boolean = false,
    // Crop
    val isCropping: Boolean = false,
    val cropRect: Rect = FullFrame,      // normalised 0..1
    val cropAspect: Float? = null,       // width / height in pixels; null = free
) {
    val displayBitmap: Bitmap?
        get() = when {
            !useCutout -> original
            isNatural -> naturalDisplay ?: original
            shadow -> cutoutWithShadow ?: cutout
            else -> cutout
        }
    val hasImage get() = original != null
    val isNatural get() = background == BackgroundStyle.Natural
    /** The shape the result is framed to: a marketplace's, square, or the photo's own (null). */
    val canvasAspect: Float? get() = marketplace?.aspect ?: if (squareCanvas) 1f else null
    /** Auto light only applies around a cutout: it's measured on the product, and the sliders build on it. */
    val lightApplies get() = lightStrength != LightStrength.OFF && useCutout && light != LightFix.NONE
    /** What preview and export apply: the light fix (when on), then the sliders. */
    val effectiveAdjustments get() = if (lightApplies) adjustments.copy(light = lightStrength.apply(light)) else adjustments
    val canUndo get() = history.isNotEmpty()
    val isInTool get() = isErasing || isCropping || isRefining || isMarkingFlaws

    /** Flaw circles in the coordinates of [content], the bitmap shown and exported (see [displayBitmap]). */
    fun flawMarks(content: Bitmap?): List<RingMark> {
        val o = original ?: return emptyList()
        if (!circleFlaws || flaws.isEmpty() || content == null) return emptyList()
        val (dx, dy) = when {
            content === naturalDisplay && naturalRect != null -> -naturalRect.left to -naturalRect.top
            content === cutoutWithShadow -> shadowMargin to shadowMargin
            else -> 0 to 0
        }
        val side = minOf(o.width, o.height)
        return flaws.map { RingMark(it.x * o.width + dx, it.y * o.height + dy, it.r * side) }
    }
    val cropChanged get() = cropRect != FullFrame
}

/** A marked flaw: centre (0..1 of the photo) and circle radius (fraction of its shorter side). */
data class Flaw(val x: Float, val y: Float, val r: Float)

/** What undo goes back to. */
data class Snapshot(val original: Bitmap, val cutout: Bitmap?)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    val isPremium = PremiumManager.isPremium
    private val remover = BackgroundRemover.shared(app)
    private val eraser = MagicEraser()

    init {
        // Keep the shadowed cutout in step with the cutout and the toggle.
        viewModelScope.launch {
            _state.map { it.cutout to it.shadow }.distinctUntilChanged().collectLatest { (cut, on) ->
                if (cut == null || !on) {
                    _state.update { it.copy(cutoutWithShadow = null, shadowMargin = 0) }
                    return@collectLatest
                }
                val shadowed = runCatching { withContext(Dispatchers.Default) { ShadowMaker.applyWithMargin(cut) } }.getOrNull()
                _state.update {
                    if (it.cutout === cut) it.copy(cutoutWithShadow = shadowed?.first, shadowMargin = shadowed?.second ?: 0) else it
                }
            }
        }
        // Natural background: keep the framed photo in step with the photo, cutout and shape.
        viewModelScope.launch {
            _state.map { s -> if (s.isNatural && s.useCutout) Triple(s.original, s.cutout, s.canvasAspect) else null }
                .distinctUntilChanged()
                .collectLatest { key ->
                    if (key == null) { _state.update { it.copy(naturalDisplay = null, naturalRect = null) }; return@collectLatest }
                    val (photo, cut, aspect) = key
                    if (photo == null) return@collectLatest
                    val framed = runCatching {
                        withContext(Dispatchers.Default) {
                            val r = Cropper.naturalRect(photo, cut, aspect)
                            (if (r == null) photo else Cropper.crop(photo, r)) to r
                        }
                    }.getOrNull()
                    _state.update { it.copy(naturalDisplay = framed?.first, naturalRect = framed?.second) }
                }
        }
    }

    fun loadImage(uri: Uri) = viewModelScope.launch { load(uri) }

    /**
     * Opens a batch photo with the batch's look (marketplace, background, shape, light, sliders, shadow), so the
     * editor shows what batch saved rather than whatever the editor last used. Removes the background again when
     * batch did.
     */
    fun loadImage(uri: Uri, look: BatchSettings) = viewModelScope.launch {
        _state.update {
            it.copy(
                background = look.background,
                backgroundPhoto = null,
                squareCanvas = look.squareCanvas,
                marketplace = look.marketplace,
                adjustments = look.adjustments,
                lightStrength = look.lightStrength,
                shadow = look.shadow,
            )
        }
        (look.background as? BackgroundStyle.Photo)?.let { style ->
            launch {
                val bmp = runCatching { BackgroundPhotos.prepare(style, BACKGROUND_MAX_EDGE) }.getOrNull()
                _state.update { if (it.background == style) it.copy(backgroundPhoto = bmp) else it }
            }
        }
        if (load(uri) && (look.removeBackground || look.background == BackgroundStyle.Natural)) removeBackground()
    }

    /** Loads the photo keeping the current look; false if it couldn't be opened. */
    private suspend fun load(uri: Uri): Boolean {
        _state.update { it.copy(isLoading = true, message = null) }
        return runCatching { BitmapLoader.load(getApplication(), uri) }
            .onSuccess { bmp ->
                val dark = runCatching { withContext(Dispatchers.Default) { PhotoCheck.isTooDark(bmp) } }.getOrDefault(false)
                // Keep the user's look (background, canvas, adjustments, brush) across photos.
                _state.update { s ->
                    EditorState(
                        original = bmp,
                        background = s.background,
                        backgroundPhoto = s.backgroundPhoto,
                        squareCanvas = s.squareCanvas,
                        marketplace = s.marketplace,
                        adjustments = s.adjustments,
                        lightStrength = s.lightStrength,
                        brushSizeDp = s.brushSizeDp,
                        shadow = s.shadow,
                        warning = if (dark) PhotoWarning.TOO_DARK else null,
                        circleFlaws = s.marketplace != Marketplace.EBAY,
                    )
                }
            }
            .onFailure { _state.update { s -> s.copy(isLoading = false, message = "Couldn't open that photo. Try a different one.") } }
            .isSuccess
    }

    fun removeBackground() {
        val s = _state.value
        val original = s.original ?: return
        if (s.cutout != null) { _state.update { it.copy(useCutout = true) }; return }

        viewModelScope.launch {
            _state.update { it.copy(isRemovingBackground = true, message = null) }
            runCatching { remover.remove(original) }
                .onSuccess { removal ->
                    val cut = removal.cutout
                    _state.update {
                        // Ignore a stale result if the photo changed while this was running.
                        if (it.original !== original) it.copy(isRemovingBackground = false)
                        else if (cut == null) it.copy(
                            isRemovingBackground = false,
                            message = if (removal.needsLook) "The cutout came out wrong on this photo. Try one with a plainer background, or keep the original."
                            else "No product found. Try a photo with a clear subject.",
                        )
                        else it.copy(
                            cutout = cut,
                            light = removal.light,
                            useCutout = true,
                            isRemovingBackground = false,
                            warning = if (removal.runsOffEdge) PhotoWarning.RUNS_OFF_EDGE else it.warning,
                        )
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
    fun dismissWarning() = _state.update { it.copy(warning = null) }
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

    /** A marketplace sets the shape; Vinted also keeps the real background, the others a clean one. */
    fun setMarketplace(m: Marketplace?) {
        // eBay doesn't allow added artwork on photos; the flaw close-ups are separate photos, so they still go.
        _state.update { it.copy(marketplace = m, circleFlaws = if (m == Marketplace.EBAY) false else it.circleFlaws) }
        val bg = _state.value.background
        when {
            m?.natural == true && bg != BackgroundStyle.Natural -> setBackground(BackgroundStyle.Natural)
            m != null && !m.natural && (bg == BackgroundStyle.Natural || bg.isTransparent) -> setBackground(BackgroundPresets.WHITE)
        }
    }
    fun setBrightness(v: Float) = _state.update { it.copy(adjustments = it.adjustments.copy(brightness = v)) }
    fun setContrast(v: Float) = _state.update { it.copy(adjustments = it.adjustments.copy(contrast = v)) }
    fun resetAdjustments() = _state.update { it.copy(adjustments = Adjustments()) }
    fun setLightStrength(strength: LightStrength) = _state.update { it.copy(lightStrength = strength) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---------- Undo (erase and crop) ----------

    fun undo() {
        val s = _state.value
        val previous = s.history.lastOrNull() ?: return
        if (s.isApplyingErase) return
        val showCutout = s.useCutout || s.restoreCutoutAfterEdit
        _state.update {
            it.copy(
                original = previous.original,
                cutout = previous.cutout,
                useCutout = previous.cutout != null && s.useCutout,
                eraseStrokes = emptyList(),
                // Marks only stay if the photo kept its size (undoing a crop moves everything).
                flaws = if (previous.original.width == s.original?.width && previous.original.height == s.original.height) it.flaws else emptyList(),
                history = it.history.dropLast(1),
            )
        }
        // Edits from before snapshots kept cutouts have none; make it again if it was showing.
        if (showCutout && previous.cutout == null && !s.isInTool) removeBackground()
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
                            history = (it.history + Snapshot(original, s.cutout)).takeLast(MAX_UNDO),
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

    // ---------- Touch up cutout ----------

    /**
     * Starts hand fixes on the cutout, making one first if needed. If background removal finds nothing usable
     * (no product, or a garbled mask) it starts empty, and the product can be painted in with Restore.
     */
    fun startRefine() {
        val s = _state.value
        val original = s.original ?: return
        if (s.isRemovingBackground || s.isInTool) return
        if (s.cutout != null) { enterRefine(original, s.cutout); return }
        // No cutout yet: try background removal first, so there's usually something to fix rather than redo.
        viewModelScope.launch {
            _state.update { it.copy(isRemovingBackground = true, message = null) }
            val removal = runCatching { remover.remove(original) }.getOrNull()
            val cut = removal?.cutout
            _state.update { it.copy(isRemovingBackground = false, light = if (cut != null) removal?.light ?: it.light else it.light) }
            val now = _state.value
            if (now.original === original && !now.isInTool) enterRefine(original, cut)
        }
    }

    private fun enterRefine(original: Bitmap, cutout: Bitmap?) {
        val base = cutout ?: Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        refineJob?.cancel()
        refineJob = null
        refineBitmap = base
        _state.update {
            it.copy(
                isRefining = true,
                refineBase = base,
                refinePreview = base,
                refineStrokes = emptyList(),
                // Nothing to remove from an empty cutout, so start where the work is.
                refineRestore = cutout == null,
                message = if (cutout == null) "No cutout this time. Paint over the product with Restore to cut it out by hand." else it.message,
            )
        }
    }

    fun setRefineRestore(restore: Boolean) = _state.update { it.copy(refineRestore = restore) }

    fun addRefineStroke(stroke: EraseStroke) {
        val s = _state.value
        if (!s.isRefining) return
        val original = s.original ?: return
        val brush = BrushStroke(stroke.points, stroke.radius, restore = s.refineRestore)
        _state.update { it.copy(refineStrokes = it.refineStrokes + brush) }
        // Only the new stroke is drawn, on top of the latest render.
        queueRefine { current -> CutoutBrush.apply(current, original, listOf(brush)) }
    }

    fun undoRefineStroke() {
        val s = _state.value
        val base = s.refineBase ?: return
        val original = s.original ?: return
        if (s.refineStrokes.isEmpty()) return
        val strokes = s.refineStrokes.dropLast(1)
        _state.update { it.copy(refineStrokes = strokes) }
        queueRefine { if (strokes.isEmpty()) base else CutoutBrush.apply(base, original, strokes) }
    }

    private var refineJob: Job? = null
    /** Latest render, owned by the queue below; it always includes every stroke queued before it. */
    private var refineBitmap: Bitmap? = null

    /** Renders run one after another in the order queued, so fast strokes and undos can't land out of order. */
    private fun queueRefine(render: (current: Bitmap) -> Bitmap) {
        val previous = refineJob
        refineJob = viewModelScope.launch {
            previous?.join()
            val current = refineBitmap ?: return@launch
            val bmp = withContext(Dispatchers.Default) { render(current) }
            refineBitmap = bmp
            _state.update { if (it.isRefining) it.copy(refinePreview = bmp) else it }
        }
    }

    fun cancelRefine() {
        refineJob?.cancel()
        refineBitmap = null
        _state.update { it.copy(isRefining = false, refineBase = null, refinePreview = null, refineStrokes = emptyList()) }
    }

    fun applyRefine() {
        val s = _state.value
        val original = s.original ?: return
        if (!s.isRefining) return
        if (s.refineStrokes.isEmpty()) { cancelRefine(); return }
        viewModelScope.launch {
            refineJob?.join()
            val result = refineBitmap ?: return@launch
            refineBitmap = null
            _state.update {
                it.copy(
                    cutout = result,
                    useCutout = true,
                    isRefining = false,
                    refineBase = null,
                    refinePreview = null,
                    refineStrokes = emptyList(),
                    // The edges have now been looked at by hand.
                    warning = if (it.warning == PhotoWarning.RUNS_OFF_EDGE) null else it.warning,
                    history = (it.history + Snapshot(original, s.cutout)).takeLast(MAX_UNDO),
                )
            }
        }
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
                // Marks were placed on the uncropped photo; they no longer line up.
                flaws = emptyList(),
                history = (it.history + Snapshot(original, s.cutout)).takeLast(MAX_UNDO),
            )
        }
        leaveTool()
    }

    // ---------- Flaw marker ----------

    fun startFlaws() = _state.update {
        if (!it.hasImage || it.isRemovingBackground || it.isInTool) it else it.copy(isMarkingFlaws = true)
    }

    fun finishFlaws() = _state.update { it.copy(isMarkingFlaws = false) }

    /** Tap on the photo (0..1): removes a flaw there, or marks a new one. */
    fun tapFlaw(x: Float, y: Float) = _state.update { s ->
        val o = s.original ?: return@update s
        val aspect = o.width / o.height.toFloat()
        val side = minOf(o.width, o.height).toFloat()
        val hit = s.flaws.lastOrNull { f ->
            val dx = (f.x - x) * o.width
            val dy = (f.y - y) * o.height
            dx * dx + dy * dy <= (f.r * side) * (f.r * side)
        }
        if (aspect <= 0f) s
        else if (hit != null) s.copy(flaws = s.flaws - hit)
        else s.copy(flaws = s.flaws + Flaw(x, y, s.flawSize))
    }

    fun setFlawSize(r: Float) = _state.update { s ->
        // Resizes the newest circle too, so you can tap then adjust.
        s.copy(flawSize = r, flaws = if (s.flaws.isEmpty()) s.flaws else s.flaws.dropLast(1) + s.flaws.last().copy(r = r))
    }

    fun undoFlaw() = _state.update { it.copy(flaws = it.flaws.dropLast(1)) }
    fun setCircleFlaws(on: Boolean) = _state.update { it.copy(circleFlaws = on) }

    // ---------- Save ----------

    fun paywallShown() = _state.update { it.copy(paywallRequested = false) }

    /**
     * Saves the photo, plus a close-up of each marked flaw, to the gallery. With [share], opens the share sheet
     * with them afterwards (to post straight into a marketplace app). The free result covers the whole set.
     */
    fun save(share: Boolean = false) {
        val s = _state.value
        val content = s.displayBitmap ?: return
        val original = s.original ?: return
        val freeSave = !isPremium.value
        if (freeSave && FreeAllowance.remaining() == 0) {
            _state.update { it.copy(paywallRequested = true) }
            return
        }
        val settings = ExportSettings(
            removeBackground = s.useCutout,
            background = s.background,
            squareCanvas = s.squareCanvas,
            marketplace = s.marketplace,
            adjustments = s.effectiveAdjustments,
            premium = isPremium.value,
        )
        val marks = s.flawMarks(content)

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, message = null) }
            runCatching {
                val uris = mutableListOf<Uri>()
                val out = ImageRenderer.render(settings.renderSpec(content, s.backgroundPhoto).copy(marks = marks))
                try {
                    uris += ImageSaver.saveToGallery(getApplication(), out, transparent = settings.transparent, quality = settings.jpegQuality)
                } finally {
                    out.recycle()
                }
                s.flaws.forEach { f -> uris += saveCloseUp(original, f, s.effectiveAdjustments, settings.premium) }
                uris
            }.onSuccess { uris ->
                if (freeSave) FreeAllowance.markUsed(getApplication())
                val what = if (uris.size > 1) "${uris.size} photos (with ${uris.size - 1} close-up${if (uris.size > 2) "s" else ""})" else "your photo"
                val message = when {
                    share -> null
                    freeSave -> "Saved $what to Pictures/ProductSnap. Go Premium to save more without the watermark."
                    else -> "Saved $what to Pictures/ProductSnap"
                }
                _state.update { it.copy(isSaving = false, message = message, shareUris = if (share) uris else null) }
            }.onFailure { e ->
                _state.update { it.copy(isSaving = false, message = "Couldn't save: ${e.message}") }
            }
        }
    }

    fun shareHandled() = _state.update { it.copy(shareUris = null) }

    /** The flaw as photographed (real background, Auto light and sliders applied), as its own photo. */
    private suspend fun saveCloseUp(original: Bitmap, f: Flaw, adjustments: Adjustments, premium: Boolean): Uri {
        val side = minOf(original.width, original.height)
        val r = FlawGeometry.closeUp(original.width, original.height, f.x * original.width, f.y * original.height, f.r * side)
        val crop = withContext(Dispatchers.Default) { Bitmap.createBitmap(original, r[0], r[1], r[2] - r[0], r[3] - r[1]) }
        val spec = RenderSpec(
            content = crop,
            adjustments = adjustments,
            background = null,
            canvasAspect = null,
            maxOutputEdge = PremiumManager.EXPORT_MAX_EDGE,
            watermark = if (premium) null else PremiumManager.WATERMARK,
        )
        val out = ImageRenderer.render(spec)
        try {
            return ImageSaver.saveToGallery(getApplication(), out, transparent = false, quality = 92)
        } finally {
            out.recycle()
            if (crop !== original) crop.recycle()
        }
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
