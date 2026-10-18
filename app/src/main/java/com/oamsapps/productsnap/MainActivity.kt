package com.oamsapps.productsnap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import com.oamsapps.productsnap.billing.Billing
import com.oamsapps.productsnap.billing.PaywallSheet
import androidx.core.content.IntentCompat
import com.oamsapps.productsnap.batch.BatchScreen
import com.oamsapps.productsnap.batch.BatchViewModel
import com.oamsapps.productsnap.editor.EditorScreen
import com.oamsapps.productsnap.editor.EditorViewModel
import com.oamsapps.productsnap.ui.theme.ProductSnapTheme

class MainActivity : ComponentActivity() {

    private val editorVm: EditorViewModel by viewModels()
    private val batchVm: BatchViewModel by viewModels()

    // Simple two-screen navigation; swap for Navigation Compose when screens grow.
    private val showBatch = mutableStateOf(false)
    private val showPaywall = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showBatch.value = savedInstanceState?.getBoolean(KEY_BATCH) ?: false
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            ProductSnapTheme {
                val openPaywall = { showPaywall.value = true }
                if (showBatch.value) {
                    BackHandler { showBatch.value = false }
                    BatchScreen(
                        batchVm,
                        onBack = { showBatch.value = false },
                        onOpenPaywall = openPaywall,
                        onOpenInEditor = { file ->
                            showBatch.value = false
                            editorVm.loadImage(Uri.fromFile(file), batchVm.state.value.settings)
                        },
                    )
                } else {
                    EditorScreen(editorVm, onOpenBatch = ::openBatch, onOpenPaywall = openPaywall)
                }
                if (showPaywall.value) {
                    PaywallSheet(onDismiss = { showPaywall.value = false })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up renewals, cancellations and purchases made outside the app.
        Billing.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_BATCH, showBatch.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun openBatch() {
        batchVm.configureFrom(editorVm.state.value)
        showBatch.value = true
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Tapped the batch progress / finished notification.
        if (intent.getBooleanExtra(EXTRA_OPEN_BATCH, false)) {
            showBatch.value = true
            return
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                    showBatch.value = false
                    editorVm.loadImage(it)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    openBatch()
                    batchVm.addPhotos(uris)
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_BATCH = "open_batch"
        private const val KEY_BATCH = "show_batch"
    }
}
