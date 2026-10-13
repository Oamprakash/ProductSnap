package com.oamsapps.productsnap.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

object BitmapLoader {

    /**
     * Decodes a mutable, software bitmap (required by ML Kit + Canvas),
     * downscaled so the long edge is at most [maxEdge] to stay memory-safe.
     */
    suspend fun load(context: Context, uri: Uri, maxEdge: Int = 2048): Bitmap =
        decode(ImageDecoder.createSource(context.contentResolver, uri), maxEdge)

    suspend fun load(file: File, maxEdge: Int = 2048): Bitmap =
        decode(ImageDecoder.createSource(file), maxEdge)

    private suspend fun decode(source: ImageDecoder.Source, maxEdge: Int): Bitmap =
        withContext(Dispatchers.IO) {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val w = info.size.width
                val h = info.size.height
                val longEdge = max(w, h)
                if (longEdge > maxEdge) {
                    val scale = maxEdge.toFloat() / longEdge
                    decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                }
            }
        }
}
