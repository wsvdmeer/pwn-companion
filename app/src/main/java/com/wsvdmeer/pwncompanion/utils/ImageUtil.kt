package com.wsvdmeer.pwncompanion.utils

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Utility for efficiently decoding base64-encoded images.
 * Decodes only when needed to avoid keeping large strings in memory.
 */
object ImageUtil {
    /**
     * Decode a base64-encoded image string to an ImageBitmap.
     *
     * @param base64Data The base64-encoded image data
     * @return ImageBitmap if successfully decoded, null if decoding fails
     */
    fun decodeBase64ToImageBitmap(base64Data: String?): ImageBitmap? {
        if (base64Data.isNullOrEmpty()) return null

        return try {
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

            // First pass: read only the bounds. Without this a spoofed/corrupt frame
            // declaring huge dimensions would force a full ARGB_8888 allocation before
            // we could downsample it.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            }
            val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, opts)
            bitmap?.asImageBitmap()
        } catch (e: OutOfMemoryError) {
            // OOM is an Error, not an Exception — if not caught explicitly it escapes
            // the decode and crashes the (main) thread that ran it.
            android.util.Log.w("ImageUtil", "Out of memory decoding image", e)
            null
        } catch (e: Exception) {
            android.util.Log.w("ImageUtil", "Failed to decode base64 image", e)
            null
        }
    }

    /**
     * Largest edge (px) we keep. The Pwnagotchi e-ink frame is ~250×122, so this is
     * generous headroom for a real frame while still capping an oversized/hostile one.
     */
    private const val MAX_DIMENSION = 1280

    /** Smallest power-of-two subsample that brings both edges within [maxDim]. */
    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w > maxDim || h > maxDim) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }

    /**
     * Get the size in bytes of a base64-encoded string.
     * Useful for monitoring memory usage.
     *
     * @param base64Data The base64-encoded data
     * @return Size in bytes
     */
    fun getBase64Size(base64Data: String?): Long {
        return base64Data?.length?.toLong() ?: 0L
    }
}

