package com.oamsapps.productsnap.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.oamsapps.productsnap.ui.theme.Studio
import kotlin.math.roundToInt

/**
 * First-use tips: a small speech bubble that points at a feature the first time the user reaches it, says what
 * it's for and how to use it, and never shows again once dismissed. One bubble at a time; when several features
 * appear together the lowest [order] goes first.
 *
 * Mark a feature with [Modifier.firstUseTip] anywhere under [FirstUseTipHost]. The tip shows only while that
 * feature is on screen, so "first use" is simply the first time the feature appears.
 */
class FirstUseTips internal constructor(private val prefs: SharedPreferences) {
    private val seen = mutableStateListOf<String>().apply { addAll(prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()) }
    internal val targets = mutableStateMapOf<String, Target>()

    internal class Target(val key: String, val title: String, val body: String, val order: Int) {
        var bounds by mutableStateOf(Rect.Zero)
    }

    internal fun isSeen(key: String) = key in seen

    /** The tip to show now: unseen, on screen and laid out, lowest order first. */
    internal val current: Target?
        get() = targets.values.filter { it.key !in seen && it.bounds.width > 0f }.minByOrNull { it.order }

    internal fun dismiss(key: String) {
        if (key in seen) return
        seen += key
        prefs.edit().putStringSet(KEY_SEEN, seen.toSet()).apply()
    }

    private companion object {
        const val KEY_SEEN = "seen"
    }
}

private val LocalFirstUseTips = staticCompositionLocalOf<FirstUseTips?> { null }

@Composable
fun rememberFirstUseTips(): FirstUseTips {
    val context = LocalContext.current
    return remember { FirstUseTips(context.getSharedPreferences("first_use_tips", Context.MODE_PRIVATE)) }
}

/** Draws the current tip over [content]. Put it once at the root of the app. */
@Composable
fun FirstUseTipHost(tips: FirstUseTips, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFirstUseTips provides tips) {
        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
            content()
            tips.current?.let { target ->
                TipBubble(target, origin, onDismiss = { tips.dismiss(target.key) })
            }
        }
    }
}

/**
 * Marks this element as a feature with a first-use tip. [active] = the feature is actually in use right now
 * (for example, the cutout exists), so the tip appears at the right moment.
 */
@Composable
fun Modifier.firstUseTip(key: String, title: String, body: String, order: Int = 0, active: Boolean = true): Modifier {
    val tips = LocalFirstUseTips.current ?: return this
    if (!active || tips.isSeen(key)) return this
    val target = remember(key) { FirstUseTips.Target(key, title, body, order) }
    DisposableEffect(key) {
        tips.targets[key] = target
        onDispose { tips.targets.remove(key) }
    }
    return this.onGloballyPositioned { target.bounds = it.boundsInRoot() }
}

private val Margin = 16.dp
private val Gap = 4.dp
private val ArrowW = 18.dp
private val ArrowH = 9.dp
private val MaxWidth = 320.dp

@Composable
private fun TipBubble(target: FirstUseTips.Target, origin: Offset, onDismiss: () -> Unit) {
    val alpha = remember(target.key) { Animatable(0f) }
    LaunchedEffect(target.key) { alpha.animateTo(1f, tween(220)) }
    // Keep the bubble clear of the status bar, navigation bar and keyboard.
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    val topInset = insets.getTop(density)
    val bottomInset = insets.getBottom(density)

    Layout(
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
        content = {
            BubbleCard(target.title, target.body, onDismiss)
            Arrow(up = true)
            Arrow(up = false)
        },
    ) { measurables, constraints ->
        val margin = Margin.roundToPx()
        val gap = Gap.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bubbleMax = minOf(width - 2 * margin, MaxWidth.roundToPx()).coerceAtLeast(0)
        val bubble = measurables[0].measure(Constraints(maxWidth = bubbleMax))
        val up = measurables[1].measure(Constraints())
        val down = measurables[2].measure(Constraints())

        val b = target.bounds.translate(-origin.x, -origin.y)
        val cx = b.center.x
        // The host fills the whole window (edge to edge), so system bar insets apply directly.
        val minY = topInset + margin
        val maxY = height - bottomInset - margin
        // Below the feature when it fits above the navigation bar, otherwise above it.
        val below = b.bottom + gap + up.height + bubble.height <= maxY
        val x = (cx - bubble.width / 2f).roundToInt().coerceIn(margin, (width - bubble.width - margin).coerceAtLeast(margin))
        val corner = 16.dp.roundToPx()
        val arrowX = (cx - up.width / 2f).roundToInt()
            .coerceIn(x + corner, (x + bubble.width - corner - up.width).coerceAtLeast(x + corner))

        layout(width, height) {
            if (below) {
                val arrowY = b.bottom.roundToInt() + gap
                up.place(arrowX, arrowY)
                bubble.place(x, arrowY + up.height - 1)
            } else {
                val bubbleY = (b.top.roundToInt() - gap - down.height - bubble.height).coerceAtLeast(minY)
                bubble.place(x, bubbleY)
                down.place(arrowX, bubbleY + bubble.height - 1)
            }
        }
    }
}

@Composable
private fun BubbleCard(title: String, body: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Studio.Ink,
        contentColor = Studio.Paper,
        shadowElevation = 10.dp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Default.Lightbulb, null, Modifier.size(18.dp), tint = Studio.Accent)
                Text(title, style = MaterialTheme.typography.titleSmall, color = Studio.Paper)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Studio.StageText.copy(alpha = 0.86f), modifier = Modifier.padding(end = 8.dp))
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Got it", color = Studio.Accent, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
private fun Arrow(up: Boolean) {
    Canvas(Modifier.size(ArrowW, ArrowH)) {
        val path = Path().apply {
            if (up) {
                moveTo(0f, size.height); lineTo(size.width / 2f, 0f); lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f); lineTo(size.width / 2f, size.height); lineTo(size.width, 0f)
            }
            close()
        }
        drawPath(path, Studio.Ink)
    }
}
