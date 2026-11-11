package com.oamsapps.productsnap.batch

import com.oamsapps.productsnap.ui.text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.oamsapps.productsnap.R
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oamsapps.imaging.PhotoWarning
import com.oamsapps.productsnap.billing.PremiumManager
import com.oamsapps.productsnap.editor.BackgroundPicker
import com.oamsapps.productsnap.editor.LightStrengthPicker
import com.oamsapps.productsnap.editor.MarketplacePicker
import com.oamsapps.productsnap.image.BatchCrop
import com.oamsapps.productsnap.image.LightStrength
import com.oamsapps.productsnap.image.background.BackgroundStyle
import com.oamsapps.productsnap.telemetry.Telemetry
import com.oamsapps.productsnap.ui.AccentButton
import com.oamsapps.productsnap.ui.BuildTag
import com.oamsapps.productsnap.ui.InkButton
import com.oamsapps.productsnap.ui.PaperButton
import com.oamsapps.productsnap.ui.PaperIconButton
import com.oamsapps.productsnap.ui.SectionLabel
import com.oamsapps.productsnap.ui.firstUseTip
import com.oamsapps.productsnap.ui.shareIntent
import com.oamsapps.productsnap.ui.theme.Studio
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(vm: BatchViewModel, onBack: () -> Unit, onOpenPaywall: () -> Unit, onOpenInEditor: (java.io.File) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val premium by vm.isPremium.collectAsStateWithLifecycle()
    val freeAvailable by vm.freeAvailable.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val limit = when {
        premium -> PremiumManager.PREMIUM_BATCH_LIMIT
        freeAvailable -> PremiumManager.FREE_RESULTS
        else -> 0
    }
    var showSettings by remember { mutableStateOf(false) }

    // Picker always allows the premium max; the ViewModel trims to the user's limit and explains why.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PremiumManager.PREMIUM_BATCH_LIMIT)
    ) { uris -> vm.addPhotos(uris) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::setBackgroundPhoto)
    }
    val pickBackground = { backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    // Progress notification needs this on Android 13+. The batch runs either way.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.start() }
    val start = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val seePremium = stringResource(R.string.see_premium)
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val offerUpgrade = state.messageOffersUpgrade
        val result = snackbar.showSnackbar(
            message = message,
            actionLabel = if (offerUpgrade) seePremium else null,
            duration = if (offerUpgrade) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onOpenPaywall()
        // Clear last: changing the key earlier would cancel this effect mid-snackbar.
        vm.clearMessage()
    }

    val market = state.marketplace?.label
    Scaffold(
        containerColor = Studio.Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                BatchBottomBar(state = state, market = market, onStart = start, onCancel = vm::cancel, onPickNew = { vm.clear(); pick() })
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 20.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            full {
                Header(
                    count = state.items.size,
                    market = market,
                    canClear = state.items.isNotEmpty() && state.canEdit,
                    onBack = onBack,
                    onClear = vm::clear,
                )
            }
            full { SettingsSummary(state, enabled = state.canEdit, onChange = { showSettings = true }) }
            if (!premium) full { PlanStrip(freeAvailable = freeAvailable, onUnlock = onOpenPaywall) }
            if (state.isRunning || state.finishedCount > 0) full { ProgressCard(state) }

            if (state.items.isEmpty() && !state.isImporting) {
                full { EmptyPrompt(limit = limit, onPick = pick, onUnlock = onOpenPaywall) }
            } else {
                full { SectionLabel(stringResource(R.string.photos)) }
                // The first photo that needs a look carries the tip about fixing flagged photos.
                val firstFlagged = state.items.indexOfFirst { needsLook(it) }
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    PhotoTile(
                        tip = index == firstFlagged,
                        index = index,
                        item = item,
                        canRemove = state.canEdit,
                        onRemove = { vm.removeItem(item.id) },
                        onOpen = item.openable?.takeIf { state.canEdit }?.let { file -> { onOpenInEditor(file) } },
                    )
                }
                if (!state.isImporting && !state.isRunning && state.items.size < limit) {
                    item { AddTile(state.items.size, limit, onClick = pick) }
                }
                if (state.isImporting) full { ImportingRow() }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Studio.Paper) {
            SettingsSheet(state, vm, premium, onChoosePhoto = pickBackground, onDone = { showSettings = false })
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Header(count: Int, market: String?, canClear: Boolean, onBack: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.home), onBack)
            Spacer(Modifier.weight(1f))
            BuildTag()
            if (canClear) TextButton(onClick = onClear) { Text(stringResource(R.string.clear), color = Studio.Ink) }
        }
        Column {
            Text(stringResource(R.string.batch), style = MaterialTheme.typography.displaySmall)
            val countText = if (count == 0) stringResource(R.string.no_photos_yet) else pluralStringResource(R.plurals.photo_count, count, count)
            val marketText = if (market != null) stringResource(R.string.for_market, market) else stringResource(R.string.any_marketplace_lower)
            Text(
                stringResource(R.string.count_dot_market, countText, marketText),
                style = MaterialTheme.typography.bodyLarge,
                color = Studio.Muted,
            )
        }
    }
}

