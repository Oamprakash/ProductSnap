package com.oamsapps.productsnap.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.toSize

/** Zoom and pan for a view whose content fills it at zoom 1. */
@Stable
class ZoomState(private val maxZoom: Float = 5f) {
    var zoom by mutableFloatStateOf(1f)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set

    val isZoomed get() = zoom > 1.01f

    /** Zooms by [factor] keeping [focus] fixed on screen, then pans by [panBy]; never shows past the edges. */
    fun transform(factor: Float, focus: Offset, panBy: Offset, size: Size) {
        val newZoom = (zoom * factor).coerceIn(1f, maxZoom)
        val raw = focus - (focus - pan) * (newZoom / zoom) + panBy
        pan = Offset(
            raw.x.coerceIn(size.width * (1 - newZoom), 0f),
            raw.y.coerceIn(size.height * (1 - newZoom), 0f),
        )
        zoom = newZoom
    }

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }
}

/**
 * Pinch to zoom, drag to pan while zoomed, double-tap to toggle.
 *
 * At zoom 1 a single-finger drag is left unconsumed, so a surrounding scrolling
 * column still scrolls normally.
 */
fun Modifier.pinchToZoom(state: ZoomState, doubleTapZoom: Float = 2.5f): Modifier = this
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed >= 2 || state.isZoomed) {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid.isSpecified) {
                        state.transform(event.calculateZoom(), centroid, event.calculatePan(), size.toSize())
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        }
    }
    .pointerInput(state) {
        detectTapGestures(
            onDoubleTap = { p ->
                if (state.isZoomed) state.reset()
                else state.transform(doubleTapZoom, p, Offset.Zero, size.toSize())
            },
        )
    }

/** Applies [state] to the content it's placed on. */
fun Modifier.zoomTransform(state: ZoomState): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = state.zoom
    scaleY = state.zoom
    translationX = state.pan.x
    translationY = state.pan.y
}
