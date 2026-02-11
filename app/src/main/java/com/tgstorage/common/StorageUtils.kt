package com.tgstorage.common

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File

/**
 * Utility for checking device storage availability and saving files.
 * Guards against running out of space during import/download.
 */
object StorageUtils {

    /** Returns available internal storage in bytes. */
    fun getAvailableInternalStorage(context: Context): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /** Returns available external storage in bytes, or internal if external is unavailable. */
    fun getAvailableExternalStorage(context: Context): Long {
        val externalDir = context.getExternalFilesDir(null) ?: return getAvailableInternalStorage(context)
        return try {
            val stat = StatFs(externalDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            getAvailableInternalStorage(context)
        }
    }

    /**
     * Returns true if there is enough free space for the given file size.
     * Requires at least [fileSizeBytes] + [SAFETY_MARGIN] available.
     */
    fun hasEnoughSpace(context: Context, fileSizeBytes: Long, useExternal: Boolean = false): Boolean {
        val available = if (useExternal) getAvailableExternalStorage(context) else getAvailableInternalStorage(context)
        return available > fileSizeBytes + SAFETY_MARGIN
    }

    /**
     * Formats storage space into a human-readable string.
     */
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /**
     * Save a file to the public Downloads folder using MediaStore.
     * This makes the file visible in the device's file manager.
     * 
     * @return The Uri of the saved file, or null if failed
     */
    fun saveToPublicDownloads(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
    ): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TgStorage")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return null

                resolver.openOutputStream(uri)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // Mark as complete
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                uri
            } else {
                // Android 9 and below - Direct file access
                @Suppress("DEPRECATION")
                val downloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "TgStorage"
                )
                if (!downloadsDir.exists()) downloadsDir.mkdirs()

                val destFile = File(downloadsDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)

                // Scan to make visible in gallery/file manager
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf(mimeType),
                    null
                )

                Uri.fromFile(destFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get a temp file for downloading. File is created in app's cache directory.
     */
    fun getTempDownloadFile(context: Context, fileName: String): File {
        val tempDir = File(context.cacheDir, "download_temp")
        if (!tempDir.exists()) tempDir.mkdirs()
        return File(tempDir, fileName)
    }

    /**
     * Clean up temp download files.
     */
    fun cleanupTempDownloads(context: Context) {
        val tempDir = File(context.cacheDir, "download_temp")
        tempDir.listFiles()?.forEach { it.delete() }
    }

    /** 50 MB safety margin to prevent filling the disk completely. */
    private const val SAFETY_MARGIN = 50L * 1024 * 1024
}