/** One line of what every photo gets, with a single way to change it. */
@Composable
private fun SettingsSummary(state: BatchState, enabled: Boolean, onChange: () -> Unit) {
    val natural = state.background == BackgroundStyle.Natural
    val parts = listOfNotNull(
        state.marketplace?.label ?: stringResource(if (state.squareCanvas) R.string.square else R.string.own_shape),
        stringResource(
            when {
                natural -> R.string.real_background
                state.removeBackground -> R.string.background_removed
                else -> R.string.original_background
            }
        ),
        if (state.removeBackground && !natural && state.shadow) stringResource(R.string.shadow) else null,
        if (state.lightStrength == LightStrength.OFF) stringResource(R.string.light_off) else stringResource(R.string.light_level, state.lightStrength.text()),
        if (state.crop != BatchCrop.NONE) stringResource(R.string.crop_level, state.crop.text()) else null,
        if (!state.adjustments.slidersNeutral) stringResource(R.string.editor_sliders) else null,
    )
    Surface(shape = RoundedCornerShape(20.dp), color = Studio.Card, border = BorderStroke(1.dp, Studio.Line), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.every_photo_gets))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    parts.forEach { p ->
                        Surface(shape = RoundedCornerShape(999.dp), color = Studio.Chip) {
                            Text(p, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            TextButton(
                onClick = onChange,
                enabled = enabled,
                modifier = Modifier.firstUseTip(
                    key = "batch_settings",
                    title = stringResource(R.string.tip_batch_settings_title),
                    body = stringResource(R.string.tip_batch_settings_body),
                    order = 1,
                    active = enabled,
                ),
            ) {
                Icon(Icons.Default.Tune, null, Modifier.size(18.dp), tint = if (enabled) Studio.Ink else Studio.Muted)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.change), color = if (enabled) Studio.Ink else Studio.Muted)
            }
        }
    }
}

@Composable
private fun SettingsSheet(state: BatchState, vm: BatchViewModel, premium: Boolean, onChoosePhoto: () -> Unit, onDone: () -> Unit) {
    val enabled = state.canEdit
    val natural = state.background == BackgroundStyle.Natural
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.every_photo_gets), style = MaterialTheme.typography.headlineSmall)
        MarketplacePicker(state.marketplace, enabled, premium, vm::setMarketplace)
        if (state.marketplace == null) SettingSwitch(stringResource(R.string.square), state.squareCanvas, enabled, vm::setSquare)
        SettingSwitch(stringResource(R.string.remove_background), state.removeBackground, enabled, vm::setRemoveBackground)
        if (state.removeBackground && !natural) SettingSwitch(stringResource(R.string.shadow_under_product), state.shadow, enabled, vm::setShadow)
        LightStrengthPicker(state.lightStrength, enabled, vm::setLightStrength)
        SectionLabel(stringResource(R.string.background))
        BackgroundPicker(
            selected = state.background,
            photoThumb = state.backgroundThumb,
            onSelect = vm::setBackground,
            onChoosePhoto = onChoosePhoto,
            onBlurChange = vm::setBackgroundBlur,
            enabled = enabled,
            busy = state.isPreparingBackground,
        )
        CropChips(state, enabled, vm::setCrop)
        if (!state.adjustments.slidersNeutral) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.editor_sliders_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::clearAdjustments, enabled = enabled) { Text(stringResource(R.string.remove), color = Studio.Ink) }
            }
        }
        InkButton(stringResource(R.string.done), onDone, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CropChips(state: BatchState, enabled: Boolean, onSelect: (BatchCrop) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.crop))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            BatchCrop.entries.forEach { crop ->
                // "Fit product" needs the cutout to know where the product is.
                val available = crop != BatchCrop.TIGHT || state.removeBackground
                FilterChip(
                    selected = state.crop == crop,
                    onClick = { onSelect(crop) },
                    enabled = enabled && available,
                    label = { Text(crop.text()) },
                )
            }
        }
        val note = when {
            state.background == BackgroundStyle.Natural ->
                stringResource(R.string.crop_note_natural)
            state.crop == BatchCrop.TIGHT && !state.removeBackground ->
                stringResource(R.string.crop_note_needs_cutout)
            state.crop == BatchCrop.TIGHT ->
                stringResource(R.string.crop_note_tight)
            state.crop.aspect != null && state.removeBackground ->
                stringResource(R.string.crop_note_centred)
            state.crop.aspect != null && state.squareCanvas && state.crop != BatchCrop.SQUARE ->
                stringResource(R.string.crop_note_square)
            else -> null
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Studio.Muted) }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Studio.Ink, checkedThumbColor = Studio.Accent),
        )
    }
}

