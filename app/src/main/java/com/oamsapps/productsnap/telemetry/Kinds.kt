package com.oamsapps.productsnap.telemetry

import com.oamsapps.productsnap.image.background.BackgroundStyle

/** Background type for events: the kind only, never colours or file paths. */
val BackgroundStyle.kind: String
    get() = when (this) {
        is BackgroundStyle.Solid -> if (isTransparent) "transparent" else "solid"
        is BackgroundStyle.Gradient -> "gradient"
        is BackgroundStyle.Studio -> "studio"
        is BackgroundStyle.Photo -> "photo"
        BackgroundStyle.Natural -> "natural"
    }
