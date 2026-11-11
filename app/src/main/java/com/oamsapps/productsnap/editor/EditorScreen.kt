package com.oamsapps.productsnap.editor

import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.oamsapps.productsnap.R
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
import com.oamsapps.productsnap.ui.firstUseTip
import com.oamsapps.productsnap.ui.shareIntent
import com.oamsapps.productsnap.ui.theme.Studio

/** Aspect presets for the editor crop. Values are width / height. */
private val CropPresets: List<Pair<String?, Float?>> = listOf(
    null to null, // "Free": named in the phone's language where it is shown
    "1:1" to 1f,
    "4:5" to 4f / 5f,
    "3:4" to 3f / 4f,
    "16:9" to 16f / 9f,
)

/** The editor's panels. Flaws and Crop open their tools straight away. */
private enum class EditorTab(@StringRes val label: Int, val icon: ImageVector) {
    Cutout(R.string.cutout, Icons.Default.AutoFixHigh),
    Background(R.string.background, Icons.Default.Wallpaper),
    Light(R.string.light, Icons.Default.WbSunny),
    Flaws(R.string.flaws, Icons.Default.Adjust),
    Crop(R.string.crop, Icons.Default.Crop),
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
                    state.isErasing -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_editor), vm::finishErase, enabled = !state.isApplyingErase)
                    state.isMarkingFlaws -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_editor), vm::finishFlaws)
                    state.isRefining -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel_fixes), vm::cancelRefine)
                    state.isCropping -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel_crop), vm::cancelCrop)
                    else -> StageIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            state.isErasing -> stringResource(R.string.magic_eraser)
                            state.isMarkingFlaws -> stringResource(R.string.mark_flaws)
                            state.isRefining -> stringResource(R.string.fix_cutout)
                            state.isCropping -> stringResource(R.string.crop)
                            else -> stringResource(R.string.edit_photo)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = Studio.StageText,
                    )
                    BuildTag(color = Studio.StageMuted)
                }
                if (!state.isInTool) {
                    if (state.canUndo) StageIconButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo_last_edit), vm::undo)
                    StageIconButton(Icons.Default.Lightbulb, stringResource(R.string.photo_tips), { showTips = true })
                    StageIconButton(Icons.Default.AddPhotoAlternate, stringResource(R.string.choose_another_photo), pick)
                    StageIconButton(Icons.Default.PhotoLibrary, stringResource(R.string.edit_many_photos), onOpenBatch)
                } else if (state.isCropping) {
                    TextButton(onClick = vm::resetCrop, enabled = state.cropChanged) { Text(stringResource(R.string.reset), color = Studio.StageText) }
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
                            description = stringResource(if (state.refineRestore) R.string.refine_desc_restore else R.string.refine_desc_remove),
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
                                InkButton(stringResource(R.string.done), vm::finishErase, Modifier.fillMaxWidth(), enabled = !state.isApplyingErase)
                            }
                            state.isMarkingFlaws -> {
                                ToolPanel { FlawControls(state, vm) }
                                InkButton(stringResource(R.string.done), vm::finishFlaws, Modifier.fillMaxWidth())
                            }
                            state.isRefining -> {
                                ToolPanel { RefineControls(state, vm) }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PaperButton(stringResource(R.string.cancel), vm::cancelRefine, Modifier.weight(1f))
                                    InkButton(stringResource(R.string.done), vm::applyRefine, Modifier.weight(1f), enabled = state.refineStrokes.isNotEmpty())
                                }
                            }
                            state.isCropping -> {
                                ToolPanel { CropControls(state, vm) }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PaperButton(stringResource(R.string.cancel), vm::cancelCrop, Modifier.weight(1f))
                                    InkButton(stringResource(R.string.apply_crop), vm::applyCrop, Modifier.weight(1f), enabled = state.cropChanged)
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
                                        text = if (state.isSaving) stringResource(R.string.saving) else stringResource(R.string.save),
                                        icon = Icons.Default.Download,
                                        onClick = { vm.save() },
                                        enabled = !state.isSaving,
                                        modifier = Modifier.weight(1f),
                                    )
                                    InkButton(
                                        text = stringResource(R.string.share),
                                        icon = Icons.Default.Share,
                                        onClick = { vm.save(share = true) },
                                        enabled = !state.isSaving,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (!premium) {
                                    Text(
                                        stringResource(R.string.free_watermark_cta),
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
            Text(stringResource(R.string.no_photo_open), style = MaterialTheme.typography.headlineSmall, color = Studio.StageText)
            AccentButton(stringResource(R.string.choose_a_photo), onPick, icon = Icons.Default.AddPhotoAlternate)
        }
    }
}

