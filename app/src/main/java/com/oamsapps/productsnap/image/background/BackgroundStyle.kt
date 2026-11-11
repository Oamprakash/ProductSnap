package com.oamsapps.productsnap.image.background

import androidx.annotation.StringRes
import com.oamsapps.productsnap.R
import android.graphics.Color
import org.json.JSONObject

/** What goes behind the product. Colours are ARGB ints. */
sealed interface BackgroundStyle {
    data class Solid(val color: Int) : BackgroundStyle
    /** Top-to-bottom gradient. */
    data class Gradient(val top: Int, val bottom: Int) : BackgroundStyle
    /** Soft spotlight: bright just above centre, falling off to [edge]. */
    data class Studio(val center: Int, val edge: Int) : BackgroundStyle
    /** The user's own photo, stored in app storage at [path]. */
    data class Photo(val path: String, val blur: Boolean) : BackgroundStyle
    /**
     * The seller's own background, as photographed: only lighting and framing change. The cutout is still made,
     * to centre the crop on the product and to measure the light.
     */
    data object Natural : BackgroundStyle

    val isTransparent get() = this is Solid && Color.alpha(color) == 0
    val isPlainColor get() = this is Solid
}

data class BackgroundPreset(@StringRes val label: Int, val style: BackgroundStyle)

object BackgroundPresets {
    val WHITE = BackgroundStyle.Solid(0xFFFFFFFF.toInt())

    val solids = listOf(
        BackgroundPreset(R.string.bg_white, WHITE),
        BackgroundPreset(R.string.bg_light_grey, BackgroundStyle.Solid(0xFFF2F2F2.toInt())),
        BackgroundPreset(R.string.bg_cream, BackgroundStyle.Solid(0xFFFFF4E0.toInt())),
        BackgroundPreset(R.string.bg_pale_blue, BackgroundStyle.Solid(0xFFE6F0FF.toInt())),
        BackgroundPreset(R.string.bg_charcoal, BackgroundStyle.Solid(0xFF1F1F1F.toInt())),
        BackgroundPreset(R.string.bg_transparent, BackgroundStyle.Solid(0x00000000)),
    )

    val scenes = listOf(
        BackgroundPreset(R.string.bg_studio_light, BackgroundStyle.Studio(0xFFFFFFFF.toInt(), 0xFFD9DCE1.toInt())),
        BackgroundPreset(R.string.bg_warm_studio, BackgroundStyle.Studio(0xFFFFF6EA.toInt(), 0xFFE3CDB5.toInt())),
        BackgroundPreset(R.string.bg_dark_studio, BackgroundStyle.Studio(0xFF4A4F5A.toInt(), 0xFF16181D.toInt())),
        BackgroundPreset(R.string.bg_blush, BackgroundStyle.Gradient(0xFFFFE5EC.toInt(), 0xFFFFC2D1.toInt())),
        BackgroundPreset(R.string.bg_sky, BackgroundStyle.Gradient(0xFFE0F2FF.toInt(), 0xFFFFFFFF.toInt())),
        BackgroundPreset(R.string.bg_mint, BackgroundStyle.Gradient(0xFFE3F9F1.toInt(), 0xFFC7EFE0.toInt())),
        BackgroundPreset(R.string.bg_sunset, BackgroundStyle.Gradient(0xFFFFE7C2.toInt(), 0xFFFFB997.toInt())),
    )
}

fun BackgroundStyle.toJson(): JSONObject = JSONObject().apply {
    when (val s = this@toJson) {
        is BackgroundStyle.Solid -> { put("type", "solid"); put("color", s.color) }
        is BackgroundStyle.Gradient -> { put("type", "gradient"); put("top", s.top); put("bottom", s.bottom) }
        is BackgroundStyle.Studio -> { put("type", "studio"); put("center", s.center); put("edge", s.edge) }
        is BackgroundStyle.Photo -> { put("type", "photo"); put("path", s.path); put("blur", s.blur) }
        BackgroundStyle.Natural -> put("type", "natural")
    }
}

fun backgroundFromJson(o: JSONObject?): BackgroundStyle? = runCatching {
    when (o?.getString("type")) {
        "solid" -> BackgroundStyle.Solid(o.getInt("color"))
        "gradient" -> BackgroundStyle.Gradient(o.getInt("top"), o.getInt("bottom"))
        "studio" -> BackgroundStyle.Studio(o.getInt("center"), o.getInt("edge"))
        "photo" -> BackgroundStyle.Photo(o.getString("path"), o.optBoolean("blur", true))
        "natural" -> BackgroundStyle.Natural
        else -> null
    }
}.getOrNull()
