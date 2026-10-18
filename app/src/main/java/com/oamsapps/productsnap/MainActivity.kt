package com.oamsapps.productsnap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oamsapps.productsnap.batch.BatchScreen
import com.oamsapps.productsnap.batch.BatchViewModel
import com.oamsapps.productsnap.billing.Billing
import com.oamsapps.productsnap.billing.PaywallSheet
import com.oamsapps.productsnap.editor.EditorScreen
import com.oamsapps.productsnap.editor.EditorViewModel
import com.oamsapps.productsnap.home.HomeScreen
import com.oamsapps.productsnap.home.OnboardingScreen
import com.oamsapps.productsnap.home.SellerPrefs
import com.oamsapps.productsnap.ui.theme.ProductSnapTheme
import com.oamsapps.productsnap.ui.theme.Studio

private enum class Screen { Onboarding, Home, Editor, Batch }

class MainActivity : ComponentActivity() {

    private val editorVm: EditorViewModel by viewModels()
    private val batchVm: BatchViewModel by viewModels()

    // Four screens and no deep stack; swap for Navigation Compose if that changes.
    private val screen = mutableStateOf(Screen.Home)
    private val showPaywall = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        screen.value = savedInstanceState?.getString(KEY_SCREEN)?.let { name -> Screen.entries.firstOrNull { it.name == name } }
            ?: if (SellerPrefs.seller.value.onboarded) Screen.Home else Screen.Onboarding
        if (savedInstanceState == null) {
            editorVm.setMarketplace(SellerPrefs.seller.value.primary)
            handleIntent(intent)
        }

        setContent {
            ProductSnapTheme {
                val seller by SellerPrefs.seller.collectAsStateWithLifecycle()
                val openPaywall = { showPaywall.value = true }
                val home = { screen.value = Screen.Home }
                Box(Modifier.fillMaxSize().background(Studio.Paper)) {
                    AnimatedContent(screen.value, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { current ->
                        when (current) {
                            Screen.Onboarding -> OnboardingScreen(
                                initial = seller.markets,
                                onDone = { markets ->
                                    SellerPrefs.saveMarkets(this@MainActivity, markets)
                                    editorVm.setMarketplace(SellerPrefs.seller.value.primary)
                                    home()
                                },
                                onSkip = { SellerPrefs.saveMarkets(this@MainActivity, seller.markets); home() },
                            )
                            Screen.Home -> HomeScreen(
                                onOpenPhoto = { uri -> openPhoto(uri) },
                                onOpenBatch = {
                                    editorVm.setMarketplace(seller.primary)
                                    openBatch()
                                },
                                onOpenPaywall = openPaywall,
                                onPrimaryChanged = { editorVm.setMarketplace(it) },
                                onChangeMarkets = { screen.value = Screen.Onboarding },
                            )
                            Screen.Editor -> {
                                BackHandler { home() }
                                EditorScreen(editorVm, onBack = home, onOpenBatch = ::openBatch, onOpenPaywall = openPaywall)
                            }
                            Screen.Batch -> {
                                BackHandler { home() }
                                BatchScreen(
                                    batchVm,
                                    onBack = home,
                                    onOpenPaywall = openPaywall,
                                    onOpenInEditor = { file ->
                                        screen.value = Screen.Editor
                                        editorVm.loadImage(Uri.fromFile(file), batchVm.state.value.settings)
                                    },
                                )
                            }
                        }
                    }
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
        outState.putString(KEY_SCREEN, screen.value.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A new photo starts with the look for the seller's marketplace. */
    private fun openPhoto(uri: Uri) {
        editorVm.setMarketplace(SellerPrefs.seller.value.primary)
        editorVm.loadImage(uri)
        screen.value = Screen.Editor
    }

    private fun openBatch() {
        batchVm.configureFrom(editorVm.state.value)
        screen.value = Screen.Batch
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Tapped the batch progress / finished notification.
        if (intent.getBooleanExtra(EXTRA_OPEN_BATCH, false)) {
            screen.value = Screen.Batch
            return
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::openPhoto)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    editorVm.setMarketplace(SellerPrefs.seller.value.primary)
                    openBatch()
                    batchVm.addPhotos(uris)
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_BATCH = "open_batch"
        private const val KEY_SCREEN = "screen"
    }
}
