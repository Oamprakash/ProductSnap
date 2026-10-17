package com.oamsapps.productsnap.editor

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.oamsapps.productsnap.image.erase.EraseStroke
import kotlin.math.roundToInt

val EraseBrushColor = Color(0xFFFF3B30)
val RestoreBrushColor = Color(0xFF34C759)
private const val MAX_ZOOM = 8f
private const val BUTTON_ZOOM_STEP = 1.5f

/**
 * Shows the photo and lets the user paint over what to remove.
 *
 * - One finger paints.
 * - Two fingers pinch to zoom and drag to move around. A stroke in progress is
 *   discarded the moment a second finger lands, so zooming never leaves stray paint.
 * - Zoom buttons for one-handed use.
 *
 * The brush keeps the same size on screen at any zoom, so zooming in gives finer strokes.
 * Strokes are reported in image pixel coordinates, independent of screen size and zoom.
 *
 * Also used to touch up a cutout: then [bitmap] is the cutout drawn over a checkerboard, [underlay] is the
 * original photo shown faintly beneath it, and [strokes] is empty because the cutout already shows them.
 */
@Composable
fun EraseCanvas(
    bitmap: Bitmap,
    strokes: List<EraseStroke>,
    brushSizeDp: Float,
    enabled: Boolean,
    isWorking: Boolean,
    onStroke: (EraseStroke) -> Unit,
    modifier: Modifier = Modifier,
    brushColor: Color = EraseBrushColor,
    underlay: Bitmap? = null,
    underlayAlpha: Float = 0.3f,
    checkerboard: Boolean = false,
    description: String = "Photo. Paint over the area to remove. Pinch to zoom.",
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val underImage = remember(underlay) { underlay?.asImageBitmap() }
    // Current stroke, in unzoomed view coordinates ("content" space).
    val current = remember { mutableStateListOf<Offset>() }
    val brushPx = with(LocalDensity.current) { brushSizeDp.dp.toPx() }
    val latestBrush by rememberUpdatedState(brushPx)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnStroke by rememberUpdatedState(onStroke)

    // Zoom survives an erase (the bitmap changes but keeps its size), so the user can keep working in place.
    var zoom by remember(bitmap.width, bitmap.height) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap.width, bitmap.height) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(Size.Zero) }

    fun clampPan(p: Offset, z: Float, s: Size) = Offset(
        p.x.coerceIn(s.width * (1 - z), 0f),
        p.y.coerceIn(s.height * (1 - z), 0f),
    )

    /** Zooms by [factor] keeping [focus] (a view point) fixed under the fingers, then pans. */
    fun transform(factor: Float, focus: Offset, panBy: Offset, s: Size) {
        val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val ratio = newZoom / zoom
        pan = clampPan(focus - (focus - pan) * ratio + panBy, newZoom, s)
        zoom = newZoom
    }

    fun toContent(view: Offset) = (view - pan) / zoom

    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .onSizeChanged { viewSize = it.toSize() }
            .semantics { contentDescription = description }
            .pointerInput(bitmap.width, bitmap.height) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!latestEnabled) return@awaitEachGesture
                    down.consume()
                    current.clear()
                    current.add(toContent(down.position))
                    var transforming = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break

                        if (pressed >= 2 || transforming) {
                            // Once a gesture becomes a zoom/pan it stays one until all fingers lift.
                            if (!transforming) {
                                transforming = true
                                current.clear()
                            }
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid != Offset.Unspecified) {
                                transform(event.calculateZoom(), centroid, event.calculatePan(), size.toSize())
                            }
                        } else {
                            val change = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                            current.add(toContent(change.position))
                        }
                        event.changes.forEach { it.consume() }
                    }

                    if (!transforming && current.isNotEmpty()) {
                        val toImage = bitmap.width / size.width.toFloat()
                        latestOnStroke(
                            EraseStroke(
                                points = current.map { PointF(it.x * toImage, it.y * toImage) },
                                // Same size on screen at any zoom → smaller in the image when zoomed in.
                                radius = latestBrush / 2f / zoom * toImage,
                            )
                        )
                    }
                    current.clear()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Fixed on screen (not zoomed): it only says "transparent here", and stays cheap at any zoom.
            if (checkerboard) drawCheckerboard(12.dp.toPx())
            withTransform({
                translate(pan.x, pan.y)
                scale(zoom, zoom, pivot = Offset.Zero)
            }) {
                val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                underImage?.let { drawImage(it, dstSize = dst, alpha = underlayAlpha, filterQuality = FilterQuality.Medium) }
                drawImage(
                    image,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        // Strokes are drawn opaque into their own layer, then the whole layer is made
        // translucent, so overlapping strokes don't get darker.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.55f
                    compositingStrategy = CompositingStrategy.Offscreen
                }
        ) {
            val toView = size.width / bitmap.width
            withTransform({
                translate(pan.x, pan.y)
                scale(zoom, zoom, pivot = Offset.Zero)
            }) {
                strokes.forEach { s ->
                    drawBrush(s.points.map { Offset(it.x * toView, it.y * toView) }, s.radius * toView, brushColor)
                }
                if (current.isNotEmpty()) drawBrush(current.toList(), brushPx / 2f / zoom, brushColor)
            }
        }

        if (isWorking) {
            Box(
                Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            ZoomButtons(
                zoom = zoom,
                onZoomIn = { transform(BUTTON_ZOOM_STEP, viewSize.center(), Offset.Zero, viewSize) },
                onZoomOut = { transform(1f / BUTTON_ZOOM_STEP, viewSize.center(), Offset.Zero, viewSize) },
                onFit = { zoom = 1f; pan = Offset.Zero },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
            if (zoom > 1.01f) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                ) {
                    Text(
                        "%.1f×".format(zoom),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomButtons(
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalIconButton(onClick = onZoomIn, enabled = zoom < MAX_ZOOM, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.ZoomIn, "Zoom in")
        }
        FilledTonalIconButton(onClick = onZoomOut, enabled = zoom > 1f, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.ZoomOut, "Zoom out")
        }
        if (zoom > 1.01f) {
            FilledTonalIconButton(onClick = onFit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.FitScreen, "Fit photo to screen")
            }
        }
    }
}

private fun Size.center() = Offset(width / 2f, height / 2f)

private fun DrawScope.drawBrush(points: List<Offset>, radius: Float, color: Color) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, radius, points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, color, style = Stroke(width = radius * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Light grey checks, the usual sign for "transparent here". */
private fun DrawScope.drawCheckerboard(cell: Float) {
    drawRect(Color(0xFFFFFFFF))
    val dark = Color(0xFFE3E3E3)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(dark, Offset(x, y), Size(cell, cell))
            x += cell * 2
        }
        y += cell
        row++
    }
}
