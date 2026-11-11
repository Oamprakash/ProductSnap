package com.oamsapps.productsnap.ui

import androidx.compose.ui.res.stringResource
import com.oamsapps.productsnap.R
import androidx.annotation.StringRes
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oamsapps.productsnap.BuildConfig
import com.oamsapps.productsnap.ui.theme.Studio

/** Opened from the light-bulb button and the Photo tips card on Home. */
object PhotoTips {
    private const val PREFS = "photo_tips"
    private const val KEY_SEEN = "seen"

    fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
}

/** What each good-vs-bad pair shows. Each answers a failure seen in testing. */
private enum class Example(@StringRes val title: Int, @StringRes val avoid: Int, @StringRes val better: Int) {
    Light(R.string.light, R.string.tip_light_avoid, R.string.tip_light_better),
    Background(R.string.background, R.string.tip_bg_avoid, R.string.tip_bg_better),
    Framing(R.string.framing, R.string.tip_framing_avoid, R.string.tip_framing_better),
    Angle(R.string.angle, R.string.tip_angle_avoid, R.string.tip_angle_better),
}

private class Tip(val icon: ImageVector, @StringRes val title: Int, @StringRes val body: Int)

private val tips = listOf(
    Tip(Icons.Default.FlashOff, R.string.tip_flash_title, R.string.tip_flash_body),
    Tip(Icons.Default.CenterFocusStrong, R.string.tip_focus_title, R.string.tip_focus_body),
    Tip(Icons.Default.Adjust, R.string.tip_flaws_title, R.string.tip_flaws_body),
)

@Composable
fun PhotoTipsDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Studio.Paper) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tips_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    PaperIconButton(Icons.Default.Close, stringResource(R.string.close_tips), onDismiss)
                }
                Text(
                    stringResource(R.string.tips_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Studio.Muted,
                )
                Example.entries.forEach { ExampleRow(it) }

                SectionLabel(stringResource(R.string.shots_to_take))
                Text(
                    stringResource(R.string.shots_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Studio.Muted,
                )
                ShotGrid()
                Text(
                    stringResource(R.string.shots_extra),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Studio.Muted,
                )

                SectionLabel(stringResource(R.string.more_tips))
                tips.forEach { t ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Studio.Chip), contentAlignment = Alignment.Center) {
                            Icon(t.icon, null, Modifier.size(20.dp), tint = Studio.Ink)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(t.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(t.body), style = MaterialTheme.typography.bodyMedium, color = Studio.Muted)
                        }
                    }
                }
                InkButton(stringResource(R.string.got_it), onDismiss, Modifier.fillMaxWidth())
                Text(
                    "ProductSnap ${BuildConfig.VERSION_NAME.substringBefore('-')} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Studio.Muted,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun ExampleRow(example: Example) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(example.title), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExampleTile(example, good = false, Modifier.weight(1f))
            ExampleTile(example, good = true, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExampleTile(example: Example, good: Boolean, modifier: Modifier) {
    val caption = stringResource(if (good) example.better else example.avoid)
    val verdict = stringResource(if (good) R.string.better else R.string.avoid)
    val description = stringResource(R.string.label_colon_text, verdict, caption)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .semantics { contentDescription = description },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawExample(example, good) }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (good) Studio.Good else Studio.Accent,
                contentColor = if (good) Color.White else Studio.Ink,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (good) Icons.Default.Check else Icons.Default.Close, null, Modifier.size(14.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(verdict, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
    }
}

/** The shot list: the angles buyers look for, in the order to take them. */
private enum class Shot(@StringRes val title: Int, @StringRes val body: Int) {
    Front(R.string.shot_front, R.string.shot_front_body),
    Back(R.string.shot_back, R.string.shot_back_body),
    Label(R.string.shot_label, R.string.shot_label_body),
    Tag(R.string.shot_tag, R.string.shot_tag_body),
    Detail(R.string.shot_detail, R.string.shot_detail_body),
    Flaw(R.string.flaws, R.string.shot_flaw_body),
    Measure(R.string.shot_measure, R.string.shot_measure_body),
}

@Composable
private fun ShotGrid() {
    // Two per row; the last one sits alone at half width so every tile is the same size.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Shot.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { shot -> ShotTile(Shot.entries.indexOf(shot) + 1, shot, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShotTile(number: Int, shot: Shot, modifier: Modifier) {
    val title = stringResource(shot.title)
    val body = stringResource(shot.body)
    val description = stringResource(R.string.label_colon_text, title, body)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .semantics { contentDescription = description },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawShot(shot) }
            Surface(
                shape = CircleShape,
                color = Studio.Ink,
                contentColor = Studio.Paper,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.labelMedium) }
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
    }
}