@Composable
private fun PlanStrip(freeAvailable: Boolean, onUnlock: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Studio.Chip, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (freeAvailable) stringResource(R.string.free_one_photo) else stringResource(R.string.free_photo_used), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.premium_batches_note, PremiumManager.PREMIUM_BATCH_LIMIT),
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Muted,
                )
            }
            TextButton(onClick = onUnlock) { Text(stringResource(R.string.go_premium), color = Studio.Ink) }
        }
    }
}

/** Big count, accent bar and an estimate of the time left, worked out from the photos done so far. */
@Composable
private fun ProgressCard(state: BatchState) {
    val total = state.items.size
    val done = state.finishedCount
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var startDone by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isRunning) {
        if (state.isRunning) {
            startedAt = SystemClock.elapsedRealtime()
            startDone = done
            while (true) { now = SystemClock.elapsedRealtime(); delay(1000) }
        } else startedAt = null
    }
    val eta = startedAt?.let { t0 ->
        val finishedHere = done - startDone
        if (finishedHere <= 0) null
        else ((now - t0) / finishedHere.toFloat() * (total - done) / 1000f).toInt().coerceAtLeast(1)
    }
    Surface(shape = RoundedCornerShape(24.dp), color = Studio.Ink, contentColor = Studio.Paper, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$done", style = MaterialTheme.typography.displayMedium, color = Studio.Paper)
                Text(" / $total", style = MaterialTheme.typography.headlineSmall, color = Studio.StageMuted, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        !state.isRunning -> stringResource(R.string.done)
                        eta == null -> stringResource(R.string.starting)
                        eta < 60 -> stringResource(R.string.eta_seconds, eta)
                        else -> stringResource(R.string.eta_minutes, (eta + 30) / 60)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.isRunning) Studio.Accent else Studio.Paper,
                )
            }
            LinearProgressIndicator(
                progress = { state.progress },
                color = Studio.Accent,
                trackColor = Studio.StageRaised,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)),
            )
            Text(
                if (state.isRunning) stringResource(R.string.batch_leave_note)
                else state.timing?.takeIf { com.oamsapps.productsnap.BuildConfig.DEBUG } ?: stringResource(R.string.saved_to_gallery, state.savedUris.size),
                style = MaterialTheme.typography.bodySmall,
                color = Studio.StageMuted,
            )
        }
    }
}

@Composable
private fun EmptyPrompt(limit: Int, onPick: () -> Unit, onUnlock: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Studio.Card, border = BorderStroke(1.dp, Studio.Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(36.dp), tint = Studio.Ink)
            Text(
                when (limit) {
                    0 -> stringResource(R.string.empty_free_used)
                    1 -> stringResource(R.string.empty_free_one)
                    else -> stringResource(R.string.pick_up_to, limit)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Studio.Muted,
            )
            if (limit == 0) AccentButton(stringResource(R.string.see_premium_plans), onUnlock, Modifier.fillMaxWidth())
            else AccentButton(if (limit == 1) stringResource(R.string.choose_a_photo) else stringResource(R.string.choose_photos), onPick, Modifier.fillMaxWidth(), icon = Icons.Default.Add)
        }
    }
}

@Composable
private fun ImportingRow() {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Studio.Accent, trackColor = Studio.Line)
        Text(stringResource(R.string.adding_photos), style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
    }
}

@Composable
private fun AddTile(count: Int, limit: Int, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(2.dp, Studio.Line),
        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClickLabel = stringResource(R.string.add_photos), onClick = onClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Add, null, tint = Studio.Ink)
            Text(stringResource(R.string.count_of_limit, count, limit), style = MaterialTheme.typography.labelMedium, color = Studio.Muted)
        }
    }
}

private fun needsLook(item: BatchItem) =
    item.status is ItemStatus.Failed || item.status == ItemStatus.NoSubject || item.status == ItemStatus.NeedsLook ||
        (item.status == ItemStatus.Saved && item.warnings.isNotEmpty())

