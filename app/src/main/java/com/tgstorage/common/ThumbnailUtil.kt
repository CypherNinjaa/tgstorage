package com.tgstorage.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Generates thumbnails for image files using BitmapFactory downscaling.
 * Thumbnails are stored alongside originals in a `thumbnails/` directory.
 */
object ThumbnailUtil {

    private const val THUMB_MAX_SIZE = 256 // px

    /**
     * Returns the thumbnail [File] for the given image path,
     * generating it if it doesn't exist.
     * Returns `null` if the source isn't an image or generation fails.
     */
    suspend fun getOrCreateThumbnail(
        localPath: String,
        cacheDir: File,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val source = File(localPath)
            if (!source.exists()) return@withContext null

            val thumbDir = File(cacheDir, "thumbnails")
            if (!thumbDir.exists()) thumbDir.mkdirs()

            val thumbFile = File(thumbDir, "thumb_${source.name}")
            if (thumbFile.exists()) return@withContext thumbFile

            // Decode bounds only
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(localPath, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null

            // Calculate sample size
            val sampleSize = calculateInSampleSize(options.outWidth, options.outHeight)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(localPath, decodeOptions)
                ?: return@withContext null

            // Scale to exact thumbnail size
            val scaledBitmap = scaleBitmap(bitmap, THUMB_MAX_SIZE)
            if (scaledBitmap !== bitmap) bitmap.recycle()

            // Write to file
            FileOutputStream(thumbFile).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            scaledBitmap.recycle()

            thumbFile
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var inSampleSize = 1
        val maxDim = maxOf(width, height)
        while (maxDim / inSampleSize > THUMB_MAX_SIZE * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap

        val ratio = minOf(maxSize.toFloat() / w, maxSize.toFloat() / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
