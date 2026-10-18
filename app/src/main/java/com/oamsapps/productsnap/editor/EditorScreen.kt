package com.oamsapps.productsnap.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oamsapps.imaging.PhotoWarning
import com.oamsapps.productsnap.image.Marketplace
import com.oamsapps.productsnap.ui.AccentButton
import com.oamsapps.productsnap.ui.BuildTag
import com.oamsapps.productsnap.ui.InkButton
import com.oamsapps.productsnap.ui.PaperButton
import com.oamsapps.productsnap.ui.PhotoTipsDialog
import com.oamsapps.productsnap.ui.StageIconButton
import com.oamsapps.productsnap.ui.StagePill
import com.oamsapps.productsnap.ui.shareIntent
import com.oamsapps.productsnap.ui.theme.Studio

/** Aspect presets for the editor crop. Values are width / height. */
private val CropPresets = listOf(
    "Free" to null,
    "1:1" to 1f,
    "4:5" to 4f / 5f,
    "3:4" to 3f / 4f,
    "16:9" to 16f / 9f,
)

/** The editor's panels. Flaws and Crop open their tools straight away. */
private enum class EditorTab(val label: String, val icon: ImageVector) {
    Cutout("Cutout", Icons.Default.AutoFixHigh),
    Background("Background", Icons.Default.Wallpaper),
    Light("Light", Icons.Default.WbSunny),
    Flaws("Flaws", Icons.Default.Adjust),
    Crop("Crop", Icons.Default.Crop),
}

