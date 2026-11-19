package com.oamsapps.productsnap.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oamsapps.productsnap.R
import com.oamsapps.productsnap.telemetry.Telemetry
import com.oamsapps.productsnap.ui.AccentButton
import com.oamsapps.productsnap.ui.ReviewPrompt
import com.oamsapps.productsnap.ui.theme.Studio

/**
 * Demand test for a profit tracker: asks once, on Home, after the user has saved at least one photo, whether
 * they would use one. Nothing is built behind it yet; the answers (Firebase events `profit_card_shown` and
 * `profit_interest`) decide whether to build it.
 */
private object ProfitInterest {
    private const val PREFS = "profit_interest"
    private const val KEY_ANSWER = "answer"
    private const val KEY_SHOWN = "shown"

    fun answer(context: Context): String? = prefs(context).getString(KEY_ANSWER, null)
    fun setAnswer(context: Context, answer: String) = prefs(context).edit().putString(KEY_ANSWER, answer).apply()

    /** True only the first time, so the "shown" count is per person, not per Home visit. */
    fun markShown(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean(KEY_SHOWN, false)) return false
        p.edit().putBoolean(KEY_SHOWN, true).apply()
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

@Composable
fun ProfitInterestCard() {
    val context = LocalContext.current
    // Read once per visit: after "Yes" the thank-you stays until the user leaves Home, then the card is gone.
    val initial = remember { ProfitInterest.answer(context) }
    val eligible = remember { ReviewPrompt.savedCount(context) > 0 }
    var answer by remember { mutableStateOf(initial) }
    if (initial != null || !eligible) return

    LaunchedEffect(Unit) { if (ProfitInterest.markShown(context)) Telemetry.event("profit_card_shown") }

    fun choose(value: String) {
        answer = value
        ProfitInterest.setAnswer(context, value)
        Telemetry.event("profit_interest", "answer" to value)
    }

    when (answer) {
        "no" -> return
        "yes" -> Surface(shape = RoundedCornerShape(18.dp), color = Studio.Chip, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.profit_thanks),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        else -> Surface(shape = RoundedCornerShape(18.dp), color = Studio.Card, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.profit_label), style = MaterialTheme.typography.labelMedium, color = Studio.Accent)
                Text(stringResource(R.string.profit_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.profit_body), style = MaterialTheme.typography.bodyMedium, color = Studio.Muted)
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { choose("no") }) {
                        Text(stringResource(R.string.profit_no), color = Studio.Ink, style = MaterialTheme.typography.labelLarge)
                    }
                    AccentButton(text = stringResource(R.string.profit_yes), onClick = { choose("yes") })
                }
            }
        }
    }
}
