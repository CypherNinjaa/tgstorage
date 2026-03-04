package com.tgstorage.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.SyncStateDao
import com.tgstorage.data.local.dao.UploadedFileInfo
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.local.entity.SyncStateEntity
import com.tgstorage.data.local.entity.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class FileRepository(
    private val context: Context,
    private val fileDao: FileDao,
    private val syncStateDao: SyncStateDao,
) {
    // ── Queries ────────────────────────────────────────

    fun getAllFiles(): Flow<List<FileEntity>> =
        fileDao.getAllFiles()

    fun searchFiles(query: String): Flow<List<FileEntity>> =
        fileDao.searchFiles(query)

    fun getFilesByMimePrefix(prefix: String): Flow<List<FileEntity>> =
        fileDao.getFilesByType(prefix)

    fun getFileCount(): Flow<Int> =
        fileDao.getFileCount()

    fun getUploadedFileNames(): Flow<List<String>> =
        fileDao.getUploadedFileNames()

    fun getUploadedFiles(): Flow<List<FileEntity>> =
        fileDao.getUploadedFiles()

    fun getUploadedFilesDetailed(): Flow<List<UploadedFileInfo>> =
        fileDao.getUploadedFilesDetailed()

    suspend fun getFileById(id: Long): FileEntity? =
        fileDao.getFileById(id)

    suspend fun getSyncState(fileId: Long): SyncStateEntity? =
        syncStateDao.getSyncState(fileId)

    // ── Import from content URI ────────────────────────

    /**
     * Registers a file for upload by storing its URI reference.
     * NO COPYING - we upload directly from the original location.
     * SHA-256 is computed on-demand during upload.
     */
    suspend fun importFile(uri: Uri): Result<FileEntity> = withContext(Dispatchers.IO) {
        runCatching {
            // Resolve display name & size from content resolver
            val (displayName, fileSize) = resolveFileMetadata(uri)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

            // Reject ghost/invalid files — 0 bytes or unresolvable name
            if (fileSize <= 0L) {
                throw IllegalArgumentException("Cannot import file with 0 bytes: $displayName ($uri)")
            }
            if (displayName == "unknown" || displayName.isBlank()) {
                throw IllegalArgumentException("Cannot resolve file name from URI: $uri")
            }

            // Store the URI directly - NO COPY
            // SHA-256 will be computed during upload to avoid double-read
            val entity = FileEntity(
                name = displayName,
                size = fileSize,
                mimeType = mimeType,
                sha256 = "", // Will be computed during upload
                localUri = uri.toString(), // Store URI, not file path
            )

            val id = fileDao.insertFile(entity)

            // Create initial sync state
            syncStateDao.insertOrUpdate(
                SyncStateEntity(
                    fileId = id,
                    status = SyncStatus.PENDING_UPLOAD,
                )
            )

            entity.copy(id = id)
        }
    }
    
    /**
     * Registers a file from a file path (for files we have direct path access to).
     */
    suspend fun importFileFromPath(filePath: String, name: String, size: Long, mimeType: String): Result<FileEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = FileEntity(
                name = name,
                size = size,
                mimeType = mimeType,
                sha256 = "", // Will be computed during upload
                localUri = filePath,
            )

            val id = fileDao.insertFile(entity)

            syncStateDao.insertOrUpdate(
                SyncStateEntity(
                    fileId = id,
                    status = SyncStatus.PENDING_UPLOAD,
                )
            )

            entity.copy(id = id)
        }
    }

    // ── Delete ─────────────────────────────────────────

    suspend fun deleteFile(fileEntity: FileEntity) = withContext(Dispatchers.IO) {
        fileEntity.localUri?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        fileDao.deleteFile(fileEntity)
    }

    // ── Folder operations ──────────────────────────────

    /** Move a file into a folder (null = root) */
    suspend fun moveFileToFolder(fileId: Long, folderId: Long?) {
        fileDao.moveToFolder(fileId, folderId)
    }

    /** Move multiple files into a folder */
    suspend fun moveFilesToFolder(fileIds: List<Long>, folderId: Long?) {
        fileDao.moveMultipleToFolder(fileIds, folderId)
    }

    // ── Trash / Recycle Bin ────────────────────────────

    /** Soft-delete: move to trash */
    suspend fun trashFile(fileId: Long) {
        fileDao.moveToTrash(fileId)
    }

    /** Soft-delete multiple files */
    suspend fun trashFiles(fileIds: List<Long>) {
        fileDao.moveMultipleToTrash(fileIds)
    }

    /** Restore file from trash */
    suspend fun restoreFile(fileId: Long) {
        fileDao.restoreFromTrash(fileId)
    }

    /** Restore multiple files from trash */
    suspend fun restoreFiles(fileIds: List<Long>) {
        fileDao.restoreMultipleFromTrash(fileIds)
    }

    /** Get all trashed files */
    fun getTrashedFiles(): Flow<List<FileEntity>> =
        fileDao.getTrashedFiles()

    /** Get trashed file count */
    fun getTrashedCount(): Flow<Int> =
        fileDao.getTrashedCount()

    /**
     * Permanently delete files trashed more than [days] ago.
     * Returns number of files purged.
     */
    suspend fun purgeOldTrash(days: Int = 30): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val expired = fileDao.getTrashedFilesBefore(cutoff)
        for (file in expired) {
            file.localUri?.let { path ->
                val f = File(path)
                if (f.exists()) f.delete()
            }
            syncStateDao.deleteByFileId(file.id)
            fileDao.deleteFile(file)
        }
        expired.size
    }

    /** Permanently delete a single trashed file */
    suspend fun permanentlyDeleteFile(fileId: Long) = withContext(Dispatchers.IO) {
        val file = fileDao.getFileById(fileId) ?: return@withContext
        file.localUri?.let { path ->
            val f = File(path)
            if (f.exists()) f.delete()
        }
        syncStateDao.deleteByFileId(fileId)
        fileDao.deleteFile(file)
    }

    /** Empty entire trash */
    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        val allTrashed = fileDao.getAllFilesSync().filter { it.trashedAt != null }
        for (file in allTrashed) {
            file.localUri?.let { path ->
                val f = File(path)
                if (f.exists()) f.delete()
            }
            syncStateDao.deleteByFileId(file.id)
            fileDao.deleteFile(file)
        }
        allTrashed.size
    }

    // ── Helpers ────────────────────────────────────────

    /**
     * Purge ghost entries: files in DB with name "unknown" / blank or size 0.
     * Also removes their sync_state rows.
     * Call once at app startup to clean up historical ghosts.
     */
    suspend fun cleanupGhostFiles(): Int = withContext(Dispatchers.IO) {
        val ghosts = fileDao.getAllFilesSync().filter { file ->
            file.size <= 0L ||
            file.name.isBlank() ||
            file.name.equals("unknown", ignoreCase = true)
        }
        for (ghost in ghosts) {
            syncStateDao.deleteByFileId(ghost.id)
            fileDao.deleteFile(ghost)
        }
        ghosts.size
    }

    private fun resolveFileMetadata(uri: Uri): Pair<String, Long> {
        var name = "unknown"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: "unknown"
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) {
            // SecurityException, stale URI, etc. — leave defaults
        }
        return name to size
    }
}
