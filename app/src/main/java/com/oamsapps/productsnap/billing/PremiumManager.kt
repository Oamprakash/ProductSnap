package com.oamsapps.productsnap.billing

import com.oamsapps.productsnap.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place the app asks "is this user premium?". [Billing] keeps it up to date.
 * Free: 1080px + watermark, 3 photos per batch.
 * Premium: full res, no watermark, 30 photos per batch.
 */
object PremiumManager {
    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    const val FREE_MAX_EDGE = 1080
    const val PREMIUM_MAX_EDGE = 2048
    const val WATERMARK = "ProductSnap"

    const val FREE_BATCH_LIMIT = 3
    const val PREMIUM_BATCH_LIMIT = 30

    internal fun setPremium(premium: Boolean) {
        _isPremium.value = premium
    }

    /** Debug builds only, for testing premium flows without a purchase. */
    fun debugUnlock() {
        if (BuildConfig.DEBUG) _isPremium.value = true
    }
}
