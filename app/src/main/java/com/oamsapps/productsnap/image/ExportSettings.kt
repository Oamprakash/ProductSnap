package com.oamsapps.productsnap.image

import android.graphics.Bitmap
import com.oamsapps.productsnap.billing.PremiumManager
import com.oamsapps.productsnap.image.background.BackgroundStyle

/** One source of truth for how a photo is exported. Used by the editor and by batch. */
data class ExportSettings(
    val removeBackground: Boolean,
    val background: BackgroundStyle,
    val squareCanvas: Boolean,
    val adjustments: Adjustments,
    val premium: Boolean,
    val crop: BatchCrop = BatchCrop.NONE,   // batch only; the editor crops the photo itself
    val shadow: Boolean = false,            // batch only; the editor bakes it into its preview bitmap
    val lightStrength: LightStrength = LightStrength.OFF, // batch only; the editor passes the light in its adjustments
) {
    private val fillsBackground get() = removeBackground || squareCanvas

    /** Transparent output must be PNG; JPEG would turn it black. */
    val transparent get() = fillsBackground && background.isTransparent

    val maxEdge get() = PremiumManager.EXPORT_MAX_EDGE

    fun renderSpec(content: Bitmap, backgroundPhoto: Bitmap? = null) = RenderSpec(
        content = content,
        adjustments = adjustments,
        background = if (fillsBackground) background else null,
        backgroundPhoto = backgroundPhoto,
        squareCanvas = squareCanvas,
        maxOutputEdge = maxEdge,
        watermark = if (premium) null else PremiumManager.WATERMARK,
    )
}