/** What the photo is being made for and what has been done to it, as small pills over the stage. */
@Composable
private fun StatusRow(state: EditorState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StagePill(state.marketplace?.let { stringResource(R.string.market_shape, it.label, shape(it)) } ?: if (state.squareCanvas) stringResource(R.string.square) else stringResource(R.string.photos_own_shape), dot = Studio.Accent)
        when {
            state.isRemovingBackground -> StagePill(stringResource(R.string.cutting_out))
            state.useCutout && state.isNatural -> StagePill(stringResource(R.string.real_background), dot = Studio.Good)
            state.useCutout -> StagePill(stringResource(R.string.background_removed), dot = Studio.Good)
        }
        if (state.flaws.isNotEmpty()) StagePill(pluralStringResource(R.plurals.flaws_marked, state.flaws.size, state.flaws.size))
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
                        if (t == EditorTab.Flaws && flawCount > 0) stringResource(R.string.flaws_count, flawCount) else stringResource(t.label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun CutoutPanel(state: EditorState, vm: EditorViewModel) {
    Box(
        Modifier.fillMaxWidth().firstUseTip(
            key = "editor_cutout",
            title = stringResource(R.string.background_removed),
            body = stringResource(R.string.tip_cutout_body),
            order = 1,
            active = state.useCutout && !state.isNatural && !state.isRemovingBackground,
        )
    ) {
        Segmented(
            left = stringResource(R.string.remove_background),
            right = stringResource(R.string.original),
            leftSelected = state.useCutout,
            enabled = !state.isRemovingBackground,
            onLeft = vm::removeBackground,
            onRight = vm::showOriginal,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PaperButton(stringResource(R.string.fix_cutout), vm::startRefine, Modifier.weight(1f), icon = Icons.Default.Brush, enabled = !state.isRemovingBackground)
        PaperButton(stringResource(R.string.magic_eraser), vm::startErase, Modifier.weight(1f), icon = Icons.Default.Healing, enabled = !state.isRemovingBackground)
    }
    if (state.useCutout && !state.isNatural) {
        SwitchRow(stringResource(R.string.shadow_under_product), null, state.shadow, vm::setShadow)
    }
}

@Composable
private fun BackgroundPanel(state: EditorState, vm: EditorViewModel, premium: Boolean, onChoosePhoto: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().firstUseTip(
            key = "editor_background",
            title = stringResource(R.string.tip_background_title),
            body = stringResource(R.string.tip_background_body),
            order = 3,
        )
    ) {
        MarketplacePicker(state.marketplace, enabled = !state.isRemovingBackground, premium = premium, onSelect = vm::setMarketplace)
    }
    if (state.marketplace == null) SwitchRow(stringResource(R.string.square), null, state.squareCanvas, vm::setSquare)
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
            stringResource(R.string.background_hidden_note),
            style = MaterialTheme.typography.bodySmall,
            color = Studio.Muted,
        )
    }
}

@Composable
private fun LightPanel(state: EditorState, vm: EditorViewModel) {
    Box(
        Modifier.fillMaxWidth().firstUseTip(
            key = "editor_light",
            title = stringResource(R.string.auto_light),
            body = stringResource(R.string.tip_light_body),
            order = 3,
        )
    ) {
        LightStrengthPicker(state.lightStrength, enabled = true, onSelect = vm::setLightStrength)
    }
    LabeledSlider(stringResource(R.string.brightness), state.adjustments.brightness, vm::setBrightness)
    LabeledSlider(stringResource(R.string.contrast), state.adjustments.contrast, vm::setContrast)
    TextButton(onClick = vm::resetAdjustments, enabled = !state.adjustments.slidersNeutral) {
        Text(stringResource(R.string.reset_sliders), color = if (state.adjustments.slidersNeutral) Studio.Muted else Studio.Ink)
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
        if (state.cropAspect == null) stringResource(R.string.crop_hint_free)
        else stringResource(R.string.crop_hint_fixed)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .firstUseTip(
                key = "tool_crop",
                title = stringResource(R.string.crop),
                body = stringResource(R.string.tip_crop_body),
            )
            .horizontalScroll(rememberScrollState()),
    ) {
        CropPresets.forEach { (label, aspect) ->
            FilterChip(selected = state.cropAspect == aspect, onClick = { vm.setCropAspect(aspect) }, label = { Text(label ?: stringResource(R.string.crop_free)) })
        }
    }
    // Only offered when a cutout already exists, so this never triggers background removal on its own.
    if (state.cutout != null) {
        PaperButton(stringResource(R.string.fit_to_product), vm::fitCropToProduct, icon = Icons.Default.FitScreen)
    }
}

