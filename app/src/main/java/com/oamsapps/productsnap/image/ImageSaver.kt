package com.oamsapps.productsnap.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageSaver {

    /**
     * File names for one item's photos, so the main photo and its flaw close-ups sort together and in order in any
     * gallery or file picker: productsnap_20261113_004512_1_main, …_2_flaw, …_3_flaw.
     */
    class ItemNames(now: Long = System.currentTimeMillis()) {
        private val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(now))
        private var next = 1
        fun main() = "productsnap_${stamp}_${next++}_main"
        fun flaw() = "productsnap_${stamp}_${next++}_flaw"
    }

    /**
     * Keeps a photo taken with "Take a photo" in Pictures/ProductSnap/Originals, byte for byte, so the seller can
     * go back to the untouched shot later. Returns null if it couldn't be kept; editing carries on either way.
     */
    suspend fun keepOriginal(context: Context, source: Uri): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "productsnap_${stamp}_original.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ProductSnap/Originals")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        try {
            val copied = resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
            }
            checkNotNull(copied) { "Couldn't copy the photo" }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Saves to Pictures/ProductSnap. No storage permission needed on API 29+. */
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        transparent: Boolean,
        quality: Int = 95,
        name: String? = null,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ext = if (transparent) "png" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${name ?: "productsnap_${System.currentTimeMillis()}"}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, if (transparent) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ProductSnap")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the gallery entry")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                val format = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(bitmap.compress(format, quality, stream)) { "Couldn't encode the image" }
            } ?: error("Couldn't open the gallery file")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
