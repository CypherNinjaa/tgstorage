package com.tgstorage.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * File picker helper using Storage Access Framework (SAF).
 * 
 * SAF is the recommended way to access user-selected files on Android 10+.
 * It provides:
 * - User consent for file access
 * - No permissions required for individual file picks
 * - Persistent access grants for folders
 * - Works across all Android versions
 * 
 * Usage with Compose:
 * ```kotlin
 * val filePicker = rememberLauncherForActivityResult(
 *     contract = ActivityResultContracts.OpenDocument()
 * ) { uri -> uri?.let { handleFile(it) } }
 * 
 * // For multiple files:
 * val multiPicker = rememberLauncherForActivityResult(
 *     contract = ActivityResultContracts.OpenMultipleDocuments()
 * ) { uris -> handleFiles(uris) }
 * ```
 */
object FilePickerHelper {

    // ═══════════════════════════════════════════════════════════════════════════
    // COMMON MIME TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    /** All file types */
    val ALL_FILES = arrayOf("*/*")
    
    /** Images only */
    val IMAGES = arrayOf("image/*")
    
    /** Videos only */
    val VIDEOS = arrayOf("video/*")
    
    /** Audio files only */
    val AUDIO = arrayOf("audio/*")
    
    /** Documents (PDF, Office, text) */
    val DOCUMENTS = arrayOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/*",
    )
    
    /** Images and videos (media) */
    val MEDIA = arrayOf("image/*", "video/*")
    
    /** Common backup file types */
    val BACKUP_FILES = arrayOf(
        "image/*",
        "video/*",
        "audio/*",
        "application/pdf",
        "application/zip",
        "application/*",
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // FILE INFO EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

    data class PickedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mimeType: String,
    )

    /**
     * Extract file information from a picked URI.
     */
    suspend fun getFileInfo(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                    
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else uri.lastPathSegment ?: "unknown"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    
                    PickedFile(uri, name, size, mimeType)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract file information from multiple picked URIs.
     */
    suspend fun getFilesInfo(context: Context, uris: List<Uri>): List<PickedFile> = withContext(Dispatchers.IO) {
        uris.mapNotNull { getFileInfo(context, it) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILE COPYING (For upload operations)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Copy a picked file to the app's private cache directory.
     * This is useful when you need a regular File object for upload operations.
     * 
     * @param context Application context
     * @param uri The content URI of the picked file
     * @param destDir Destination directory (defaults to cache)
     * @return The copied File, or null if copy failed
     */
    suspend fun copyToCache(
        context: Context,
        uri: Uri,
        destDir: File = context.cacheDir,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val fileInfo = getFileInfo(context, uri) ?: return@withContext null
            
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, fileInfo.name)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            
            destFile.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copy multiple picked files to cache.
     */
    suspend fun copyAllToCache(
        context: Context,
        uris: List<Uri>,
        destDir: File = context.cacheDir,
    ): List<File> = withContext(Dispatchers.IO) {
        uris.mapNotNull { copyToCache(context, it, destDir) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENT ACCESS (For folder backup feature)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Take persistable permission for a picked folder/file.
     * This allows future access without re-picking.
     * 
     * NOTE: User must pick the folder using OpenDocumentTree contract.
     */
    fun takePersistablePermission(context: Context, uri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                           Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            // Permission not grantable, ignore
        }
    }

    /**
     * Release a previously taken persistable permission.
     */
    fun releasePersistablePermission(context: Context, uri: Uri) {
        try {
            val releaseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                              Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, releaseFlags)
        } catch (e: Exception) {
            // Already released or never granted
        }
    }

    /**
     * Get all URIs with persistent permissions.
     */
    fun getPersistablePermissions(context: Context): List<Uri> {
        return context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
    }

    /**
     * Check if we have persistent access to a URI.
     */
    fun hasPersistablePermission(context: Context, uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FOLDER TRAVERSAL (For backup folders)
    // ═══════════════════════════════════════════════════════════════════════════

    data class DocumentFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mimeType: String,
        val isDirectory: Boolean,
        val lastModified: Long,
    )

    /**
     * List files in a folder picked via OpenDocumentTree.
     */
    suspend fun listFolderContents(context: Context, folderUri: Uri): List<DocumentFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<DocumentFile>()
        
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            folderUri,
            DocumentsContract.getTreeDocumentId(folderUri)
        )
        
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val lastModCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val lastModified = cursor.getLong(lastModCol)
                    
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                    val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    
                    files.add(DocumentFile(docUri, name, size, mimeType, isDir, lastModified))
                }
            }
        } catch (e: Exception) {
            // Folder not accessible
        }
        
        files
    }

    /**
     * Recursively list all files in a folder (for backup).
     */
    suspend fun listFolderRecursive(context: Context, folderUri: Uri): List<DocumentFile> = withContext(Dispatchers.IO) {
        val allFiles = mutableListOf<DocumentFile>()
        
        suspend fun traverse(uri: Uri) {
            val contents = listFolderContents(context, uri)
            for (item in contents) {
                if (item.isDirectory) {
                    traverse(item.uri)
                } else {
                    allFiles.add(item)
                }
            }
        }
        
        traverse(folderUri)
        allFiles
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANDROID 13+ PHOTO PICKER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if the device supports the new Android 13+ Photo Picker.
     */
    fun isPhotoPickerAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Get the PickVisualMedia contract for Compose launchers.
     * Use with rememberLauncherForActivityResult.
     * 
     * Example:
     * ```kotlin
     * val photoPicker = rememberLauncherForActivityResult(
     *     contract = ActivityResultContracts.PickVisualMedia()
     * ) { uri -> uri?.let { handlePhoto(it) } }
     * 
     * // Launch: photoPicker.launch(PickVisualMediaRequest(ImageOnly))
     * ```
     */
    fun getPhotoPickerContract() = ActivityResultContracts.PickVisualMedia()

    /**
     * Get the PickMultipleVisualMedia contract for multiple selection.
     * @param maxItems Maximum number of items user can select
     */
    fun getMultiPhotoPickerContract(maxItems: Int = 10) = 
        ActivityResultContracts.PickMultipleVisualMedia(maxItems)
}