@Composable
private fun FlawControls(state: EditorState, vm: EditorViewModel) {
    Hint(stringResource(R.string.flaws_hint))
    Box(
        Modifier.fillMaxWidth().firstUseTip(
            key = "tool_flaws",
            title = stringResource(R.string.mark_flaws),
            body = stringResource(R.string.tip_flaws_tool_body),
        )
    ) {
        LabeledSlider(stringResource(R.string.circle_size), state.flawSize, vm::setFlawSize, 0.02f..0.15f)
    }
    SwitchRow(
        stringResource(R.string.circle_flaws),
        if (state.marketplace == Marketplace.EBAY) stringResource(R.string.circle_flaws_ebay)
        else stringResource(R.string.circle_flaws_note),
        state.circleFlaws,
        vm::setCircleFlaws,
    )
    TextButton(onClick = vm::undoFlaw, enabled = state.flaws.isNotEmpty()) { Text(stringResource(R.string.undo_last_flaw), color = Studio.Ink) }
}

@Composable
private fun RefineControls(state: EditorState, vm: EditorViewModel) {
    Box(
        Modifier.fillMaxWidth().firstUseTip(
            key = "tool_fix_cutout",
            title = stringResource(R.string.fix_cutout),
            body = stringResource(R.string.tip_fix_cutout_body),
        )
    ) {
        Segmented(
            left = stringResource(R.string.remove),
            right = stringResource(R.string.restore),
            leftSelected = !state.refineRestore,
            enabled = true,
            onLeft = { vm.setRefineRestore(false) },
            onRight = { vm.setRefineRestore(true) },
        )
    }
    Hint(
        if (state.refineRestore) stringResource(R.string.refine_hint_restore)
        else stringResource(R.string.refine_hint_remove)
    )
    LabeledSlider(stringResource(R.string.brush_size), state.brushSizeDp, vm::setBrushSize, 12f..64f)
    TextButton(onClick = vm::undoRefineStroke, enabled = state.refineStrokes.isNotEmpty()) { Text(stringResource(R.string.undo_stroke), color = Studio.Ink) }
}

@Composable
private fun EraseControls(state: EditorState, vm: EditorViewModel) {
    val busy = state.isApplyingErase
    Hint(
        stringResource(R.string.erase_hint)
    )
    Box(
        Modifier.fillMaxWidth().firstUseTip(
            key = "tool_magic_eraser",
            title = stringResource(R.string.magic_eraser),
            body = stringResource(R.string.tip_eraser_body),
        )
    ) {
        LabeledSlider(stringResource(R.string.brush_size), state.brushSizeDp, vm::setBrushSize, 12f..64f, enabled = !busy)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = vm::undoEraseStroke, enabled = state.eraseStrokes.isNotEmpty() && !busy) { Text(stringResource(R.string.undo_stroke), color = Studio.Ink) }
        TextButton(onClick = vm::undo, enabled = state.canUndo && !busy) { Text(stringResource(R.string.undo_last_edit), color = Studio.Ink) }
    }
    AccentButton(
        text = if (busy) stringResource(R.string.erasing) else stringResource(R.string.erase),
        onClick = vm::applyErase,
        enabled = state.eraseStrokes.isNotEmpty() && !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A tip about the photo, with a way to act on it or carry on regardless. */
@Composable
private fun WarningCard(warning: PhotoWarning, onFix: () -> Unit, onIgnore: () -> Unit) {
    val (title, body) = when (warning) {
        PhotoWarning.TOO_DARK -> stringResource(R.string.warn_dark_title) to
            stringResource(R.string.warn_dark_body)
        PhotoWarning.RUNS_OFF_EDGE -> stringResource(R.string.warn_edge_title) to
            stringResource(R.string.warn_edge_body)
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
                if (warning == PhotoWarning.RUNS_OFF_EDGE) TextButton(onClick = onFix) { Text(stringResource(R.string.fix_cutout), color = Studio.OnWarn) }
                TextButton(onClick = onIgnore) { Text(if (warning == PhotoWarning.TOO_DARK) stringResource(R.string.use_anyway) else stringResource(R.string.ignore), color = Studio.OnWarn) }
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
                    contentDescription = stringResource(R.string.original_photo),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().zoomTransform(zoom),
                )
            } else {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.edited_photo_desc),
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
            val holdToSeeOriginal = stringResource(R.string.hold_to_see_original)
            if (edited) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (comparing) Studio.Accent else Studio.Ink.copy(alpha = 0.85f),
                    contentColor = if (comparing) Studio.Ink else Studio.Paper,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .firstUseTip(
                            key = "editor_compare",
                            title = stringResource(R.string.hold_to_compare),
                            body = stringResource(R.string.tip_compare_body),
                            order = 2,
                        )
                        .semantics { contentDescription = holdToSeeOriginal }
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
                        Text(if (comparing) stringResource(R.string.original_photo) else stringResource(R.string.hold_to_compare), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (zoom.isZoomed) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    StageIconButton(Icons.Default.FitScreen, stringResource(R.string.fit_photo_to_screen), zoom::reset)
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
