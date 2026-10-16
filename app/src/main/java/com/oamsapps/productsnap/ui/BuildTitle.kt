package com.oamsapps.productsnap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.oamsapps.productsnap.BuildConfig

/** A top-bar title with the build ID under it, so every screenshot and test report says which build it came from. */
@Composable
fun BuildTitle(title: String) {
    Column {
        Text(title)
        Text(
            "Build ${BuildConfig.BUILD_ID}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
