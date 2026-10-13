package com.oamsapps.productsnap.image

import android.graphics.ColorMatrix

/** Slider values. 0f = neutral for all three. Range -1f..1f. */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
) {
    /** Single matrix used by BOTH live preview and export, so they always match. */
    fun toColorMatrix(): ColorMatrix {
        val result = ColorMatrix()

        result.postConcat(ColorMatrix().apply { setSaturation(1f + saturation) })

        val c = 1f + contrast            // 0..2
        val t = (1f - c) * 127.5f        // keep mid-grey fixed
        result.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )

        val b = brightness * 100f
        result.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, b,
                    0f, 1f, 0f, 0f, b,
                    0f, 0f, 1f, 0f, b,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return result
    }

    val isNeutral get() = brightness == 0f && contrast == 0f && saturation == 0f
}