private fun DrawScope.drawShot(shot: Shot) {
    val w = size.width
    val h = size.height
    clipRect {
        when (shot) {
            Shot.Front -> {
                drawRect(Floor)
                shirt(0.66f, shadow = true)
            }
            Shot.Back -> {
                drawRect(Floor)
                shirt(0.66f, color = ShirtDark, shadow = true)
                // A higher back neckline, so it reads as the back.
                drawArc(
                    Shirt, startAngle = 0f, sweepAngle = 180f, useCenter = true,
                    topLeft = Offset(w * 0.43f, h * 0.16f), size = Size(w * 0.14f, h * 0.06f),
                )
            }
            Shot.Label -> {
                // Close-up: fabric with a woven brand label.
                drawRect(Shirt)
                drawRoundRect(
                    Color.White, topLeft = Offset(w * 0.22f, h * 0.32f), size = Size(w * 0.56f, h * 0.30f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f),
                )
                drawRect(Studio.Ink, topLeft = Offset(w * 0.32f, h * 0.41f), size = Size(w * 0.36f, h * 0.05f))
                drawRect(Color(0xFFB8B2A7), topLeft = Offset(w * 0.36f, h * 0.50f), size = Size(w * 0.28f, h * 0.03f))
            }
            Shot.Tag -> {
                // Care tag: a long white tag with size and text lines.
                drawRect(Shirt)
                drawRect(Color.White, topLeft = Offset(w * 0.34f, h * 0.18f), size = Size(w * 0.32f, h * 0.64f))
                drawRect(Studio.Ink, topLeft = Offset(w * 0.42f, h * 0.24f), size = Size(w * 0.16f, h * 0.08f))
                for (i in 0 until 5) {
                    drawRect(Color(0xFFB8B2A7), topLeft = Offset(w * 0.39f, h * (0.38f + i * 0.08f)), size = Size(w * 0.22f, h * 0.025f))
                }
            }
            Shot.Detail -> {
                // Close-up of a zip.
                drawRect(Shirt)
                drawRect(ShirtDark, topLeft = Offset(w * 0.47f, 0f), size = Size(w * 0.06f, h))
                var y = 0f
                while (y < h) {
                    drawRect(Color(0xFFC9C3B8), topLeft = Offset(w * 0.44f, y), size = Size(w * 0.06f, h * 0.025f))
                    drawRect(Color(0xFFC9C3B8), topLeft = Offset(w * 0.50f, y + h * 0.03f), size = Size(w * 0.06f, h * 0.025f))
                    y += h * 0.06f
                }
                drawRoundRect(
                    Color(0xFFD9D3C8), topLeft = Offset(w * 0.42f, h * 0.55f), size = Size(w * 0.16f, h * 0.18f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f),
                )
            }
            Shot.Flaw -> {
                // Close-up of fabric with a stain, circled.
                drawRect(Shirt)
                drawCircle(Color(0xFF8C7A55), radius = w * 0.06f, center = Offset(w * 0.52f, h * 0.5f))
                drawCircle(Color(0xFF8C7A55).copy(alpha = 0.5f), radius = w * 0.09f, center = Offset(w * 0.49f, h * 0.53f))
                drawCircle(
                    Studio.Accent, radius = w * 0.2f, center = Offset(w * 0.5f, h * 0.51f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.025f),
                )
            }
            Shot.Measure -> {
                drawRect(Floor)
                shirt(0.66f, shadow = true)
                // A tape measure across the chest, with ticks.
                val y = h * 0.5f
                drawRect(Color(0xFFF2C14E), topLeft = Offset(w * 0.24f, y - h * 0.035f), size = Size(w * 0.52f, h * 0.07f))
                var x = w * 0.26f
                while (x < w * 0.75f) {
                    drawLine(Studio.Ink, Offset(x, y - h * 0.035f), Offset(x, y - h * 0.005f), strokeWidth = w * 0.006f)
                    x += w * 0.04f
                }
            }
        }
    }
}

