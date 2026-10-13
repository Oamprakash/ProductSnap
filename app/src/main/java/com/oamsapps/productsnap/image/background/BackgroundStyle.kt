package com.oamsapps.productsnap.image.background

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

    val isTransparent get() = this is Solid && Color.alpha(color) == 0
    val isPlainColor get() = this is Solid
}

data class BackgroundPreset(val label: String, val style: BackgroundStyle)

object BackgroundPresets {
    val WHITE = BackgroundStyle.Solid(0xFFFFFFFF.toInt())

    val solids = listOf(
        BackgroundPreset("White", WHITE),
        BackgroundPreset("Light grey", BackgroundStyle.Solid(0xFFF2F2F2.toInt())),
        BackgroundPreset("Cream", BackgroundStyle.Solid(0xFFFFF4E0.toInt())),
        BackgroundPreset("Pale blue", BackgroundStyle.Solid(0xFFE6F0FF.toInt())),
        BackgroundPreset("Charcoal", BackgroundStyle.Solid(0xFF1F1F1F.toInt())),
        BackgroundPreset("Transparent", BackgroundStyle.Solid(0x00000000)),
    )

    val scenes = listOf(
        BackgroundPreset("Studio light", BackgroundStyle.Studio(0xFFFFFFFF.toInt(), 0xFFD9DCE1.toInt())),
        BackgroundPreset("Warm studio", BackgroundStyle.Studio(0xFFFFF6EA.toInt(), 0xFFE3CDB5.toInt())),
        BackgroundPreset("Dark studio", BackgroundStyle.Studio(0xFF4A4F5A.toInt(), 0xFF16181D.toInt())),
        BackgroundPreset("Blush gradient", BackgroundStyle.Gradient(0xFFFFE5EC.toInt(), 0xFFFFC2D1.toInt())),
        BackgroundPreset("Sky gradient", BackgroundStyle.Gradient(0xFFE0F2FF.toInt(), 0xFFFFFFFF.toInt())),
        BackgroundPreset("Mint gradient", BackgroundStyle.Gradient(0xFFE3F9F1.toInt(), 0xFFC7EFE0.toInt())),
        BackgroundPreset("Sunset gradient", BackgroundStyle.Gradient(0xFFFFE7C2.toInt(), 0xFFFFB997.toInt())),
    )
}

fun BackgroundStyle.toJson(): JSONObject = JSONObject().apply {
    when (val s = this@toJson) {
        is BackgroundStyle.Solid -> { put("type", "solid"); put("color", s.color) }
        is BackgroundStyle.Gradient -> { put("type", "gradient"); put("top", s.top); put("bottom", s.bottom) }
        is BackgroundStyle.Studio -> { put("type", "studio"); put("center", s.center); put("edge", s.edge) }
        is BackgroundStyle.Photo -> { put("type", "photo"); put("path", s.path); put("blur", s.blur) }
    }
}

fun backgroundFromJson(o: JSONObject?): BackgroundStyle? = runCatching {
    when (o?.getString("type")) {
        "solid" -> BackgroundStyle.Solid(o.getInt("color"))
        "gradient" -> BackgroundStyle.Gradient(o.getInt("top"), o.getInt("bottom"))
        "studio" -> BackgroundStyle.Studio(o.getInt("center"), o.getInt("edge"))
        "photo" -> BackgroundStyle.Photo(o.getString("path"), o.optBoolean("blur", true))
        else -> null
    }
}.getOrNull()
