package com.tgstorage.data.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A file found on the device via MediaStore.
 * This is NOT a Room entity — it's a lightweight data class for display.
 */
data class DeviceFile(
    val id: Long,
    val name: String,
    val path: String,
    val size: Long,
    val mimeType: String,
    val dateModified: Long,
    val contentUri: Uri,
)

/**
 * Scans the device for all files using [MediaStore].
 * Works with granular permissions on Android 13+ and
 * READ_EXTERNAL_STORAGE on older versions.
 */
class DeviceFileScanner(private val context: Context) {

    /**
     * Load all files from the device, sorted by date modified (newest first).
     * Optionally filter by MIME prefix (e.g. "image/", "video/").
     */
    suspend fun scanFiles(
        mimeFilter: String? = null,
        searchQuery: String? = null,
    ): List<DeviceFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<DeviceFile>()

        // Query both "external files" collection (covers all file types)
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )

        // Build selection
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        // Exclude zero-size and directory entries
        selectionParts.add("${MediaStore.Files.FileColumns.SIZE} > 0")

        if (mimeFilter != null) {
            selectionParts.add("${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?")
            selectionArgs.add("$mimeFilter%")
        } else {
            // Exclude null mime types (directories, etc.)
            selectionParts.add("${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL")
            selectionParts.add("${MediaStore.Files.FileColumns.MIME_TYPE} != ''")
        }

        if (!searchQuery.isNullOrBlank()) {
            selectionParts.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            selectionArgs.add("%$searchQuery%")
        }

        val selection = selectionParts.joinToString(" AND ")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs.toTypedArray(),
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val path = cursor.getString(dataCol) ?: ""
                val size = cursor.getLong(sizeCol)
                val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                val date = cursor.getLong(dateCol)

                val contentUri = ContentUris.withAppendedId(collection, id)

                files.add(
                    DeviceFile(
                        id = id,
                        name = name,
                        path = path,
                        size = size,
                        mimeType = mime,
                        dateModified = date,
                        contentUri = contentUri,
                    )
                )
            }
        }

        files
    }
}
