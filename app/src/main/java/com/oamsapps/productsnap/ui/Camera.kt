package com.oamsapps.productsnap.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * "Take a photo": opens the phone's camera app, which saves the shot into our cache, then hands it on.
 * Uses the camera app rather than an in-app camera, so no CAMERA permission is needed and the user gets
 * their own camera's focus, HDR and flash.
 */
@Composable
fun rememberTakePhoto(onPhoto: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    // Survives the activity being recreated while the camera app is open.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pending?.let(Uri::parse)
        pending = null
        if (saved && uri != null) onPhoto(uri)
    }
    return {
        val uri = newShot(context)
        pending = uri.toString()
        try {
            launcher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pending = null
            Toast.makeText(context, "No camera app found on this phone.", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun newShot(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    // Shots are copied into the editor or a batch when opened, so a day-old one is no longer needed.
    val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    dir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
    val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
