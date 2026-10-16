package com.oamsapps.productsnap.batch

import com.oamsapps.productsnap.ui.BuildTitle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oamsapps.productsnap.billing.PremiumManager
import com.oamsapps.productsnap.editor.BackgroundPicker
import com.oamsapps.productsnap.image.BatchCrop

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { BuildTitle("Edit many photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (state.items.isNotEmpty() && state.canEdit) {
                        TextButton(onClick = vm::clear) { Text("Clear") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                BatchBottomBar(
                    state = state,
                    onStart = start,
                    onCancel = vm::cancel,
                    onPickNew = { vm.clear(); pick() },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { SettingsCard(state, vm, onChoosePhoto = pickBackground) }

            if (!premium) item { UpsellCard(freeAvailable = freeAvailable, onUnlock = onOpenPaywall) }

            if (state.items.isEmpty() && !state.isImporting) {
                item { EmptyPrompt(limit = limit, onPick = pick, onUnlock = onOpenPaywall) }
            } else {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    BatchRow(
                        index = index,
                        item = item,
                        canRemove = state.canEdit,
                        onRemove = { vm.removeItem(item.id) },
                        onOpen = item.openable?.takeIf { state.canEdit }?.let { file -> { onOpenInEditor(file) } },
                    )
                }
                if (state.isImporting) {
                    item { ImportingRow() }
                } else if (!state.isRunning && state.items.size < limit) {
                    item {
                        OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
                            Text("Add photos (${state.items.size} of $limit)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(state: BatchState, vm: BatchViewModel, onChoosePhoto: () -> Unit) {
    val enabled = state.canEdit
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Applied to every photo", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("Remove background", state.removeBackground, enabled, vm::setRemoveBackground)
            if (state.removeBackground) {
                SettingSwitch("Shadow under product", state.shadow, enabled, vm::setShadow)
            }
            SettingSwitch("Square for marketplaces", state.squareCanvas, enabled, vm::setSquare)
            Text("Background", style = MaterialTheme.typography.bodyLarge)
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
            if (!state.adjustments.isNeutral) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Brightness and contrast from the editor",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::clearAdjustments, enabled = enabled) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun CropChips(state: BatchState, enabled: Boolean, onSelect: (BatchCrop) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Crop", style = MaterialTheme.typography.bodyLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
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
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun UpsellCard(freeAvailable: Boolean, onUnlock: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (freeAvailable) "Free: your first photo, full quality with a watermark"
                else "You've used your free photo",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Premium: unlimited photos, batches of ${PremiumManager.PREMIUM_BATCH_LIMIT}, no watermark",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onUnlock) { Text("See Premium plans") }
        }
    }
}

@Composable
private fun EmptyPrompt(limit: Int, onPick: () -> Unit, onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (limit) {
                0 -> "You've used your free photo. Premium edits whole batches without a watermark."
                1 -> "Pick a photo to try it free. It's saved with the settings above, with a watermark."
                else -> "Pick up to $limit photos. Each one is saved with the settings above."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        if (limit == 0) Button(onClick = onUnlock) { Text("See Premium plans") }
        else Button(onClick = onPick) { Text(if (limit == 1) "Choose a photo" else "Choose photos") }
    }
}

@Composable
private fun ImportingRow() {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            "Adding photos…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatchRow(index: Int, item: BatchItem, canRemove: Boolean, onRemove: () -> Unit, onOpen: (() -> Unit)?) {
    val thumb = remember(item.thumbnail) { item.thumbnail?.asImageBitmap() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open in the editor", onClick = onOpen) else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Photo ${index + 1}", style = MaterialTheme.typography.bodyLarge)
            Text(
                statusLabel(item.status),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status is ItemStatus.Failed || item.status == ItemStatus.NoSubject || item.status == ItemStatus.NeedsLook)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (item.status) {
            ItemStatus.Working -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            ItemStatus.Saved -> Icon(Icons.Default.CheckCircle, "Saved", tint = MaterialTheme.colorScheme.primary)
            ItemStatus.NoSubject, ItemStatus.NeedsLook, is ItemStatus.Failed ->
                Icon(Icons.Default.ErrorOutline, "Not saved", tint = MaterialTheme.colorScheme.error)
            ItemStatus.Pending -> if (canRemove) {
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove from batch") }
            }
        }
    }
}

private fun statusLabel(status: ItemStatus) = when (status) {
    ItemStatus.Pending -> "Ready"
    ItemStatus.Working -> "Saving…"
    ItemStatus.Saved -> "Saved"
    ItemStatus.NoSubject -> "No product found. Tap to open in the editor"
    ItemStatus.NeedsLook -> "Needs a look: the cutout came out wrong. Tap to fix in the editor"
    is ItemStatus.Failed -> "Not saved: ${status.reason}"
}

@Composable
private fun BatchBottomBar(
    state: BatchState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onPickNew: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isRunning || state.finishedCount > 0) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (state.isRunning) "${state.finishedCount} of ${state.items.size} done. You can leave the app; progress shows in your notifications."
                    else "${state.finishedCount} of ${state.items.size} done",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.isRunning && state.timing != null) {
                Text(state.timing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                state.isRunning ->
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                state.queuedCount > 0 ->
                    Button(onClick = onStart, enabled = !state.isImporting, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (state.queuedCount == state.failedCount) "Retry ${state.failedCount} failed"
                            else "Save ${state.queuedCount} photos"
                        )
                    }
                else ->
                    Button(onClick = onPickNew, modifier = Modifier.fillMaxWidth()) { Text("Start a new batch") }
            }
        }
    }
}
