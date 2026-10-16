package com.oamsapps.productsnap.billing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The free tier: one finished photo, watermarked, then the paywall. Processing and previews stay free so a
 * seller can judge the quality on their own photos first.
 *
 * Stored locally, so a reinstall resets it; tie it to the Play account (or Play Integrity) before public launch
 * if that turns out to matter.
 */
object FreeAllowance {
    private const val PREFS = "free_allowance"
    private const val KEY_USED = "free_results_used"

    private val _available = MutableStateFlow(true)
    /** True while the free result hasn't been used yet. Premium users never need it. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    fun init(context: Context) {
        _available.value = used(context) < PremiumManager.FREE_RESULTS
    }

    /** Call after a non-premium photo has actually been saved. */
    fun markUsed(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val used = prefs.getInt(KEY_USED, 0) + 1
        prefs.edit().putInt(KEY_USED, used).apply()
        _available.value = used < PremiumManager.FREE_RESULTS
    }

    /** Photos a free user may still save; premium is unlimited and never calls this. */
    fun remaining(): Int = if (_available.value) PremiumManager.FREE_RESULTS else 0

    private fun used(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_USED, 0)
}