@Composable
private fun PhotoTile(index: Int, item: BatchItem, canRemove: Boolean, onRemove: () -> Unit, onOpen: (() -> Unit)?, tip: Boolean = false) {
    val thumb = remember(item.thumbnail) { item.thumbnail?.asImageBitmap() }
    val label = statusLabel(item.status, item.warnings)
    val problem = item.status is ItemStatus.Failed || item.status == ItemStatus.NoSubject || item.status == ItemStatus.NeedsLook
    val warn = item.status == ItemStatus.Saved && item.warnings.isNotEmpty()
    val description = stringResource(R.string.photo_n_desc, index + 1, label)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .firstUseTip(
                    key = "batch_flagged",
                    title = stringResource(R.string.needs_a_look),
                    body = stringResource(R.string.tip_flagged_body),
                    order = 2,
                    active = tip,
                )
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Studio.Chip)
                .then(if (problem || warn) Modifier.border(2.dp, if (problem) Studio.Accent else Studio.OnWarn.copy(alpha = 0.5f), RoundedCornerShape(18.dp)) else Modifier)
                .semantics { contentDescription = description }
                .then(if (onOpen != null) Modifier.clickable(onClickLabel = stringResource(R.string.open_in_editor), onClick = onOpen) else Modifier),
        ) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (item.status == ItemStatus.Working) {
                Box(Modifier.matchParentSize().background(Studio.Ink.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = Studio.Accent, strokeWidth = 3.dp)
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                when {
                    item.status == ItemStatus.Saved && !warn -> StatusBadge(Studio.Good, Color.White) { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                    warn -> StatusBadge(Studio.WarnBg, Studio.OnWarn) { Icon(Icons.Default.PriorityHigh, null, Modifier.size(14.dp)) }
                    problem -> StatusBadge(Studio.Accent, Studio.Ink) { Icon(Icons.Default.PriorityHigh, null, Modifier.size(14.dp)) }
                    item.status == ItemStatus.Pending && canRemove ->
                        Surface(
                            shape = CircleShape,
                            color = Studio.Ink.copy(alpha = 0.7f),
                            contentColor = Studio.Paper,
                            modifier = Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = stringResource(R.string.remove_from_batch), onClick = onRemove),
                        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Close, stringResource(R.string.remove_photo_n, index + 1), Modifier.size(16.dp)) } }
                }
            }
        }
        if (problem || warn) {
            Text(shortLabel(item.status, item.warnings), style = MaterialTheme.typography.labelSmall, color = if (problem) Studio.Ink else Studio.Muted, maxLines = 2)
        }
    }
}

@Composable
private fun StatusBadge(bg: Color, fg: Color, content: @Composable () -> Unit) {
    Surface(Modifier.size(24.dp), shape = CircleShape, color = bg, contentColor = fg) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun shortLabel(status: ItemStatus, warnings: List<PhotoWarning>) = when (status) {
    ItemStatus.Saved -> if (PhotoWarning.RUNS_OFF_EDGE in warnings) stringResource(R.string.check_edges) else stringResource(R.string.very_dark)
    ItemStatus.NoSubject -> stringResource(R.string.no_product_found)
    ItemStatus.NeedsLook -> stringResource(R.string.needs_a_look)
    is ItemStatus.Failed -> stringResource(R.string.not_saved)
    else -> ""
}

@Composable
private fun statusLabel(status: ItemStatus, warnings: List<PhotoWarning>) = when (status) {
    ItemStatus.Pending -> stringResource(R.string.ready)
    ItemStatus.Working -> stringResource(R.string.saving)
    ItemStatus.Saved -> when {
        PhotoWarning.RUNS_OFF_EDGE in warnings -> stringResource(R.string.saved_edge_desc)
        PhotoWarning.TOO_DARK in warnings -> stringResource(R.string.saved_dark_desc)
        else -> stringResource(R.string.saved)
    }
    ItemStatus.NoSubject -> stringResource(R.string.no_product_desc)
    ItemStatus.NeedsLook -> stringResource(R.string.needs_look_desc)
    is ItemStatus.Failed -> stringResource(R.string.not_saved_reason, status.reason)
}

@Composable
private fun BatchBottomBar(state: BatchState, market: String?, onStart: () -> Unit, onCancel: () -> Unit, onPickNew: () -> Unit) {
    Surface(color = Studio.Paper, shadowElevation = 8.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.isRunning -> PaperButton(stringResource(R.string.stop), onCancel, Modifier.fillMaxWidth())
                state.queuedCount > 0 -> AccentButton(
                    text = if (state.queuedCount == state.failedCount) stringResource(R.string.retry_failed, state.failedCount)
                    else pluralStringResource(R.plurals.save_photos, state.queuedCount, state.queuedCount),
                    onClick = onStart,
                    enabled = !state.isImporting,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.savedUris.isNotEmpty()) {
                        val context = LocalContext.current
                        val n = state.savedUris.size
                        AccentButton(
                            text = if (market != null) stringResource(R.string.share_n_to, n, market) else stringResource(R.string.share_n, n),
                            icon = Icons.Default.Share,
                            onClick = {
                                Telemetry.event("photos_shared", "source" to "batch", "count" to n)
                                runCatching { context.startActivity(shareIntent(state.savedUris)) }
                            },
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                    InkButton(stringResource(R.string.new_batch), onPickNew, Modifier.weight(1f))
                }
            }
        }
    }
}
