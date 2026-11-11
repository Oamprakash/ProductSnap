package com.oamsapps.productsnap.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.oamsapps.productsnap.R
import com.oamsapps.productsnap.image.BatchCrop
import com.oamsapps.productsnap.image.LightStrength

/** Names shown for settings, in the phone's language. The enums themselves stay language-free. */
@Composable
fun LightStrength.text(): String = stringResource(
    when (this) {
        LightStrength.OFF -> R.string.off
        LightStrength.GENTLE -> R.string.light_gentle
        LightStrength.NORMAL -> R.string.light_normal
        LightStrength.STRONG -> R.string.light_strong
    }
)

@Composable
fun BatchCrop.text(): String = when (this) {
    BatchCrop.NONE -> stringResource(R.string.off)
    BatchCrop.TIGHT -> stringResource(R.string.crop_fit_product)
    else -> label
}
