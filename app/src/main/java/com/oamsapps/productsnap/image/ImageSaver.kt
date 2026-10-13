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

    /** Saves to Pictures/ProductSnap. No storage permission needed on API 29+. */
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        transparent: Boolean,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ext = if (transparent) "png" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "productsnap_${System.currentTimeMillis()}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, if (transparent) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ProductSnap")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the gallery entry")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                val format = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(bitmap.compress(format, 95, stream)) { "Couldn't encode the image" }
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
