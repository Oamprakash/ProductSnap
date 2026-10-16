package com.oamsapps.productsnap.editor

import com.oamsapps.productsnap.ui.BuildTitle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Aspect presets for the editor crop. Values are width / height. */
private val CropPresets = listOf(
    "Free" to null,
    "1:1" to 1f,
    "4:5" to 4f / 5f,
    "3:4" to 3f / 4f,
    "16:9" to 16f / 9f,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onOpenBatch: () -> Unit, onOpenPaywall: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val premium by vm.isPremium.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

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

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    LaunchedEffect(state.paywallRequested) {
        if (state.paywallRequested) { onOpenPaywall(); vm.paywallShown() }
    }

    Scaffold(
        topBar = {
            when {
                state.isErasing -> TopAppBar(
                    title = { BuildTitle("Magic eraser") },
                    navigationIcon = {
                        IconButton(onClick = vm::finishErase, enabled = !state.isApplyingErase) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to editor")
                        }
                    },
                )
                state.isRefining -> TopAppBar(
                    title = { BuildTitle("Fix cutout") },
                    navigationIcon = {
                        IconButton(onClick = vm::cancelRefine) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel fixes") }
                    },
                )
                state.isCropping -> TopAppBar(
                    title = { BuildTitle("Crop") },
                    navigationIcon = {
                        IconButton(onClick = vm::cancelCrop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel crop") }
                    },
                    actions = {
                        TextButton(onClick = vm::resetCrop, enabled = state.cropChanged) { Text("Reset") }
                    },
                )
                else -> TopAppBar(
                    title = { BuildTitle("ProductSnap") },
                    actions = {
                        if (state.canUndo) {
                            IconButton(onClick = vm::undo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo last edit") }
                        }
                        IconButton(onClick = onOpenBatch) { Icon(Icons.Default.PhotoLibrary, "Edit many photos") }
                        if (state.hasImage) {
                            IconButton(onClick = pick) { Icon(Icons.Default.AddPhotoAlternate, "Choose another photo") }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.hasImage) {
                Surface(tonalElevation = 3.dp) {
                    val barModifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
                    when {
                        state.isErasing ->
                            Button(onClick = vm::finishErase, enabled = !state.isApplyingErase, modifier = barModifier) {
                                Text("Done")
                            }
                        state.isRefining ->
                            Row(barModifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = vm::cancelRefine, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                Button(onClick = vm::applyRefine, enabled = state.refineStrokes.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                    Text("Done")
                                }
                            }
                        state.isCropping ->
                            Row(barModifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = vm::cancelCrop, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                Button(onClick = vm::applyCrop, enabled = state.cropChanged, modifier = Modifier.weight(1f)) {
                                    Text("Apply crop")
                                }
                            }
                        else ->
                            Column(barModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(onClick = vm::save, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Download, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (premium) "Save full quality" else "Save (with watermark)")
                                }
                                if (!premium) {
                                    TextButton(onClick = onOpenPaywall) { Text("Remove watermark with Premium") }
                                }
                            }
                    }
                }
            }
        },
    ) { padding ->
        val original = state.original
        val content = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        when {
            original == null ->
                EmptyState(onPick = pick, onOpenBatch = onOpenBatch, loading = state.isLoading, modifier = Modifier.padding(padding))

            // Tools don't scroll: the photo is fitted into the space above the controls,
            // so tall photos are always fully visible and every edge can be reached.
            state.isErasing ->
                ToolLayout(
                    aspect = original.width / original.height.toFloat(),
                    modifier = Modifier.padding(padding),
                    canvas = {
                        EraseCanvas(
                            bitmap = original,
                            strokes = state.eraseStrokes,
                            brushSizeDp = state.brushSizeDp,
                            enabled = !state.isApplyingErase,
                            isWorking = state.isApplyingErase,
                            onStroke = vm::addEraseStroke,
                        )
                    },
                    controls = { EraseControls(state, vm) },
                )

            state.isRefining ->
                ToolLayout(
                    aspect = original.width / original.height.toFloat(),
                    modifier = Modifier.padding(padding),
                    canvas = {
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
                    },
                    controls = { RefineControls(state, vm) },
                )

            state.isCropping ->
                ToolLayout(
                    aspect = original.width / original.height.toFloat(),
                    modifier = Modifier.padding(padding),
                    canvas = {
                        CropCanvas(
                            bitmap = original,
                            rect = state.cropRect,
                            aspect = state.cropAspect,
                            onRectChange = vm::setCropRect,
                        )
                    },
                    controls = { CropControls(state, vm) },
                )

            else ->
                Column(content, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Preview(state)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        // Hand fixes for what background removal got wrong (or couldn't do at all).
                        OutlinedButton(onClick = vm::startRefine, enabled = !state.isRemovingBackground) {
                            Icon(Icons.Default.Brush, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Fix cutout")
                        }
                        OutlinedButton(onClick = vm::startCrop, enabled = !state.isRemovingBackground) {
                            Icon(Icons.Default.Crop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Crop")
                        }
                        OutlinedButton(onClick = vm::startErase, enabled = !state.isRemovingBackground) {
                            Icon(Icons.Default.Healing, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Magic eraser")
                        }
                    }
                    BackgroundControls(state, vm, onChoosePhoto = pickBackground)
                    AdjustmentControls(state, vm)
                }
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit, onOpenBatch: () -> Unit, loading: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Clean product photos in seconds", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a photo, remove the background, and save a square image ready for your store.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onPick) { Text("Choose a photo") }
            TextButton(onClick = onOpenBatch) { Text("Edit many photos at once") }
        }
    }
}

/**
 * Photo on top, fitted to the free space at its own aspect ratio; controls below.
 * Controls scroll on their own if the screen is very short (e.g. landscape).
 */
@Composable
private fun ToolLayout(
    aspect: Float,
    canvas: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FitToSpace(aspect, Modifier.weight(1f).fillMaxWidth()) { canvas() }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { controls() }
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
private fun CropControls(state: EditorState, vm: EditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (state.cropAspect == null) "Drag the corners or edges to crop, or drag the middle to move the frame."
            else "Drag a corner to resize, or the middle to move the frame.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            CropPresets.forEach { (label, aspect) ->
                FilterChip(
                    selected = state.cropAspect == aspect,
                    onClick = { vm.setCropAspect(aspect) },
                    label = { Text(label) },
                )
            }
        }
        // Only offered when a cutout already exists, so this never triggers background removal on its own.
        if (state.cutout != null) {
            OutlinedButton(onClick = vm::fitCropToProduct) {
                Icon(Icons.Default.FitScreen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fit to product")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefineControls(state: EditorState, vm: EditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !state.refineRestore,
                onClick = { vm.setRefineRestore(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Remove") }
            SegmentedButton(
                selected = state.refineRestore,
                onClick = { vm.setRefineRestore(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Restore") }
        }
        Text(
            if (state.refineRestore) "Paint over parts of the product that were cut away. They come back exactly as photographed."
            else "Paint over anything that isn't the product, like a hanger or background between straps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text("Brush size", style = MaterialTheme.typography.bodyMedium)
            Slider(value = state.brushSizeDp, onValueChange = vm::setBrushSize, valueRange = 12f..64f)
        }
        TextButton(onClick = vm::undoRefineStroke, enabled = state.refineStrokes.isNotEmpty()) {
            Text("Undo stroke")
        }
    }
}

@Composable
private fun EraseControls(state: EditorState, vm: EditorViewModel) {
    val busy = state.isApplyingErase
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Paint over background clutter, then tap Erase. Pinch to zoom in on small spots. " +
                "The product itself is protected, so its real condition stays visible to buyers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text("Brush size", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = state.brushSizeDp,
                onValueChange = vm::setBrushSize,
                valueRange = 12f..64f,
                enabled = !busy,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::undoEraseStroke, enabled = state.eraseStrokes.isNotEmpty() && !busy) {
                Text("Undo stroke")
            }
            TextButton(onClick = vm::undo, enabled = state.canUndo && !busy) {
                Text("Undo last edit")
            }
        }
        Button(
            onClick = vm::applyErase,
            enabled = state.eraseStrokes.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Erasing…" else "Erase")
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
    val filter = remember(state.adjustments) {
        if (state.adjustments.isNeutral) null
        else ColorFilter.colorMatrix(ColorMatrix(state.adjustments.toColorMatrix().array))
    }
    val fillsBackground = state.useCutout || state.squareCanvas
    val photo = remember(state.backgroundPhoto) { state.backgroundPhoto?.asImageBitmap() }

    Box(
        Modifier
            .fillMaxWidth()
            .then(if (state.squareCanvas) Modifier.aspectRatio(1f) else Modifier.aspectRatio(bmp.width / bmp.height.toFloat()))
            .clip(RoundedCornerShape(12.dp))
            .then(if (fillsBackground) Modifier.backgroundStyle(state.background, photo) else Modifier)
            .pinchToZoom(zoom),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = "Edited photo. Pinch or double-tap to zoom.",
            colorFilter = filter,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .zoomTransform(zoom)
                .padding(if (state.squareCanvas) 20.dp else 0.dp),
        )
        if (zoom.isZoomed) {
            FilledTonalIconButton(
                onClick = zoom::reset,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp),
            ) {
                Icon(Icons.Default.FitScreen, "Fit photo to screen")
            }
        }
        if (state.isRemovingBackground) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun BackgroundControls(state: EditorState, vm: EditorViewModel, onChoosePhoto: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.useCutout,
                onClick = vm::removeBackground,
                enabled = !state.isRemovingBackground,
                label = { Text("Remove background") },
                leadingIcon = { Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp)) },
            )
            FilterChip(selected = !state.useCutout, onClick = vm::showOriginal, label = { Text("Original") })
        }
        if (state.useCutout) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shadow under product", Modifier.weight(1f))
                Switch(checked = state.shadow, onCheckedChange = vm::setShadow)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Square for marketplaces", Modifier.weight(1f))
            Switch(checked = state.squareCanvas, onCheckedChange = vm::setSquare)
        }
        Text("Background", style = MaterialTheme.typography.titleMedium)
        BackgroundPicker(
            selected = state.background,
            photoThumb = state.backgroundPhoto,
            onSelect = vm::setBackground,
            onChoosePhoto = onChoosePhoto,
            onBlurChange = vm::setBackgroundBlur,
            enabled = !state.isRemovingBackground,
            busy = state.isPreparingBackground,
        )
        if (!state.useCutout && !state.squareCanvas) {
            Text(
                "Turn on Remove background or Square to see the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AdjustmentControls(state: EditorState, vm: EditorViewModel) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Adjust", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::resetAdjustments, enabled = !state.adjustments.isNeutral) { Text("Reset") }
        }
        LabeledSlider("Brightness", state.adjustments.brightness, vm::setBrightness)
        LabeledSlider("Contrast", state.adjustments.contrast, vm::setContrast)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = -1f..1f)
    }
}
