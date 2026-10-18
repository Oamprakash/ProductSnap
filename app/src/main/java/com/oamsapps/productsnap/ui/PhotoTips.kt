package com.oamsapps.productsnap.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Shown once on first launch, and again from the light-bulb button. */
object PhotoTips {
    private const val PREFS = "photo_tips"
    private const val KEY_SEEN = "seen"

    fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
}

private class Tip(val icon: ImageVector, val title: String, val body: String)

// Each tip answers a failure seen in testing: dim rooms, items blending into the background, items cut off at
// the photo's edge, blur.
private val tips = listOf(
    Tip(Icons.Default.WbSunny, "Shoot in daylight", "Near a window, without flash. Dim rooms make dark items blend into the background."),
    Tip(Icons.Default.Layers, "Use a plain, contrasting surface", "Light items on a dark surface, dark items on a light one. Busy patterns confuse the cutout."),
    Tip(Icons.Default.CropFree, "Leave space around the item", "Keep the whole item in the frame. Anything touching the edge may be cut off or keep some background."),
    Tip(Icons.Default.CenterFocusStrong, "Hold still and tap to focus", "A sharp photo cuts out cleanly. Lay clothes flat and shoot from straight above."),
    Tip(Icons.Default.Adjust, "Show flaws honestly", "Mark stains or tears with Mark flaws. Each gets its own close-up, so buyers know what they're getting."),
)

@Composable
fun PhotoTipsDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Tips for photos that sell", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "A good photo to start from means a clean cutout in one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tips.forEach { t ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(t.icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(t.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
            }
        }
    }
}
