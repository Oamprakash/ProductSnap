package com.oamsapps.productsnap.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oamsapps.productsnap.image.ImageRenderer

/**
 * The photo, filling the box (the caller sizes it to the photo's shape). Tap to mark a flaw, tap a circle to
 * remove it. Reports taps as 0..1 of the photo.
 */
@Composable
fun FlawCanvas(bitmap: Bitmap, flaws: List<Flaw>, onTap: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Photo. Tap a flaw to circle it; tap a circle to remove it." }
            .pointerInput(bitmap) {
                detectTapGestures { p -> onTap(p.x / size.width, p.y / size.height) }
            },
    ) {
        Image(image, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height)
            flaws.forEach { f -> drawFlawRing(Offset(f.x * size.width, f.y * size.height), f.r * side) }
        }
    }
}

/** Same look as the exported ring (red with a white edge), for previews. */
fun DrawScope.drawFlawRing(center: Offset, r: Float) {
    val width = maxOf(3f, r * ImageRenderer.RING_WIDTH)
    drawCircle(Color.White, r, center, style = Stroke(width * 1.6f))
    drawCircle(Color(ImageRenderer.RING_COLOR), r, center, style = Stroke(width))
}
