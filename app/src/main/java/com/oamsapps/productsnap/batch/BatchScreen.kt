package com.oamsapps.productsnap.batch

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

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val offerUpgrade = state.messageOffersUpgrade
        val result = snackbar.showSnackbar(
            message = message,
            actionLabel = if (offerUpgrade) "See Premium" else null,
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
                full { SectionLabel("Photos") }
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    PhotoTile(
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
            PaperIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Home", onBack)
            Spacer(Modifier.weight(1f))
            BuildTag()
            if (canClear) TextButton(onClick = onClear) { Text("Clear", color = Studio.Ink) }
        }
        Column {
            Text("Batch", style = MaterialTheme.typography.displaySmall)
            Text(
                buildString {
                    append(if (count == 0) "No photos yet" else "$count photo${if (count == 1) "" else "s"}")
                    append(" · ")
                    append(if (market != null) "for $market" else "any marketplace")
                },
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
    val parts = buildList {
        add(state.marketplace?.label ?: if (state.squareCanvas) "Square" else "Own shape")
        add(
            when {
                natural -> "Real background"
                state.removeBackground -> "Background removed"
                else -> "Original background"
            }
        )
        if (state.removeBackground && !natural && state.shadow) add("Shadow")
        add(if (state.lightStrength == LightStrength.OFF) "Light off" else "Light ${state.lightStrength.label}")
        if (state.crop != BatchCrop.NONE) add("Crop ${state.crop.label}")
        if (!state.adjustments.slidersNeutral) add("Editor sliders")
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Studio.Card, border = BorderStroke(1.dp, Studio.Line), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Every photo gets")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    parts.forEach { p ->
                        Surface(shape = RoundedCornerShape(999.dp), color = Studio.Chip) {
                            Text(p, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            TextButton(onClick = onChange, enabled = enabled) {
                Icon(Icons.Default.Tune, null, Modifier.size(18.dp), tint = if (enabled) Studio.Ink else Studio.Muted)
                Spacer(Modifier.width(6.dp))
                Text("Change", color = if (enabled) Studio.Ink else Studio.Muted)
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
        Text("Every photo gets", style = MaterialTheme.typography.headlineSmall)
        MarketplacePicker(state.marketplace, enabled, premium, vm::setMarketplace)
        if (state.marketplace == null) SettingSwitch("Square", state.squareCanvas, enabled, vm::setSquare)
        SettingSwitch("Remove background", state.removeBackground, enabled, vm::setRemoveBackground)
        if (state.removeBackground && !natural) SettingSwitch("Shadow under product", state.shadow, enabled, vm::setShadow)
        LightStrengthPicker(state.lightStrength, enabled, vm::setLightStrength)
        SectionLabel("Background")
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
                    "Brightness and contrast from the editor",
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::clearAdjustments, enabled = enabled) { Text("Remove", color = Studio.Ink) }
            }
        }
        InkButton("Done", onDone, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CropChips(state: BatchState, enabled: Boolean, onSelect: (BatchCrop) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Crop")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            BatchCrop.entries.forEach { crop ->
                // "Fit product" needs the cutout to know where the product is.
                val available = crop != BatchCrop.TIGHT || state.removeBackground
                FilterChip(
                    selected = state.crop == crop,
                    onClick = { onSelect(crop) },
                    enabled = enabled && available,
                    label = { Text(crop.label) },
                )
            }
        }
        val note = when {
            state.background == BackgroundStyle.Natural ->
                "With Natural, each photo is framed around the product instead; this crop isn't used."
            state.crop == BatchCrop.TIGHT && !state.removeBackground ->
                "Turn on Remove background to fit the crop to the product."
            state.crop == BatchCrop.TIGHT ->
                "Trims empty space so every product fills the frame the same way."
            state.crop.aspect != null && state.removeBackground ->
                "Each crop is centred on the product."
            state.crop.aspect != null && state.squareCanvas && state.crop != BatchCrop.SQUARE ->
                "Square adds padding around the crop."
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
                Text(if (freeAvailable) "Free: 1 photo, with a watermark" else "Free photo used", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Premium: batches of ${PremiumManager.PREMIUM_BATCH_LIMIT}, no watermark",
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Muted,
                )
            }
            TextButton(onClick = onUnlock) { Text("Go Premium", color = Studio.Ink) }
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
                        !state.isRunning -> "Done"
                        eta == null -> "Starting…"
                        eta < 60 -> "About ${eta}s left"
                        else -> "About ${(eta + 30) / 60} min left"
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
                if (state.isRunning) "You can leave the app; progress shows in your notifications."
                else state.timing ?: "${state.savedUris.size} saved to your gallery.",
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
                    0 -> "You've used your free photo. Premium edits whole batches without a watermark."
                    1 -> "Pick a photo to try it free. It's saved with the settings above, with a watermark."
                    else -> "Pick up to $limit photos. Each one is saved with the settings above."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Studio.Muted,
            )
            if (limit == 0) AccentButton("See Premium plans", onUnlock, Modifier.fillMaxWidth())
            else AccentButton(if (limit == 1) "Choose a photo" else "Choose photos", onPick, Modifier.fillMaxWidth(), icon = Icons.Default.Add)
        }
    }
}

