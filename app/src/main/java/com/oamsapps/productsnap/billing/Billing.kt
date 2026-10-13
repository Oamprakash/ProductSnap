package com.oamsapps.productsnap.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

/** One way to pay: a base plan, with its free trial if the user is eligible. */
data class Plan(
    val basePlanId: String,
    val offerToken: String,
    val price: String,             // e.g. "₹999.00", already formatted by Play
    val billingPeriod: String,     // ISO 8601, e.g. P1M, P1Y
    val trialPeriod: String?,      // ISO 8601, e.g. P7D; null if no trial offer
)

data class BillingState(
    val isConnected: Boolean = false,
    val isLoading: Boolean = true,
    val plans: List<Plan> = emptyList(),
    val isPending: Boolean = false,
    val error: String? = null,
)

/**
 * Google Play Billing for the "Premium" subscription.
 *
 * - Connects on app start and reconnects with backoff.
 * - Loads plans (base plans + trial offers) from Play.
 * - Treats the user as premium while Play reports an active, purchased subscription;
 *   caches that so the app starts in the right state offline.
 * - Acknowledges new purchases (Play refunds unacknowledged ones after 3 days).
 *
 * TODO before scaling: verify purchases on a server (Play Developer API) and use
 * obfuscatedAccountId; the on-device check is fine for launch but can be bypassed on rooted phones.
 */
object Billing : PurchasesUpdatedListener {

    /** Subscription product ID; must match Play Console. */
    const val PRODUCT_ID = "productsnap_premium"

    private const val TAG = "Billing"
    private const val PREFS = "billing"
    private const val KEY_PREMIUM = "premium_cached"

    private lateinit var appContext: Context
    private lateinit var client: BillingClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var productDetails: ProductDetails? = null
    private var retryAttempt = 0

    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()

    fun init(context: Context) {
        if (::client.isInitialized) return
        appContext = context.applicationContext
        // Last known answer first, so a premium user offline doesn't see watermarks.
        PremiumManager.setPremium(prefs().getBoolean(KEY_PREMIUM, false))
        client = BillingClient.newBuilder(appContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect()
    }

    private fun connect() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    retryAttempt = 0
                    _state.update { it.copy(isConnected = true, error = null) }
                    scope.launch {
                        loadPlans()
                        refreshPurchases()
                    }
                } else {
                    Log.w(TAG, "Setup failed: ${result.debugMessage}")
                    _state.update { it.copy(isConnected = false, isLoading = false, error = "Google Play billing isn't available right now.") }
                    scheduleReconnect()
                }
            }

            override fun onBillingServiceDisconnected() {
                _state.update { it.copy(isConnected = false) }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        val wait = min(60_000L, 1_000L shl min(retryAttempt, 6))
        retryAttempt++
        scope.launch {
            delay(wait)
            connect()
        }
    }

    /** Call when the app comes back to the foreground: catches renewals, cancellations and refunds. */
    fun refresh() {
        if (!::client.isInitialized) return
        if (!client.isReady) { connect(); return }
        scope.launch {
            if (_state.value.plans.isEmpty()) loadPlans()
            refreshPurchases()
        }
    }

    private suspend fun loadPlans() {
        _state.update { it.copy(isLoading = true) }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        val result = client.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull()
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK || details == null) {
            Log.w(TAG, "No product details: ${result.billingResult.debugMessage}")
            _state.update { it.copy(isLoading = false, plans = emptyList(), error = "Premium plans aren't available right now. Check your connection and try again.") }
            return
        }
        productDetails = details
        _state.update { it.copy(isLoading = false, plans = toPlans(details), error = null) }
    }

    /**
     * One plan per base plan. Play only returns offers the user is eligible for, so if a
     * free-trial offer is present we show it; otherwise the plain base plan.
     */
    private fun toPlans(details: ProductDetails): List<Plan> =
        details.subscriptionOfferDetails.orEmpty()
            .groupBy { it.basePlanId }
            .mapNotNull { (basePlanId, offers) ->
                val chosen = offers.firstOrNull { offer ->
                    offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
                } ?: offers.firstOrNull { it.offerId == null } ?: offers.first()
                val phases = chosen.pricingPhases.pricingPhaseList
                val recurring = phases.lastOrNull() ?: return@mapNotNull null
                Plan(
                    basePlanId = basePlanId,
                    offerToken = chosen.offerToken,
                    price = recurring.formattedPrice,
                    billingPeriod = recurring.billingPeriod,
                    trialPeriod = phases.firstOrNull { it.priceAmountMicros == 0L }?.billingPeriod,
                )
            }
            // Longest period first: yearly is the better deal, so lead with it.
            .sortedByDescending { periodDays(it.billingPeriod) }

    private suspend fun refreshPurchases() {
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return
        // Only active subscriptions come back, so "none" means expired or cancelled.
        applyPurchases(result.purchasesList, authoritative = true)
    }

    fun launchPurchase(activity: Activity, plan: Plan) {
        val details = productDetails
        if (details == null || !client.isReady) {
            _state.update { it.copy(error = "Couldn't reach Google Play. Try again in a moment.") }
            refresh()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(plan.offerToken)
                        .build()
                )
            )
            .build()
        _state.update { it.copy(error = null) }
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.update { it.copy(error = "Couldn't start the purchase. Try again.") }
        }
    }

    fun restore() {
        _state.update { it.copy(error = null) }
        refresh()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> applyPurchases(purchases.orEmpty(), authoritative = false)
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> _state.update { it.copy(error = "The purchase didn't go through. You haven't been charged.") }
        }
    }

    /**
     * [authoritative] = this is the full list of active purchases, so absence means not premium.
     * A purchase-update callback only carries what just changed, so it can grant but never revoke.
     */
    private fun applyPurchases(purchases: List<Purchase>, authoritative: Boolean) {
        val ours = purchases.filter { PRODUCT_ID in it.products }
        val active = ours.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }

        if (active || authoritative) setPremium(active)
        _state.update { it.copy(isPending = pending && !active) }

        ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase ->
                scope.launch {
                    val ack = client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                    )
                    if (ack.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.w(TAG, "Acknowledge failed: ${ack.debugMessage}")
                    }
                }
            }
    }

    private fun setPremium(premium: Boolean) {
        PremiumManager.setPremium(premium)
        prefs().edit().putBoolean(KEY_PREMIUM, premium).apply()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Rough length in days of an ISO 8601 period like P1M or P1Y, for sorting. */
internal fun periodDays(iso: String): Int {
    val m = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?").matchEntire(iso) ?: return 0
    val (y, mo, w, d) = m.destructured
    return (y.toIntOrNull() ?: 0) * 365 + (mo.toIntOrNull() ?: 0) * 30 + (w.toIntOrNull() ?: 0) * 7 + (d.toIntOrNull() ?: 0)
}

/** "P1M" → "month", "P3M" → "3 months", "P1Y" → "year", "P7D" → "7 days". */
internal fun periodLabel(iso: String, singularWithoutOne: Boolean = true): String {
    val m = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?").matchEntire(iso) ?: return iso
    val parts = listOf("year", "month", "week", "day").zip(m.groupValues.drop(1))
    val (unit, value) = parts.firstOrNull { it.second.isNotEmpty() } ?: return iso
    val n = value.toInt()
    return when {
        n == 1 && singularWithoutOne -> unit
        n == 1 -> "1 $unit"
        else -> "$n ${unit}s"
    }
}
