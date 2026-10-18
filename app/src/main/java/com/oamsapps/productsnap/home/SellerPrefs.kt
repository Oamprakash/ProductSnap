package com.oamsapps.productsnap.home

import android.content.Context
import com.oamsapps.productsnap.image.Marketplace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the seller lists, asked once on first launch. null in [markets] means "somewhere else" (any shape). */
data class Seller(
    val markets: List<Marketplace?> = emptyList(),
    /** The marketplace new photos and batches are made for; null = any shape. */
    val primary: Marketplace? = null,
    val onboarded: Boolean = false,
)

object SellerPrefs {
    private const val PREFS = "seller"
    private const val KEY_MARKETS = "markets"
    private const val KEY_PRIMARY = "primary"
    private const val KEY_ONBOARDED = "onboarded"
    private const val ANY = "ANY"

    private val _seller = MutableStateFlow(Seller())
    val seller: StateFlow<Seller> = _seller.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val markets = p.getString(KEY_MARKETS, "").orEmpty().split(',').filter { it.isNotBlank() }.map { decode(it) }
        _seller.value = Seller(
            markets = markets,
            primary = p.getString(KEY_PRIMARY, null)?.let { decode(it) },
            onboarded = p.getBoolean(KEY_ONBOARDED, false),
        )
    }

    /** Saves the onboarding choice; the first marketplace picked becomes the one new photos are made for. */
    fun saveMarkets(context: Context, markets: List<Marketplace?>) {
        val current = _seller.value.primary
        val primary = if (current in markets && markets.isNotEmpty()) current else markets.firstOrNull { it != null }
        write(context, Seller(markets, primary, onboarded = true))
    }

    fun setPrimary(context: Context, m: Marketplace?) {
        val s = _seller.value
        write(context, s.copy(primary = m, markets = if (m in s.markets) s.markets else s.markets + m))
    }

    private fun write(context: Context, s: Seller) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MARKETS, s.markets.joinToString(",") { encode(it) })
            .putString(KEY_PRIMARY, encode(s.primary))
            .putBoolean(KEY_ONBOARDED, s.onboarded)
            .apply()
        _seller.value = s
    }

    private fun encode(m: Marketplace?) = m?.name ?: ANY
    private fun decode(s: String): Marketplace? = if (s == ANY) null else Marketplace.fromName(s)
}