@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onOpenBatch: () -> Unit, onOpenPaywall: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val premium by vm.isPremium.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::loadImage)
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::setBackgroundPhoto)
    }
    val pickBackground = { backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    BackHandler(enabled = state.isErasing) { vm.finishErase() }
    BackHandler(enabled = state.isCropping) { vm.cancelCrop() }
    BackHandler(enabled = state.isRefining) { vm.cancelRefine() }
    BackHandler(enabled = state.isMarkingFlaws) { vm.finishFlaws() }

    var showTips by remember { mutableStateOf(false) }
    if (showTips) PhotoTipsDialog(onDismiss = { showTips = false })
    var tab by rememberSaveable { mutableStateOf(EditorTab.Cutout) }

    LaunchedEffect(state.shareUris) {
        val uris = state.shareUris ?: return@LaunchedEffect
        vm.shareHandled()
        runCatching { context.startActivity(shareIntent(uris)) }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    LaunchedEffect(state.paywallRequested) {
        if (state.paywallRequested) { onOpenPaywall(); vm.paywallShown() }
    }

    val original = state.original
    Box(Modifier.fillMaxSize().background(Studio.Stage)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar on the stage
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    state.isErasing -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to editor", vm::finishErase, enabled = !state.isApplyingErase)
                    state.isMarkingFlaws -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to editor", vm::finishFlaws)
                    state.isRefining -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Cancel fixes", vm::cancelRefine)
                    state.isCropping -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Cancel crop", vm::cancelCrop)
                    else -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Home", onBack)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            state.isErasing -> "Magic eraser"
                            state.isMarkingFlaws -> "Mark flaws"
                            state.isRefining -> "Fix cutout"
                            state.isCropping -> "Crop"
                            else -> "Edit photo"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = Studio.StageText,
                    )
                    BuildTag(color = Studio.StageMuted)
                }
                if (!state.isInTool) {
                    if (state.canUndo) StageIconButton(Icons.AutoMirrored.Filled.Undo, "Undo last edit", vm::undo)
                    StageIconButton(Icons.Default.Lightbulb, "Photo tips", { showTips = true })
                    StageIconButton(Icons.Default.AddPhotoAlternate, "Choose another photo", pick)
                    StageIconButton(Icons.Default.PhotoLibrary, "Edit many photos", onOpenBatch)
                } else if (state.isCropping) {
                    TextButton(onClick = vm::resetCrop, enabled = state.cropChanged) { Text("Reset", color = Studio.StageText) }
                }
            }

            // The stage: the photo, fitted to the space left by the panel.
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                when {
                    original == null -> EmptyStage(loading = state.isLoading, onPick = pick)
                    state.isErasing -> FitToSpace(original.width / original.height.toFloat(), Modifier.fillMaxSize()) {
                        EraseCanvas(
                            bitmap = original,
                            strokes = state.eraseStrokes,
                            brushSizeDp = state.brushSizeDp,
                            enabled = !state.isApplyingErase,
                            isWorking = state.isApplyingErase,
                            onStroke = vm::addEraseStroke,
                        )
                    }
                    state.isMarkingFlaws -> FitToSpace(original.width / original.height.toFloat(), Modifier.fillMaxSize()) {
                        FlawCanvas(original, state.flaws, onTap = vm::tapFlaw)
                    }
                    state.isRefining -> FitToSpace(original.width / original.height.toFloat(), Modifier.fillMaxSize()) {
                        EraseCanvas(
                            bitmap = state.refinePreview ?: original,
                            strokes = emptyList(),
                            brushSizeDp = state.brushSizeDp,
                            enabled = true,
                            isWorking = false,
                            onStroke = vm::addRefineStroke,
                            brushColor = if (state.refineRestore) RestoreBrushColor else EraseBrushColor,
                            underlay = original,
                            checkerboard = true,
                            description = "Cutout over a faint copy of the photo. Paint to " +
                                (if (state.refineRestore) "restore" else "remove") + ". Pinch to zoom.",
                        )
                    }
                    state.isCropping -> FitToSpace(original.width / original.height.toFloat(), Modifier.fillMaxSize()) {
                        CropCanvas(bitmap = original, rect = state.cropRect, aspect = state.cropAspect, onRectChange = vm::setCropRect)
                    }
                    else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusRow(state)
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Preview(state) }
                    }
                }
            }

            // Paper panel
            if (original != null) {
                Surface(
                    color = Studio.Paper,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when {
                            state.isErasing -> {
                                ToolPanel { EraseControls(state, vm) }
                                InkButton("Done", vm::finishErase, Modifier.fillMaxWidth(), enabled = !state.isApplyingErase)
                            }
                            state.isMarkingFlaws -> {
                                ToolPanel { FlawControls(state, vm) }
                                InkButton("Done", vm::finishFlaws, Modifier.fillMaxWidth())
                            }
                            state.isRefining -> {
                                ToolPanel { RefineControls(state, vm) }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PaperButton("Cancel", vm::cancelRefine, Modifier.weight(1f))
                                    InkButton("Done", vm::applyRefine, Modifier.weight(1f), enabled = state.refineStrokes.isNotEmpty())
                                }
                            }
                            state.isCropping -> {
                                ToolPanel { CropControls(state, vm) }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PaperButton("Cancel", vm::cancelCrop, Modifier.weight(1f))
                                    InkButton("Apply crop", vm::applyCrop, Modifier.weight(1f), enabled = state.cropChanged)
                                }
                            }
                            else -> {
                                state.warning?.let { WarningCard(it, onFix = vm::startRefine, onIgnore = vm::dismissWarning) }
                                EditorTabs(
                                    selected = tab,
                                    flawCount = state.flaws.size,
                                    enabled = !state.isRemovingBackground,
                                    onSelect = { t ->
                                        when (t) {
                                            EditorTab.Flaws -> vm.startFlaws()
                                            EditorTab.Crop -> vm.startCrop()
                                            else -> tab = t
                                        }
                                    },
                                )
                                ToolPanel {
                                    when (tab) {
                                        EditorTab.Cutout -> CutoutPanel(state, vm)
                                        EditorTab.Background -> BackgroundPanel(state, vm, premium, onChoosePhoto = pickBackground)
                                        EditorTab.Light -> LightPanel(state, vm)
                                        EditorTab.Flaws, EditorTab.Crop -> Unit
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AccentButton(
                                        text = if (state.isSaving) "Saving…" else "Save",
                                        icon = Icons.Default.Download,
                                        onClick = { vm.save() },
                                        enabled = !state.isSaving,
                                        modifier = Modifier.weight(1f),
                                    )
                                    InkButton(
                                        text = "Share",
                                        icon = Icons.Default.Share,
                                        onClick = { vm.save(share = true) },
                                        enabled = !state.isSaving,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (!premium) {
                                    Text(
                                        "Free saves carry a small watermark. Remove it with Premium →",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Studio.Muted,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(role = Role.Button, onClick = onOpenPaywall)
                                            .padding(vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 180.dp))
    }
}

@Composable
private fun EmptyStage(loading: Boolean, onPick: () -> Unit) {
    if (loading) {
        CircularProgressIndicator(color = Studio.Accent)
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("No photo open", style = MaterialTheme.typography.headlineSmall, color = Studio.StageText)
            AccentButton("Choose a photo", onPick, icon = Icons.Default.AddPhotoAlternate)
        }
    }
}

/** What the photo is being made for and what has been done to it, as small pills over the stage. */
@Composable
private fun StatusRow(state: EditorState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StagePill(state.marketplace?.let { "${it.label} · ${shape(it)}" } ?: if (state.squareCanvas) "Square" else "Photo's own shape", dot = Studio.Accent)
        when {
            state.isRemovingBackground -> StagePill("Cutting out…")
            state.useCutout && state.isNatural -> StagePill("Real background", dot = Studio.Good)
            state.useCutout -> StagePill("Background removed", dot = Studio.Good)
        }
        if (state.flaws.isNotEmpty()) StagePill("${state.flaws.size} flaw${if (state.flaws.size == 1) "" else "s"} marked")
    }
}

private fun shape(m: Marketplace) = when {
    m.aspect == 1f -> "1:1"
    m.aspect > 1f -> "4:3"
    else -> "4:5"
}

/** Panel controls scroll on their own so Save and Share stay put on short screens. */
@Composable
private fun ToolPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun EditorTabs(selected: EditorTab, flawCount: Int, enabled: Boolean, onSelect: (EditorTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EditorTab.entries.forEach { t ->
            val on = t == selected
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (on) Studio.Ink else Studio.Chip,
                contentColor = if (on) Studio.Paper else Studio.Ink,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .selectable(selected = on, enabled = enabled, role = Role.Tab, onClick = { onSelect(t) }),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(t.icon, null, Modifier.size(18.dp))
                    Text(
                        if (t == EditorTab.Flaws && flawCount > 0) "Flaws ($flawCount)" else t.label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun CutoutPanel(state: EditorState, vm: EditorViewModel) {
    Segmented(
        left = "Remove background",
        right = "Original",
        leftSelected = state.useCutout,
        enabled = !state.isRemovingBackground,
        onLeft = vm::removeBackground,
        onRight = vm::showOriginal,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PaperButton("Fix cutout", vm::startRefine, Modifier.weight(1f), icon = Icons.Default.Brush, enabled = !state.isRemovingBackground)
        PaperButton("Magic eraser", vm::startErase, Modifier.weight(1f), icon = Icons.Default.Healing, enabled = !state.isRemovingBackground)
    }
    if (state.useCutout && !state.isNatural) {
        SwitchRow("Shadow under product", null, state.shadow, vm::setShadow)
    }
}

@Composable
private fun BackgroundPanel(state: EditorState, vm: EditorViewModel, premium: Boolean, onChoosePhoto: () -> Unit) {
    MarketplacePicker(state.marketplace, enabled = !state.isRemovingBackground, premium = premium, onSelect = vm::setMarketplace)
    if (state.marketplace == null) SwitchRow("Square", null, state.squareCanvas, vm::setSquare)
    BackgroundPicker(
        selected = state.background,
        photoThumb = state.backgroundPhoto,
        onSelect = vm::setBackground,
        onChoosePhoto = onChoosePhoto,
        onBlurChange = vm::setBackgroundBlur,
        enabled = !state.isRemovingBackground,
        busy = state.isPreparingBackground,
    )
    if (!state.useCutout && state.canvasAspect == null) {
        Text(
            "Turn on Remove background or Square to see the background.",
            style = MaterialTheme.typography.bodySmall,
            color = Studio.Muted,
        )
    }
}

@Composable
private fun LightPanel(state: EditorState, vm: EditorViewModel) {
    LightStrengthPicker(state.lightStrength, enabled = true, onSelect = vm::setLightStrength)
    LabeledSlider("Brightness", state.adjustments.brightness, vm::setBrightness)
    LabeledSlider("Contrast", state.adjustments.contrast, vm::setContrast)
    TextButton(onClick = vm::resetAdjustments, enabled = !state.adjustments.slidersNeutral) {
        Text("Reset sliders", color = if (state.adjustments.slidersNeutral) Studio.Muted else Studio.Ink)
    }
}

@Composable
private fun Segmented(left: String, right: String, leftSelected: Boolean, enabled: Boolean, onLeft: () -> Unit, onRight: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Studio.Chip, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp).selectableGroup()) {
            listOf(left to leftSelected, right to !leftSelected).forEachIndexed { i, (label, on) ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (on) Studio.Ink else Color.Transparent,
                    contentColor = if (on) Studio.Paper else Studio.Ink,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = if (i == 0) onLeft else onRight),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Studio.Ink, checkedThumbColor = Studio.Accent),
        )
    }
}

/** Largest box of [aspect] (width / height) that fits the available space, centred. */
@Composable
private fun FitToSpace(aspect: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
        Box(Modifier.size(width, width / aspect)) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Studio.Muted)
}

@Composable
private fun CropControls(state: EditorState, vm: EditorViewModel) {
    Hint(
        if (state.cropAspect == null) "Drag the corners or edges to crop, or drag the middle to move the frame."
        else "Drag a corner to resize, or the middle to move the frame."
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        CropPresets.forEach { (label, aspect) ->
            FilterChip(selected = state.cropAspect == aspect, onClick = { vm.setCropAspect(aspect) }, label = { Text(label) })
        }
    }
    // Only offered when a cutout already exists, so this never triggers background removal on its own.
    if (state.cutout != null) {
        PaperButton("Fit to product", vm::fitCropToProduct, icon = Icons.Default.FitScreen)
    }
}

@Composable
private fun FlawControls(state: EditorState, vm: EditorViewModel) {
    Hint("Tap each stain, tear or mark so buyers see it up front. Every flaw is also saved as its own close-up photo. Tap a circle to remove it.")
    LabeledSlider("Circle size", state.flawSize, vm::setFlawSize, 0.02f..0.15f)
    SwitchRow(
        "Circle flaws on the main photo",
        if (state.marketplace == Marketplace.EBAY) "Off for eBay, which doesn't allow added marks. The close-ups show the flaws instead."
        else "The close-ups are saved either way.",
        state.circleFlaws,
        vm::setCircleFlaws,
    )
    TextButton(onClick = vm::undoFlaw, enabled = state.flaws.isNotEmpty()) { Text("Undo last flaw", color = Studio.Ink) }
}

@Composable
private fun RefineControls(state: EditorState, vm: EditorViewModel) {
    Segmented(
        left = "Remove",
        right = "Restore",
        leftSelected = !state.refineRestore,
        enabled = true,
        onLeft = { vm.setRefineRestore(false) },
        onRight = { vm.setRefineRestore(true) },
    )
    Hint(
        if (state.refineRestore) "Paint over parts of the product that were cut away. They come back exactly as photographed."
        else "Paint over anything that isn't the product, like a hanger or background between straps."
    )
    LabeledSlider("Brush size", state.brushSizeDp, vm::setBrushSize, 12f..64f)
    TextButton(onClick = vm::undoRefineStroke, enabled = state.refineStrokes.isNotEmpty()) { Text("Undo stroke", color = Studio.Ink) }
}

@Composable
private fun EraseControls(state: EditorState, vm: EditorViewModel) {
    val busy = state.isApplyingErase
    Hint(
        "Paint over background clutter, then tap Erase. Pinch to zoom in on small spots. " +
            "The product itself is protected, so its real condition stays visible to buyers."
    )
    LabeledSlider("Brush size", state.brushSizeDp, vm::setBrushSize, 12f..64f, enabled = !busy)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = vm::undoEraseStroke, enabled = state.eraseStrokes.isNotEmpty() && !busy) { Text("Undo stroke", color = Studio.Ink) }
        TextButton(onClick = vm::undo, enabled = state.canUndo && !busy) { Text("Undo last edit", color = Studio.Ink) }
    }
    AccentButton(
        text = if (busy) "Erasing…" else "Erase",
        onClick = vm::applyErase,
        enabled = state.eraseStrokes.isNotEmpty() && !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A tip about the photo, with a way to act on it or carry on regardless. */
@Composable
private fun WarningCard(warning: PhotoWarning, onFix: () -> Unit, onIgnore: () -> Unit) {
    val (title, body) = when (warning) {
        PhotoWarning.TOO_DARK -> "This photo is very dark" to
            "The cutout may miss parts of the item or take in some background. For the best result, retake it near a window or on a lighter surface."
        PhotoWarning.RUNS_OFF_EDGE -> "Check the edges of the cutout" to
            "The item runs off the edge of the photo, so the cutout may include background there. Fix it with the brush, or retake with space around the item."
    }
    Surface(shape = RoundedCornerShape(18.dp), color = Studio.WarnBg, contentColor = Studio.OnWarn, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WarningAmber, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (warning == PhotoWarning.RUNS_OFF_EDGE) TextButton(onClick = onFix) { Text("Fix cutout", color = Studio.OnWarn) }
                TextButton(onClick = onIgnore) { Text(if (warning == PhotoWarning.TOO_DARK) "Use anyway" else "Ignore", color = Studio.OnWarn) }
            }
        }
    }
}

