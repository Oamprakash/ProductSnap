package com.oamsapps.productsnap.ui

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
private enum class Example(val title: String, val avoid: String, val better: String) {
    Light("Light", "A dim room hides the item and darkens the cutout.", "Daylight from a window, no flash."),
    Background("Background", "Busy patterns blend into the item and confuse the cutout.", "A plain surface that contrasts with the item."),
    Framing("Framing", "Edges that touch the frame get cut off.", "The whole item, with space all around."),
    Angle("Angle", "Shooting at a slant distorts the shape.", "Lay it flat and shoot from straight above."),
}

private class Tip(val icon: ImageVector, val title: String, val body: String)

private val tips = listOf(
    Tip(Icons.Default.FlashOff, "Turn the flash off", "Flash makes glare and harsh shadows. Move closer to a window instead."),
    Tip(Icons.Default.CenterFocusStrong, "Hold still and tap to focus", "A sharp photo cuts out cleanly."),
    Tip(Icons.Default.Adjust, "Show flaws honestly", "Mark stains or tears with Flaws. Each gets its own close-up, so buyers know what they're getting."),
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
                    Text("Photos that sell", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    PaperIconButton(Icons.Default.Close, "Close tips", onDismiss)
                }
                Text(
                    "A good photo to start from means a clean cutout in one tap. Here's what to avoid and what works better.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Studio.Muted,
                )
                Example.entries.forEach { ExampleRow(it) }

                SectionLabel("More tips")
                tips.forEach { t ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Studio.Chip), contentAlignment = Alignment.Center) {
                            Icon(t.icon, null, Modifier.size(20.dp), tint = Studio.Ink)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(t.body, style = MaterialTheme.typography.bodyMedium, color = Studio.Muted)
                        }
                    }
                }
                InkButton("Got it", onDismiss, Modifier.fillMaxWidth())
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
        Text(example.title, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExampleTile(example, good = false, Modifier.weight(1f))
            ExampleTile(example, good = true, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExampleTile(example: Example, good: Boolean, modifier: Modifier) {
    val caption = if (good) example.better else example.avoid
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .semantics { contentDescription = (if (good) "Better: " else "Avoid: ") + caption },
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
                    Text(if (good) "Better" else "Avoid", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
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

