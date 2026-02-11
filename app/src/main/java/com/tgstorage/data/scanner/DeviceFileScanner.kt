package com.tgstorage.data.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tgstorage.util.StoragePermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 *
 * On Android 13+ documents are not covered by READ_MEDIA_* permissions,
 * so we additionally scan MediaStore.Downloads and on-disk Documents folder.
 * When MANAGE_EXTERNAL_STORAGE is granted, we can scan all external storage directly.
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
        val results = mutableListOf<DeviceFile>()
        
        // Check if we have full storage access for comprehensive scanning
        val hasFullAccess = StoragePermissionHelper.hasFullStorageAccess()

        // 1. Scan MediaStore.Files — gives images/video/audio (+ all files on older Android)
        results.addAll(scanMediaStoreFiles(mimeFilter, searchQuery))

        // 2. On Android 10+, also scan MediaStore.Downloads — captures downloaded documents
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val docFilter = if (mimeFilter != null && !mimeFilter.startsWith("application/") && !mimeFilter.startsWith("text/")) {
                // User filtered to image/video/audio — downloads won't match, skip
                null
            } else {
                mimeFilter
            }
            if (docFilter == null && mimeFilter == null || docFilter != null) {
                results.addAll(scanDownloads(docFilter, searchQuery))
            }
        }

        // 3. Scan on-disk directories for documents
        if (mimeFilter == null || mimeFilter.startsWith("application/") || mimeFilter.startsWith("text/")) {
            if (hasFullAccess) {
                // With full access, scan all common document locations
                results.addAll(scanAllDocumentDirectories(searchQuery))
            } else {
                // Fallback: only scan Documents folder (limited access)
                results.addAll(scanDocumentsFolder(searchQuery))
            }
        }
        
        // 4. With full access, also scan other common storage locations
        if (hasFullAccess && (mimeFilter == null || mimeFilter.startsWith("application/") || mimeFilter.startsWith("text/"))) {
            results.addAll(scanAdditionalDirectories(searchQuery, mimeFilter))
        }

        // Deduplicate by path (or by name+size as fallback)
        val seen = mutableSetOf<String>()
        results.filter { file ->
            val key = file.path.ifBlank { "${file.name}_${file.size}" }
            seen.add(key)
        }.sortedByDescending { it.dateModified }
    }

    /** Scan MediaStore.Files — main scan that works with granted permissions. */
    private fun scanMediaStoreFiles(
        mimeFilter: String?,
        searchQuery: String?,
    ): List<DeviceFile> {
        val files = mutableListOf<DeviceFile>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = fileProjection()
        val (selection, args) = buildSelection(mimeFilter, searchQuery)

        context.contentResolver.query(
            collection, projection, selection, args.toTypedArray(),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor -> files.addAll(cursorToDeviceFiles(cursor, collection)) }

        return files
    }

    /** Scan MediaStore.Downloads — captures documents downloaded by other apps. */
    private fun scanDownloads(
        mimeFilter: String?,
        searchQuery: String?,
    ): List<DeviceFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val files = mutableListOf<DeviceFile>()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = fileProjection()
        val (selection, args) = buildSelection(mimeFilter, searchQuery)

        try {
            context.contentResolver.query(
                collection, projection, selection, args.toTypedArray(),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor -> files.addAll(cursorToDeviceFiles(cursor, collection)) }
        } catch (_: Exception) { /* Some devices don't support Downloads query */ }

        return files
    }

    /** Scan on-disk Documents directory — fallback for Android 13+ where MediaStore lacks docs. */
    private fun scanDocumentsFolder(searchQuery: String?): List<DeviceFile> {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            ?: return emptyList()
        if (!docsDir.exists()) return emptyList()

        return docsDir.walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .filter { file -> isValidUserFile(file.name, file.absolutePath) }
            .filter { file ->
                searchQuery == null || file.name.contains(searchQuery, ignoreCase = true)
            }
            .map { file ->
                val mime = guessMime(file.name)
                DeviceFile(
                    id = file.absolutePath.hashCode().toLong(),
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    mimeType = mime,
                    dateModified = file.lastModified() / 1000,
                    contentUri = Uri.fromFile(file),
                )
            }
            .toList()
    }

    /** 
     * Scan all common document directories with full storage access.
     * This includes Documents, Downloads, and app-specific directories.
     */
    private fun scanAllDocumentDirectories(searchQuery: String?): List<DeviceFile> {
        val results = mutableListOf<DeviceFile>()
        
        // Common document storage locations
        val directories = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        )
        
        for (dir in directories) {
            if (!dir.exists() || !dir.isDirectory) continue
            
            try {
                dir.walkTopDown()
                    .filter { it.isFile && it.length() > 0 }
                    .filter { file -> isValidUserFile(file.name, file.absolutePath) }
                    .filter { file ->
                        val mime = guessMime(file.name)
                        isDocumentMime(mime)
                    }
                    .filter { file ->
                        searchQuery == null || file.name.contains(searchQuery, ignoreCase = true)
                    }
                    .forEach { file ->
                        val mime = guessMime(file.name)
                        results.add(
                            DeviceFile(
                                id = file.absolutePath.hashCode().toLong(),
                                name = file.name,
                                path = file.absolutePath,
                                size = file.length(),
                                mimeType = mime,
                                dateModified = file.lastModified() / 1000,
                                contentUri = Uri.fromFile(file),
                            )
                        )
                    }
            } catch (_: Exception) {
                // Skip directories we can't access
            }
        }
        
        return results
    }
    
    /**
     * Scan additional directories for files when full storage access is granted.
     * This includes WhatsApp, Telegram received files, and other common app folders.
     */
    private fun scanAdditionalDirectories(searchQuery: String?, mimeFilter: String?): List<DeviceFile> {
        val results = mutableListOf<DeviceFile>()
        val externalStorage = Environment.getExternalStorageDirectory() ?: return emptyList()
        
        // Additional directories commonly containing user files
        val additionalDirs = listOf(
            File(externalStorage, "WhatsApp/Media/WhatsApp Documents"),
            File(externalStorage, "Telegram/Telegram Documents"),
            File(externalStorage, "Telegram"),
            File(externalStorage, "Download"), // Alternate Downloads path
            File(externalStorage, "DCIM"),
        )
        
        for (dir in additionalDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            
            try {
                dir.walkTopDown()
                    .maxDepth(3) // Limit depth to avoid excessive scanning
                    .filter { it.isFile && it.length() > 0 }
                    .filter { file -> isValidUserFile(file.name, file.absolutePath) }
                    .filter { file ->
                        if (mimeFilter != null) {
                            val mime = guessMime(file.name)
                            mime.startsWith(mimeFilter)
                        } else {
                            true
                        }
                    }
                    .filter { file ->
                        searchQuery == null || file.name.contains(searchQuery, ignoreCase = true)
                    }
                    .forEach { file ->
                        val mime = guessMime(file.name)
                        results.add(
                            DeviceFile(
                                id = file.absolutePath.hashCode().toLong(),
                                name = file.name,
                                path = file.absolutePath,
                                size = file.length(),
                                mimeType = mime,
                                dateModified = file.lastModified() / 1000,
                                contentUri = Uri.fromFile(file),
                            )
                        )
                    }
            } catch (_: Exception) {
                // Skip directories we can't access
            }
        }
        
        return results
    }
    
    /** Check if MIME type is a document type. */
    private fun isDocumentMime(mime: String): Boolean {
        return mime.startsWith("application/") || 
               mime.startsWith("text/") ||
               mime in listOf(
                   "application/pdf",
                   "application/msword",
                   "application/vnd.ms-excel",
                   "application/vnd.ms-powerpoint",
                   "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                   "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                   "application/zip",
                   "application/x-rar-compressed",
                   "application/json",
                   "application/xml",
               )
    }

    // ── Helpers ─────────────────────────────────────────

    private fun fileProjection() = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
    )

    private fun buildSelection(mimeFilter: String?, searchQuery: String?): Pair<String, List<String>> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()

        parts.add("${MediaStore.Files.FileColumns.SIZE} > 0")

        if (mimeFilter != null) {
            parts.add("${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?")
            args.add("$mimeFilter%")
        } else {
            parts.add("${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL")
            parts.add("${MediaStore.Files.FileColumns.MIME_TYPE} != ''")
        }

        if (!searchQuery.isNullOrBlank()) {
            parts.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            args.add("%$searchQuery%")
        }

        return parts.joinToString(" AND ") to args
    }

    private fun cursorToDeviceFiles(cursor: Cursor, collection: Uri): List<DeviceFile> {
        val files = mutableListOf<DeviceFile>()
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
            
            // Skip files in excluded paths or with invalid names
            if (!isValidUserFile(name, path)) continue

            files.add(DeviceFile(id, name, path, size, mime, date, contentUri))
        }
        return files
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
    
    // ── Path exclusion filters ─────────────────────────────────────────
    
    /** Folders to exclude from scanning (app-internal, cache, system). */
    private val excludedPathPatterns = listOf(
        "/Android/data/",
        "/Android/obb/",
        "/Android/media/",
        "/.cache/",
        "/.thumbnails/",
        "/.trash/",
        "/cache/",
        "/temp/",
        "/tmp/",
        "/.nomedia",
        "/lost+found/",
        "/.android_secure/",
    )
    
    /** Check if a file path should be excluded from results. */
    private fun isExcludedPath(path: String): Boolean {
        if (path.isBlank()) return false
        val lowerPath = path.lowercase()
        return excludedPathPatterns.any { pattern -> lowerPath.contains(pattern.lowercase()) }
    }
    
    /** Check if filename looks like a valid user file (has proper extension, not random hash). */
    private fun isValidUserFile(name: String, path: String): Boolean {
        // Exclude files in Android folders
        if (isExcludedPath(path)) return false
        
        // Must have a recognizable extension
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank() || ext == name.lowercase()) return false
        
        // Known valid extensions
        val validExtensions = setOf(
            // Documents
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "rtf", "odt", "ods", "odp", "csv", "md",
            // Archives
            "zip", "rar", "7z", "tar", "gz",
            // Code & data
            "json", "xml", "html", "htm", "css", "js", "py", "java", "kt",
            // Images
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico",
            // Video
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
            // Audio
            "mp3", "m4a", "wav", "flac", "aac", "ogg", "wma",
            // Apps
            "apk", "xapk",
            // Other
            "epub", "mobi", "djvu",
        )
        
        if (ext !in validExtensions) {
            // Unknown extension - check if name looks like random hash
            val baseName = name.substringBeforeLast('.')
            // Random hashes typically have: no spaces, lots of special chars, very long
            if (baseName.length > 30 && !baseName.contains(' ') && 
                baseName.count { it in "+/=" } > 2) {
                return false
            }
        }
        
        return true
    }
}
