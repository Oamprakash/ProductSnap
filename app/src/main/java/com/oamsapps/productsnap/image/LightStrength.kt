package com.oamsapps.productsnap.image

import com.oamsapps.imaging.AutoLight
import com.oamsapps.imaging.LightFix

/**
 * How far Auto light may brighten a photo taken in too little light. Normally exposed photos get the same
 * gentle fix (at most 1.6x) at every level except Off.
 */
enum class LightStrength(val label: String, val dimCap: Float) {
    OFF("Off", 1f),
    GENTLE("Gentle", 1.6f),
    NORMAL("Normal", AutoLight.DEFAULT_DIM_CAP),
    STRONG("Strong", 2.8f);

    /** The fix to apply for this strength, or [LightFix.NONE] when off. */
    fun apply(measured: LightFix): LightFix =
        if (this == OFF || measured == LightFix.NONE) LightFix.NONE else AutoLight.withDimCap(measured, dimCap)

    companion object {
        val DEFAULT = NORMAL
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
