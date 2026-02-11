package com.tgstorage.common

import android.content.Context
import android.os.StatFs

/**
 * Utility for checking device storage availability.
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

    /** 50 MB safety margin to prevent filling the disk completely. */
    private const val SAFETY_MARGIN = 50L * 1024 * 1024
}
