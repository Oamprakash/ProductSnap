package com.oamsapps.productsnap.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.oamsapps.productsnap.image.LightStrength
import com.oamsapps.productsnap.image.Marketplace
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oamsapps.productsnap.image.ImageRenderer
import com.oamsapps.productsnap.image.background.BackgroundPreset
import com.oamsapps.productsnap.image.background.BackgroundPresets
import com.oamsapps.productsnap.image.background.BackgroundStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Background chooser shared by the editor and batch: plain colours, studio lights and
 * gradients, and the user's own photo (optionally blurred).
 */
@Composable
fun BackgroundPicker(
    selected: BackgroundStyle,
    photoThumb: Bitmap?,
    onSelect: (BackgroundStyle) -> Unit,
    onChoosePhoto: () -> Unit,
    onBlurChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
    ) {
        NaturalOption(selected == BackgroundStyle.Natural, enabled) { onSelect(BackgroundStyle.Natural) }
        SwatchRow(BackgroundPresets.solids, selected, enabled, onSelect)
        SwatchRow(BackgroundPresets.scenes, selected, enabled, onSelect)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val photo = selected as? BackgroundStyle.Photo
            if (photo != null) {
                val thumb = remember(photoThumb) { photoThumb?.asImageBitmap() }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(10.dp))
                        .semantics { contentDescription = "Your background photo"; this.selected = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop)
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Color.LightGray, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled && !busy, onClick = onChoosePhoto)
                    .semantics { contentDescription = if (photo == null) "Use your own photo as background" else "Choose a different background photo" },
                contentAlignment = Alignment.Center,
            ) {
                if (busy && photo == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (photo != null) {
                Text("Blur", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = photo.blur, onCheckedChange = onBlurChange, enabled = enabled && !busy)
            } else {
                Text(
                    "Your photo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Keep the real background: a chip, since it isn't a colour swatch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NaturalOption(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            label = { Text("Natural: keep your background") },
            leadingIcon = { Icon(Icons.Default.Landscape, null, Modifier.size(18.dp)) },
        )
        if (selected) {
            Text(
                "Your real background, with light and framing only. Good for Vinted, which asks for unedited photos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwatchRow(
    presets: List<BackgroundPreset>,
    selected: BackgroundStyle,
    enabled: Boolean,
    onSelect: (BackgroundStyle) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        presets.forEach { preset ->
            val isSelected = preset.style == selected
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .backgroundStyle(preset.style, photo = null, shape = CircleShape)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.secondary else Color.LightGray,
                        shape = CircleShape,
                    )
                    .clickable(enabled = enabled) { onSelect(preset.style) }
                    .semantics {
                        contentDescription = "${preset.label} background"
                        this.selected = isSelected
                    }
            )
        }
    }
}

/**
 * Draws [style] behind the content, matching [ImageRenderer.drawBackground] so the
 * preview looks like the saved image.
 */
fun Modifier.backgroundStyle(style: BackgroundStyle, photo: ImageBitmap?, shape: Shape? = null): Modifier =
    when (style) {
        is BackgroundStyle.Solid ->
            if (style.isTransparent) checkerboardIf(true)
            else if (shape != null) background(Color(style.color), shape) else background(Color(style.color))
        is BackgroundStyle.Gradient ->
            background(Brush.verticalGradient(listOf(Color(style.top), Color(style.bottom))))
        is BackgroundStyle.Studio -> drawBehind {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(style.center), Color(style.edge)),
                    center = Offset(size.width * ImageRenderer.STUDIO_CENTER_X, size.height * ImageRenderer.STUDIO_CENTER_Y),
                    radius = max(size.width, size.height) * ImageRenderer.STUDIO_RADIUS,
                )
            )
        }
        // The photo itself is shown; nothing behind it.
        BackgroundStyle.Natural -> this
        is BackgroundStyle.Photo -> drawBehind {
            if (photo == null) { drawRect(Color.White); return@drawBehind }
            // Cover: crop the source to the view's shape, centred.
            val viewAspect = size.width / size.height
            val photoAspect = photo.width / photo.height.toFloat()
            val (sw, sh) = if (photoAspect > viewAspect) {
                (photo.height * viewAspect).roundToInt() to photo.height
            } else {
                photo.width to (photo.width / viewAspect).roundToInt()
            }
            drawImage(
                photo,
                srcOffset = IntOffset((photo.width - sw) / 2, (photo.height - sh) / 2),
                srcSize = IntSize(sw, sh),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
    }

/** Simple transparency hint behind transparent backgrounds. */
fun Modifier.checkerboardIf(enabled: Boolean): Modifier =
    if (!enabled) this else this.background(Color(0xFFE0E0E0))

/** Auto light level, shared by the editor and batch settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightStrengthPicker(selected: LightStrength, enabled: Boolean, onSelect: (LightStrength) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Auto light", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            LightStrength.entries.forEach { s ->
                FilterChip(selected = s == selected, onClick = { onSelect(s) }, enabled = enabled, label = { Text(s.label) })
            }
        }
        Text(
            when (selected) {
                LightStrength.OFF -> "The photo's own lighting, as shot."
                LightStrength.GENTLE -> "Fixes a dim or tinted photo, up to 1.6× brighter."
                LightStrength.NORMAL -> "Fixes a dim or tinted photo. Very dark photos go up to 2.2× brighter."
                LightStrength.STRONG -> "Fixes a dim or tinted photo. Very dark photos go up to 2.8× brighter; may look grainy."
            } + if (selected != LightStrength.OFF) " The item's own colours stay true." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Made for": one tap sets the shape, size and background a marketplace wants. Shared by editor and batch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplacePicker(selected: Marketplace?, enabled: Boolean, premium: Boolean, onSelect: (Marketplace?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Made for", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, enabled = enabled, label = { Text("Any") })
            Marketplace.entries.forEach { m ->
                FilterChip(selected = m == selected, onClick = { onSelect(m) }, enabled = enabled, label = { Text(m.label) })
            }
        }
        selected?.let { m ->
            Text(
                m.note + if (!premium) " Free saves carry a watermark, which most marketplaces don't allow; Premium removes it." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
