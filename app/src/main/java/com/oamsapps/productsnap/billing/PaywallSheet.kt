package com.oamsapps.productsnap.billing

import android.content.res.Resources
import androidx.compose.ui.res.stringResource
import com.oamsapps.productsnap.R
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.oamsapps.productsnap.telemetry.Telemetry
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oamsapps.productsnap.BuildConfig


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallSheet(onDismiss: () -> Unit) {
    val billing by Billing.state.collectAsStateWithLifecycle()
    val premium by PremiumManager.isPremium.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { Telemetry.event("paywall_shown", "premium" to premium) }
    val activity = LocalContext.current.findActivity()
    val uriHandler = LocalUriHandler.current
    val resources = LocalContext.current.resources
    val benefits = listOf(
        stringResource(R.string.benefit_unlimited),
        stringResource(R.string.benefit_no_watermark),
        stringResource(R.string.benefit_batches, PremiumManager.PREMIUM_BATCH_LIMIT),
    )

    // Closes itself the moment the purchase goes through.
    LaunchedEffect(premium) { if (premium) onDismiss() }
    LaunchedEffect(Unit) { Billing.refresh() }

    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = billing.plans.firstOrNull { it.basePlanId == selectedId } ?: billing.plans.firstOrNull()

    ModalBottomSheet(onDismissRequest = { Billing.clearError(); onDismiss() }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.premium_title), style = MaterialTheme.typography.headlineSmall)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                benefits.forEach { benefit ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(benefit, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            when {
                billing.isLoading && billing.plans.isEmpty() ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                billing.plans.isEmpty() ->
                    Text(
                        billing.error ?: stringResource(R.string.billing_no_plans),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                else ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        billing.plans.forEach { plan ->
                            PlanCard(plan, selected = plan == selected, onSelect = { selectedId = plan.basePlanId })
                        }
                    }
            }

            if (billing.isPending) {
                Text(
                    stringResource(R.string.payment_pending),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (billing.error != null && billing.plans.isNotEmpty()) {
                Text(billing.error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { if (activity != null && selected != null) Billing.launchPurchase(activity, selected) },
                enabled = activity != null && selected != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(if (selected?.trialPeriod != null) R.string.start_free_trial else R.string.subscribe))
            }

            Text(
                selected?.let { renewalNote(resources, it) } ?: stringResource(R.string.billed_google_play),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = Billing::restore) { Text(stringResource(R.string.restore_purchase)) }
                TextButton(onClick = {
                    uriHandler.openUri(
                        "https://play.google.com/store/account/subscriptions?sku=${Billing.PRODUCT_ID}&package=${BuildConfig.APPLICATION_ID}"
                    )
                }) { Text(stringResource(R.string.manage_subscription)) }
            }

            if (BuildConfig.DEBUG) {
                TextButton(onClick = PremiumManager::debugUnlock) { Text("Unlock Premium (debug builds only)") }
                // Crash reporting status and checks, so setup can be verified without adb.
                var testSent by remember { mutableStateOf<String?>(null) }
                Text(
                    "Crash reports: ${Telemetry.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    testSent = if (Telemetry.sendTestReport()) "Sent. Look under Non-fatals in Crashlytics in a few minutes." else "Reporting is off in this build."
                }) { Text("Send a test report, no crash (debug builds only)") }
                testSent?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                // Checks that Crashlytics catches real crashes: the report appears in the Firebase console after relaunch.
                TextButton(onClick = { throw RuntimeException("Test crash from build ${BuildConfig.BUILD_ID}") }) {
                    Text("Send a test crash (debug builds only)")
                }
            }
        }
    }
}

@Composable
private fun PlanCard(plan: Plan, selected: Boolean, onSelect: () -> Unit) {
    val resources = LocalContext.current.resources
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.price_per_period, plan.price, periodLabel(resources, plan.billingPeriod)),
                    style = MaterialTheme.typography.titleMedium,
                )
                plan.trialPeriod?.let {
                    Text(
                        stringResource(R.string.trial_free, periodLabel(resources, it, singularWithoutOne = false)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun renewalNote(resources: Resources, plan: Plan): String {
    val period = periodLabel(resources, plan.billingPeriod)
    return if (plan.trialPeriod != null) {
        resources.getString(R.string.renewal_trial, periodLabel(resources, plan.trialPeriod, singularWithoutOne = false), plan.price, period)
    } else {
        resources.getString(R.string.renewal_plain, plan.price, period)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
