package com.oamsapps.productsnap.image

import android.graphics.Bitmap
import com.oamsapps.productsnap.billing.PremiumManager
import com.oamsapps.productsnap.image.background.BackgroundPresets
import com.oamsapps.productsnap.image.background.BackgroundStyle
import kotlin.math.max

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
    /** Shape and size for a marketplace; overrides [squareCanvas]. */
    val marketplace: Marketplace? = null,
) {
    /** The real background, lit and framed around the product (see [BackgroundStyle.Natural]). */
    val isNatural get() = background == BackgroundStyle.Natural

    /** ML Kit runs for a cutout, for Natural (to frame the product), and for Auto light (to measure it). */
    val needsSegmentation get() = removeBackground || isNatural || lightStrength != LightStrength.OFF

    /** Keep the photo as it is (no cutout, no Natural framing); ML Kit may still run to measure the light. */
    val keepsPhoto get() = !removeBackground && !isNatural

    private val fillsBackground get() = !isNatural && (removeBackground || squareCanvas || marketplace != null)

    /** Every marketplace preset gets a filled background: Etsy shows transparency as black. */
    private val effectiveBackground get() =
        if (marketplace != null && background.isTransparent) BackgroundPresets.WHITE else background

    /** Transparent output must be PNG; JPEG would turn it black. */
    val transparent get() = fillsBackground && effectiveBackground.isTransparent

    /** The shape the result is framed to: a marketplace's, square, or the photo's own (null). */
    val canvasAspect: Float? get() = marketplace?.aspect ?: if (squareCanvas) 1f else null

    /** Decode size: large enough for the marketplace size, so Etsy's 2667 px isn't an enlargement. */
    val maxEdge get() = max(PremiumManager.EXPORT_MAX_EDGE, marketplace?.let { max(it.width, it.height) } ?: 0)

    val jpegQuality get() = marketplace?.jpegQuality ?: 95

    /**
     * [content] is the cutout (or the photo), except for Natural, where it's the photo already cropped to
     * [canvasAspect] around the product (see Cropper.natural); it's then only scaled.
     */
    fun renderSpec(content: Bitmap, backgroundPhoto: Bitmap? = null) = RenderSpec(
        content = content,
        adjustments = adjustments,
        background = if (fillsBackground) effectiveBackground else null,
        backgroundPhoto = backgroundPhoto,
        canvasAspect = if (isNatural) null else canvasAspect,
        maxOutputEdge = maxEdge,
        outWidth = marketplace?.width,
        outHeight = marketplace?.height,
        watermark = if (premium) null else PremiumManager.WATERMARK,
    )
}