@Composable
private fun ImportingRow() {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Studio.Accent, trackColor = Studio.Line)
        Text("Adding photos…", style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
    }
}

@Composable
private fun AddTile(count: Int, limit: Int, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(2.dp, Studio.Line),
        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClickLabel = "Add photos", onClick = onClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Add, null, tint = Studio.Ink)
            Text("$count of $limit", style = MaterialTheme.typography.labelMedium, color = Studio.Muted)
        }
    }
}

@Composable
private fun PhotoTile(index: Int, item: BatchItem, canRemove: Boolean, onRemove: () -> Unit, onOpen: (() -> Unit)?) {
    val thumb = remember(item.thumbnail) { item.thumbnail?.asImageBitmap() }
    val label = statusLabel(item.status, item.warnings)
    val problem = item.status is ItemStatus.Failed || item.status == ItemStatus.NoSubject || item.status == ItemStatus.NeedsLook
    val warn = item.status == ItemStatus.Saved && item.warnings.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Studio.Chip)
                .then(if (problem || warn) Modifier.border(2.dp, if (problem) Studio.Accent else Studio.OnWarn.copy(alpha = 0.5f), RoundedCornerShape(18.dp)) else Modifier)
                .semantics { contentDescription = "Photo ${index + 1}. $label" }
                .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open in the editor", onClick = onOpen) else Modifier),
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
                            modifier = Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Remove from batch", onClick = onRemove),
                        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Close, "Remove photo ${index + 1}", Modifier.size(16.dp)) } }
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

private fun shortLabel(status: ItemStatus, warnings: List<PhotoWarning>) = when (status) {
    ItemStatus.Saved -> if (PhotoWarning.RUNS_OFF_EDGE in warnings) "Check edges" else "Very dark"
    ItemStatus.NoSubject -> "No product found"
    ItemStatus.NeedsLook -> "Needs a look"
    is ItemStatus.Failed -> "Not saved"
    else -> ""
}

private fun statusLabel(status: ItemStatus, warnings: List<PhotoWarning>) = when (status) {
    ItemStatus.Pending -> "Ready"
    ItemStatus.Working -> "Saving…"
    ItemStatus.Saved -> when {
        PhotoWarning.RUNS_OFF_EDGE in warnings -> "Saved. The cutout may include background at the photo's edge. Tap to check"
        PhotoWarning.TOO_DARK in warnings -> "Saved. Very dark photo, so the cutout may be rough. Tap to check"
        else -> "Saved"
    }
    ItemStatus.NoSubject -> "No product found. Tap to open in the editor"
    ItemStatus.NeedsLook -> "Needs a look: the cutout came out wrong. Tap to fix in the editor"
    is ItemStatus.Failed -> "Not saved: ${status.reason}"
}

@Composable
private fun BatchBottomBar(state: BatchState, market: String?, onStart: () -> Unit, onCancel: () -> Unit, onPickNew: () -> Unit) {
    Surface(color = Studio.Paper, shadowElevation = 8.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.isRunning -> PaperButton("Stop", onCancel, Modifier.fillMaxWidth())
                state.queuedCount > 0 -> AccentButton(
                    text = if (state.queuedCount == state.failedCount) "Retry ${state.failedCount} failed"
                    else "Save ${state.queuedCount} photo${if (state.queuedCount == 1) "" else "s"}",
                    onClick = onStart,
                    enabled = !state.isImporting,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.savedUris.isNotEmpty()) {
                        val context = LocalContext.current
                        val n = state.savedUris.size
                        AccentButton(
                            text = if (market != null) "Share $n to $market" else "Share $n",
                            icon = Icons.Default.Share,
                            onClick = {
                                Telemetry.event("photos_shared", "source" to "batch", "count" to n)
                                runCatching { context.startActivity(shareIntent(state.savedUris)) }
                            },
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                    InkButton("New batch", onPickNew, Modifier.weight(1f))
                }
            }
        }
    }
}
