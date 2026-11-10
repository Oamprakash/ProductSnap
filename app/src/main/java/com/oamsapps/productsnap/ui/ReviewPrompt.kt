package com.oamsapps.productsnap.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.oamsapps.productsnap.telemetry.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asks for a Play Store rating at a happy moment: right after photos are saved, once the user has saved enough
 * to know the app (3 photos), then again after much more use. Google's in-app review sheet decides itself
 * whether to show (it has its own quota), and never says whether the user rated, so we only pace the asks.
 */
object ReviewPrompt {
    private const val PREFS = "review_prompt"
    private const val KEY_SAVED = "photos_saved"
    private const val KEY_ASKS = "asks"
    private const val KEY_LAST_ASK = "last_ask"
    private const val KEY_SAVED_AT_ASK = "saved_at_ask"

    private const val FIRST_ASK_AFTER = 3
    private const val MORE_PHOTOS_BETWEEN_ASKS = 25
    private const val DAYS_BETWEEN_ASKS = 60
    private const val MAX_ASKS = 3

    private val _due = MutableStateFlow(false)
    /** True when the app should show the review sheet at the next good moment. */
    val due: StateFlow<Boolean> = _due.asStateFlow()

    /** Call after photos were saved successfully. */
    fun recordSaved(context: Context, photos: Int) {
        if (photos <= 0) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getInt(KEY_SAVED, 0) + photos
        p.edit().putInt(KEY_SAVED, saved).apply()

        val asks = p.getInt(KEY_ASKS, 0)
        val daysSince = (System.currentTimeMillis() - p.getLong(KEY_LAST_ASK, 0L)) / 86_400_000L
        val ready = when {
            asks >= MAX_ASKS -> false
            asks == 0 -> saved >= FIRST_ASK_AFTER
            else -> daysSince >= DAYS_BETWEEN_ASKS && saved - p.getInt(KEY_SAVED_AT_ASK, 0) >= MORE_PHOTOS_BETWEEN_ASKS
        }
        if (ready) _due.value = true
    }

    /** Shows Google's review sheet over [activity]. Safe to call when Play isn't available: it just does nothing. */
    suspend fun ask(activity: Activity) {
        if (!_due.value) return
        _due.value = false
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putInt(KEY_ASKS, p.getInt(KEY_ASKS, 0) + 1)
            .putLong(KEY_LAST_ASK, System.currentTimeMillis())
            .putInt(KEY_SAVED_AT_ASK, p.getInt(KEY_SAVED, 0))
            .apply()
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            val info = manager.requestReview()
            manager.launchReview(activity, info)
            Telemetry.event("review_prompt_shown")
        }.onFailure { Log.w("ReviewPrompt", "In-app review unavailable", it) }
    }
}
