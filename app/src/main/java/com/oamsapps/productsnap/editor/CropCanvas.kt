package com.oamsapps.productsnap.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Crop rect corners and edges, named by which sides of the rect they move. */
private enum class Handle(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    TopLeft(true, true, false, false),
    TopRight(false, true, true, false),
    BottomLeft(true, false, false, true),
    BottomRight(false, false, true, true),
    Left(true, false, false, false),
    Top(false, true, false, false),
    Right(false, false, true, false),
    Bottom(false, false, false, true),
    Move(false, false, false, false);

    val isCorner get() = (left || right) && (top || bottom)
}

/**
 * Photo with a crop frame. Drag corners to resize, edges to resize one side
 * (free aspect only), or the middle to move. [rect] is normalised (0..1).
 * [aspect] is width / height in image pixels, or null for free.
 */
@Composable
fun CropCanvas(
    bitmap: Bitmap,
    rect: Rect,
    aspect: Float?,
    onRectChange: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val touchSlop = with(density) { 28.dp.toPx() }
    val minSize = with(density) { 48.dp.toPx() }
    val cornerLen = with(density) { 20.dp.toPx() }
    val cornerWidth = with(density) { 3.dp.toPx() }
    val lineWidth = with(density) { 1.dp.toPx() }
    val latestRect by rememberUpdatedState(rect)
    val latestAspect by rememberUpdatedState(aspect)
    val latestOnChange by rememberUpdatedState(onRectChange)

    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Crop frame. Drag corners to resize or the middle to move." }
            .pointerInput(bitmap.width, bitmap.height) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val view = size.toSize()
                    val start = latestRect.toView(view)
                    val handle = hitTest(down.position, start, touchSlop, allowEdges = latestAspect == null)
                        ?: return@awaitEachGesture // outside the frame: let the page scroll
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val next = when {
                            handle == Handle.Move -> moved(start, change.position - down.position, view)
                            handle.isCorner && latestAspect != null ->
                                resizedLocked(start, handle, change.position, latestAspect!!, view, minSize)
                            else -> resizedFree(start, handle, change.position, view, minSize)
                        }
                        latestOnChange(next.toNormalized(view))
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawImage(
                image,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )

            val r = rect.toView(size)
            val shade = Color.Black.copy(alpha = 0.55f)
            // Dim everything outside the frame.
            drawRect(shade, Offset.Zero, Size(size.width, r.top))
            drawRect(shade, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
            drawRect(shade, Offset(0f, r.top), Size(r.left, r.height))
            drawRect(shade, Offset(r.right, r.top), Size(size.width - r.right, r.height))

            // Rule-of-thirds guides and frame.
            val guide = Color.White.copy(alpha = 0.5f)
            for (i in 1..2) {
                val x = r.left + r.width * i / 3f
                val y = r.top + r.height * i / 3f
                drawLine(guide, Offset(x, r.top), Offset(x, r.bottom), lineWidth)
                drawLine(guide, Offset(r.left, y), Offset(r.right, y), lineWidth)
            }
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(lineWidth * 1.5f))

            // Corner handles.
            val len = min(cornerLen, min(r.width, r.height) / 2f)
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(Color.White, Offset(x, y), Offset(x + dx * len, y), cornerWidth)
                drawLine(Color.White, Offset(x, y), Offset(x, y + dy * len), cornerWidth)
            }
            corner(r.left, r.top, 1f, 1f)
            corner(r.right, r.top, -1f, 1f)
            corner(r.left, r.bottom, 1f, -1f)
            corner(r.right, r.bottom, -1f, -1f)
        }
    }
}

private fun Rect.toView(s: Size) = Rect(left * s.width, top * s.height, right * s.width, bottom * s.height)
private fun Rect.toNormalized(s: Size) = Rect(left / s.width, top / s.height, right / s.width, bottom / s.height)

private fun hitTest(p: Offset, r: Rect, slop: Float, allowEdges: Boolean): Handle? {
    val corners = listOf(
        Handle.TopLeft to r.topLeft,
        Handle.TopRight to r.topRight,
        Handle.BottomLeft to r.bottomLeft,
        Handle.BottomRight to r.bottomRight,
    )
    corners.minByOrNull { (_, c) -> hypot(p.x - c.x, p.y - c.y) }
        ?.takeIf { (_, c) -> hypot(p.x - c.x, p.y - c.y) <= slop }
        ?.let { return it.first }

    if (allowEdges) {
        val withinY = p.y in (r.top - slop)..(r.bottom + slop)
        val withinX = p.x in (r.left - slop)..(r.right + slop)
        if (withinY && abs(p.x - r.left) <= slop) return Handle.Left
        if (withinY && abs(p.x - r.right) <= slop) return Handle.Right
        if (withinX && abs(p.y - r.top) <= slop) return Handle.Top
        if (withinX && abs(p.y - r.bottom) <= slop) return Handle.Bottom
    }
    return if (r.contains(p)) Handle.Move else null
}

private fun moved(start: Rect, delta: Offset, view: Size): Rect {
    val dx = delta.x.coerceIn(-start.left, view.width - start.right)
    val dy = delta.y.coerceIn(-start.top, view.height - start.bottom)
    return start.translate(dx, dy)
}

private fun resizedFree(start: Rect, h: Handle, p: Offset, view: Size, minSize: Float): Rect {
    val minW = min(minSize, view.width)
    val minH = min(minSize, view.height)
    var left = start.left; var top = start.top; var right = start.right; var bottom = start.bottom
    if (h.left) left = p.x.coerceIn(0f, right - minW)
    if (h.right) right = p.x.coerceIn(left + minW, view.width)
    if (h.top) top = p.y.coerceIn(0f, bottom - minH)
    if (h.bottom) bottom = p.y.coerceIn(top + minH, view.height)
    return Rect(left, top, right, bottom)
}

/** Corner drag with a fixed aspect: the opposite corner stays put. */
private fun resizedLocked(start: Rect, h: Handle, p: Offset, aspect: Float, view: Size, minSize: Float): Rect {
    val anchorX = if (h.left) start.right else start.left
    val anchorY = if (h.top) start.bottom else start.top
    val dirX = if (h.left) -1f else 1f
    val dirY = if (h.top) -1f else 1f

    val maxW = if (dirX > 0) view.width - anchorX else anchorX
    val maxH = if (dirY > 0) view.height - anchorY else anchorY
    val wantW = max(dirX * (p.x - anchorX), dirY * (p.y - anchorY) * aspect)
    val minW = min(max(minSize, minSize * aspect), min(maxW, maxH * aspect))
    val w = wantW.coerceIn(minW, min(maxW, maxH * aspect))
    val hgt = w / aspect

    val left = if (dirX > 0) anchorX else anchorX - w
    val top = if (dirY > 0) anchorY else anchorY - hgt
    return Rect(left, top, left + w, top + hgt)
}
