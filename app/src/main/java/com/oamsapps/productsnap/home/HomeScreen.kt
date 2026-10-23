package com.oamsapps.productsnap.home

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oamsapps.productsnap.billing.FreeAllowance
import com.oamsapps.productsnap.billing.PremiumManager
import com.oamsapps.productsnap.image.Marketplace
import com.oamsapps.productsnap.ui.BuildTag
import com.oamsapps.productsnap.ui.PaperIconButton
import com.oamsapps.productsnap.ui.PhotoTips
import com.oamsapps.productsnap.ui.PhotoTipsDialog
import com.oamsapps.productsnap.ui.SectionLabel
import com.oamsapps.productsnap.ui.theme.Studio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPhoto: (Uri) -> Unit,
    onOpenBatch: () -> Unit,
    onOpenPaywall: () -> Unit,
    onPrimaryChanged: (Marketplace?) -> Unit,
    onChangeMarkets: () -> Unit,
    onHowItWorks: () -> Unit,
) {
    val context = LocalContext.current
    val seller by SellerPrefs.seller.collectAsStateWithLifecycle()
    val premium by PremiumManager.isPremium.collectAsStateWithLifecycle()
    val freeLeft by FreeAllowance.available.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onOpenPhoto)
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    var showTips by remember { mutableStateOf(false) }
    if (showTips) PhotoTipsDialog(onDismiss = { PhotoTips.markSeen(context); showTips = false })
    var showMarkets by remember { mutableStateOf(false) }

    var recent by remember { mutableStateOf<List<Saved>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) { refreshKey++; onPauseOrDispose { } }
    LaunchedEffect(refreshKey) { recent = loadRecent(context) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Logo()
            Column(Modifier.weight(1f)) {
                Text("ProductSnap", style = MaterialTheme.typography.titleLarge)
                BuildTag()
            }
            PaperIconButton(Icons.Default.Lightbulb, "Photo tips", onClick = { showTips = true })
        }

        MarketPill(seller.primary, onClick = { showMarkets = true })

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Listing-ready photos in 60 seconds.", style = MaterialTheme.typography.displaySmall)
            TextButton(onClick = onHowItWorks, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text("How ProductSnap works", style = MaterialTheme.typography.labelLarge, color = Studio.Ink)
                Spacer(Modifier.size(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = Studio.Ink)
            }
        }

        // Actions
        BigTile(
            title = "One photo",
            body = "Clean background, good light, flaws marked.",
            icon = Icons.Default.PhotoCamera,
            container = Studio.Ink,
            content = Studio.Paper,
            onClick = pick,
            modifier = Modifier.fillMaxWidth(),
        )
        // Same height side by side, titles sharing a last line, so the two cards line up.
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigTile(
                title = "Many photos",
                body = "Up to ${PremiumManager.PREMIUM_BATCH_LIMIT} at once.",
                icon = Icons.Default.PhotoLibrary,
                container = Studio.Accent,
                content = Studio.Ink,
                onClick = onOpenBatch,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                titleLines = 2,
            )
            BigTile(
                title = "Photo tips",
                body = "Shoot better photos.",
                icon = Icons.Default.Lightbulb,
                container = Studio.Card,
                content = Studio.Ink,
                onClick = { showTips = true },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                titleLines = 2,
            )
        }

        if (recent.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Recent saves")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recent, key = { it.uri.toString() }) { item ->
                        Image(
                            item.thumb.asImageBitmap(),
                            contentDescription = "Saved photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(88.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(role = Role.Image) { view(context, item.uri) },
                        )
                    }
                }
            }
        }

        PlanStrip(premium = premium, freeLeft = freeLeft, onUpgrade = onOpenPaywall)
        Spacer(Modifier.height(8.dp))
    }

    if (showMarkets) {
        ModalBottomSheet(onDismissRequest = { showMarkets = false }, containerColor = Studio.Paper) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Make photos for", style = MaterialTheme.typography.headlineSmall)
                val options = seller.markets.ifEmpty { listOf(Marketplace.EBAY, Marketplace.VINTED, Marketplace.POSHMARK, Marketplace.DEPOP, Marketplace.ETSY, null) }
                options.forEach { m ->
                    val selected = m == seller.primary
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Studio.Card,
                        border = BorderStroke(2.dp, if (selected) Studio.Ink else Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.RadioButton) {
                                SellerPrefs.setPrimary(context, m)
                                onPrimaryChanged(m)
                                showMarkets = false
                            },
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m?.label ?: "Any marketplace", style = MaterialTheme.typography.titleMedium)
                                Text(m?.note ?: "Keeps the photo's own shape.", style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
                            }
                            if (selected) Icon(Icons.Default.Check, "Selected", tint = Studio.Ink)
                        }
                    }
                }
                TextButton(onClick = { showMarkets = false; onChangeMarkets() }) {
                    Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Change where I sell")
                }
            }
        }
    }
}

@Composable
private fun Logo() {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxSize(), color = Studio.Ink) {}
        Box(Modifier.size(20.dp).border(2.5.dp, Studio.Accent, CircleShape))
        Surface(Modifier.size(6.dp), shape = CircleShape, color = Studio.Accent) {}
    }
}

@Composable
private fun MarketPill(primary: Marketplace?, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Studio.Card,
        border = BorderStroke(1.dp, Studio.Line),
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Change marketplace", onClick = onClick),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.size(8.dp), shape = CircleShape, color = Studio.Good) {}
            Text(
                if (primary != null) "Selling on ${primary.label}" else "Any marketplace",
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = Studio.Muted)
        }
    }
}

@Composable
private fun BigTile(
    title: String,
    body: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Room reserved for the title, so tiles next to each other keep their text level. */
    titleLines: Int = 1,
) {
    val titleStyle = MaterialTheme.typography.headlineSmall
    val titleHeight = with(LocalDensity.current) { (titleStyle.lineHeight * titleLines).toDp() }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = container,
        contentColor = content,
        // Same 1 dp edge on every tile (matching the fill where no outline is wanted), so tiles match in size.
        border = BorderStroke(1.dp, if (container == Studio.Card) Studio.Line else container),
        onClick = onClick,
        modifier = modifier.heightIn(min = 140.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.heightIn(min = titleHeight), contentAlignment = Alignment.BottomStart) {
                Text(title, style = titleStyle, color = content)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.78f))
        }
    }
}

@Composable
private fun PlanStrip(premium: Boolean, freeLeft: Boolean, onUpgrade: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Studio.Chip, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (PremiumManager.allFree) "Free while testing" else if (premium) "Premium" else "Free plan", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        PremiumManager.allFree -> "Everything is unlocked while ProductSnap is in testing."
                        premium -> "Unlimited photos, no watermark."
                        freeLeft -> "${FreeAllowance.remaining()} photo left to save."
                        else -> "Free photo used. Go Premium to keep saving."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Muted,
                )
            }
            if (!premium) TextButton(onClick = onUpgrade) { Text("Go Premium", color = Studio.Ink, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

private class Saved(val uri: Uri, val thumb: Bitmap)

/** The newest photos ProductSnap saved (its own files need no storage permission). */
private suspend fun loadRecent(context: Context, limit: Int = 12): List<Saved> = withContext(Dispatchers.IO) {
    runCatching {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uris = mutableListOf<Uri>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/ProductSnap%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && uris.size < limit) uris += ContentUris.withAppendedId(collection, c.getLong(id))
        }
        uris.mapNotNull { uri ->
            runCatching { Saved(uri, context.contentResolver.loadThumbnail(uri, Size(256, 256), null)) }.getOrNull()
        }
    }.getOrDefault(emptyList())
}

private fun view(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