// --- Illustrations: a simple T-shirt drawn the way each tip describes. ---

private val Shirt = Color(0xFF3B5A8C)
private val ShirtDark = Color(0xFF2F4A75)
private val Floor = Color(0xFFEAE3D6)

/** T-shirt outline in a 100 x 100 box. */
private fun shirtPath(): Path = Path().apply {
    moveTo(35f, 14f); lineTo(22f, 20f); lineTo(8f, 34f); lineTo(18f, 46f); lineTo(26f, 40f)
    lineTo(26f, 88f); lineTo(74f, 88f); lineTo(74f, 40f); lineTo(82f, 46f); lineTo(92f, 34f)
    lineTo(78f, 20f); lineTo(65f, 14f); quadraticBezierTo(60f, 24f, 50f, 24f); quadraticBezierTo(40f, 24f, 35f, 14f)
    close()
}

/** Draws the shirt [fraction] of the tile wide, centred at ([cx], [cy]) as fractions of the tile. */
private fun DrawScope.shirt(
    fraction: Float,
    cx: Float = 0.5f,
    cy: Float = 0.5f,
    color: Color = Shirt,
    degrees: Float = 0f,
    squash: Float = 1f,
    shadow: Boolean = false,
) {
    val s = size.minDimension * fraction / 100f
    val center = Offset(size.width * cx, size.height * cy)
    if (shadow) {
        drawOval(
            Color.Black.copy(alpha = 0.10f),
            topLeft = Offset(center.x - 32f * s, center.y + 34f * s * squash),
            size = Size(64f * s, 10f * s),
        )
    }
    rotate(degrees, center) {
        translate(center.x - 50f * s, center.y - 51f * s * squash) {
            scale(s, s * squash, pivot = Offset.Zero) {
                drawPath(shirtPath(), color)
            }
        }
    }
}

private fun DrawScope.drawExample(example: Example, good: Boolean) {
    clipRect {
        when (example) {
            Example.Light ->
                if (good) {
                    drawRect(Floor)
                    shirt(0.66f, shadow = true)
                } else {
                    drawRect(Color(0xFF3A342D))
                    shirt(0.66f, color = Color(0xFF26272B))
                }

            Example.Background ->
                if (good) {
                    drawRect(Floor)
                    shirt(0.66f, shadow = true)
                } else {
                    // Busy diagonal stripes close to the shirt's own colour.
                    drawRect(Color(0xFF5A6F93))
                    val step = size.minDimension / 9f
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(ShirtDark, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = step * 0.45f)
                        x += step
                    }
                    shirt(0.66f)
                }

            Example.Framing ->
                if (good) {
                    drawRect(Floor)
                    shirt(0.62f, shadow = true)
                } else {
                    drawRect(Floor)
                    shirt(1.15f, cx = 0.68f, cy = 0.62f)
                }

            Example.Angle ->
                if (good) {
                    drawRect(Floor)
                    shirt(0.66f, shadow = true)
                } else {
                    drawRect(Floor)
                    shirt(0.72f, degrees = -18f, squash = 0.62f, cy = 0.56f)
                }
        }
    }
}

