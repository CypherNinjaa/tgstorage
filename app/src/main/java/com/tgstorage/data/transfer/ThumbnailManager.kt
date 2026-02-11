package com.tgstorage.data.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import com.tgstorage.data.local.dao.FileDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Creates and caches small thumbnails for image/video files.
 * Call [generateThumbnail] before deleting the original local file
 * so the Uploaded tab can still show previews without full-size copies.
 */
object ThumbnailManager {

    private const val THUMB_WIDTH = 256
    private const val THUMB_HEIGHT = 256
    private const val THUMB_QUALITY = 75 // JPEG quality

    /**
     * If the file is an image or video, generates a small JPEG thumbnail
     * in the app's cache dir and stores its path in the DB.
     * Returns the thumbnail [File] or null if not applicable.
     */
    suspend fun generateThumbnail(
        context: Context,
        fileDao: FileDao,
        fileId: Long,
        localPath: String,
        mimeType: String,
    ): File? = withContext(Dispatchers.IO) {
        val isImage = mimeType.startsWith("image/")
        val isVideo = mimeType.startsWith("video/")
        if (!isImage && !isVideo) return@withContext null

        val sourceFile = File(localPath)
        if (!sourceFile.exists()) return@withContext null

        val thumbDir = File(context.cacheDir, "thumbnails")
        if (!thumbDir.exists()) thumbDir.mkdirs()
        val thumbFile = File(thumbDir, "thumb_$fileId.jpg")

        // Already generated
        if (thumbFile.exists()) {
            fileDao.setThumbnailUri(fileId, thumbFile.absolutePath)
            return@withContext thumbFile
        }

        try {
            val bitmap: Bitmap? = if (isImage) {
                decodeImageThumbnail(sourceFile)
            } else {
                decodeVideoThumbnail(sourceFile)
            }

            if (bitmap != null) {
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
                }
                bitmap.recycle()
                fileDao.setThumbnailUri(fileId, thumbFile.absolutePath)
                return@withContext thumbFile
            }
        } catch (_: Exception) {
            // Thumbnail generation failed — not critical
        }
        null
    }

    private fun decodeImageThumbnail(file: File): Bitmap? {
        // First pass: get dimensions without loading
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val w = options.outWidth
        val h = options.outHeight
        if (w <= 0 || h <= 0) return null

        // Calculate sample size for efficient memory usage
        var sampleSize = 1
        while (w / sampleSize > THUMB_WIDTH * 2 || h / sampleSize > THUMB_HEIGHT * 2) {
            sampleSize *= 2
        }

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        // Scale down
        val scale = minOf(THUMB_WIDTH.toFloat() / decoded.width, THUMB_HEIGHT.toFloat() / decoded.height)
        val scaledW = (decoded.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (decoded.height * scale).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(decoded, scaledW, scaledH, true)
        if (thumb !== decoded) decoded.recycle()
        return thumb
    }

    @Suppress("DEPRECATION")
    private fun decodeVideoThumbnail(file: File): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(file, Size(THUMB_WIDTH, THUMB_HEIGHT), null)
        } else {
            ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
        }
    }

    /** Resolves the best available thumbnail path for display. */
    fun resolveThumbnailPath(
        localUri: String?,
        thumbnailUri: String?,
        mimeType: String,
    ): String? {
        val isImage = mimeType.startsWith("image/")
        val isVideo = mimeType.startsWith("video/")
        if (!isImage && !isVideo) return null

        // Prefer thumbnail cache
        thumbnailUri?.let { path ->
            if (File(path).exists()) return path
        }
        // Fall back to original file if still on device
        localUri?.let { path ->
            if (File(path).exists()) return path
        }
        return null
    }
}
