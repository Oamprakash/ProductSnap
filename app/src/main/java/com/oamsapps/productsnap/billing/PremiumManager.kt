package com.oamsapps.productsnap.billing

import com.oamsapps.productsnap.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place the app asks "is this user premium?". [Billing] keeps it up to date.
 * Free: one finished photo, full quality with a watermark, then the paywall ([FreeAllowance]).
 * Premium: unlimited saves, no watermark, batches of up to 30 photos.
 */
object PremiumManager {
    /** Testing period: everyone gets Premium and the paywall stays out of the way (see ALL_FREE in build.gradle). */
    val allFree: Boolean = BuildConfig.ALL_FREE

    private val _isPremium = MutableStateFlow(allFree)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    /** Every save is full quality; free differs only by the watermark and the count. */
    const val EXPORT_MAX_EDGE = 2048
    const val WATERMARK = "ProductSnap"

    const val FREE_RESULTS = 1
    const val PREMIUM_BATCH_LIMIT = 30

    internal fun setPremium(premium: Boolean) {
        _isPremium.value = premium || allFree
    }

    /** Debug builds only, for testing premium flows without a purchase. */
    fun debugUnlock() {
        if (BuildConfig.DEBUG) _isPremium.value = true
    }
}