@Composable
private fun Preview(state: EditorState) {
    val bmp = state.displayBitmap ?: return
    val image = remember(bmp) { bmp.asImageBitmap() }
    // Fresh zoom per photo / edit, so a new result is always shown whole first.
    val zoom = remember(bmp) { ZoomState(maxZoom = 5f) }
    // Same matrix the exporter uses, so preview == output.
    val adjustments = state.effectiveAdjustments
    val filter = remember(adjustments) {
        if (adjustments.isNeutral) null
        else ColorFilter.colorMatrix(ColorMatrix(adjustments.toColorMatrix().array))
    }
    val natural = state.isNatural && state.useCutout
    val aspect = state.canvasAspect
    val fillsBackground = !natural && (state.useCutout || aspect != null)
    val photo = remember(state.backgroundPhoto) { state.backgroundPhoto?.asImageBitmap() }

    // Held down: the photo as it was taken, for an honest before/after.
    var comparing by remember { mutableStateOf(false) }
    val originalImage = remember(state.source) { state.source?.asImageBitmap() }
    val marks = remember(state.flaws, state.circleFlaws, bmp) { state.flawMarks(bmp) }
    val inset = if (!natural && aspect != null) 20.dp else 0.dp
    val boxAspect = if (natural || aspect == null) bmp.width / bmp.height.toFloat() else aspect

    FitToSpace(boxAspect, Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .then(if (fillsBackground && !comparing) Modifier.backgroundStyle(state.background, photo) else Modifier)
                .pinchToZoom(zoom),
            contentAlignment = Alignment.Center,
        ) {
            if (comparing && originalImage != null) {
                Image(
                    bitmap = originalImage,
                    contentDescription = "Original photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().zoomTransform(zoom),
                )
            } else {
                Image(
                    bitmap = image,
                    contentDescription = "Edited photo. Pinch or double-tap to zoom.",
                    colorFilter = filter,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().zoomTransform(zoom).padding(inset),
                )
                if (marks.isNotEmpty()) {
                    Canvas(Modifier.fillMaxSize().zoomTransform(zoom).padding(inset)) {
                        // Same fit as the Image above.
                        val s = minOf(size.width / bmp.width, size.height / bmp.height)
                        val ox = (size.width - bmp.width * s) / 2f
                        val oy = (size.height - bmp.height * s) / 2f
                        marks.forEach { m -> drawFlawRing(Offset(ox + m.x * s, oy + m.y * s), m.r * s) }
                    }
                }
            }
            // Only once something has changed: a cutout, crop, erase, light or slider change, or flaw circles.
            val edited = state.source != null && (state.source !== bmp || filter != null || marks.isNotEmpty())
            if (edited) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (comparing) Studio.Accent else Studio.Ink.copy(alpha = 0.85f),
                    contentColor = if (comparing) Studio.Ink else Studio.Paper,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .semantics { contentDescription = "Hold to see the original photo" }
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                comparing = true
                                try { tryAwaitRelease() } finally { comparing = false }
                            })
                        },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Compare, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (comparing) "Original photo" else "Hold to compare", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (zoom.isZoomed) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    StageIconButton(Icons.Default.FitScreen, "Fit photo to screen", zoom::reset)
                }
            }
            if (state.isRemovingBackground) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Studio.Accent)
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
    enabled: Boolean = true,
) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = Studio.Ink, activeTrackColor = Studio.Ink, inactiveTrackColor = Studio.Line),
        )
    }
}
