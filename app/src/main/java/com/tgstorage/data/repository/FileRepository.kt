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
     * Copies a file from a content:// URI into app-private storage,
     * computes SHA-256, records in Room, and creates a sync_state entry.
     */
    suspend fun importFile(uri: Uri): Result<FileEntity> = withContext(Dispatchers.IO) {
        runCatching {
            // Resolve display name & size from content resolver
            val (displayName, fileSize) = resolveFileMetadata(uri)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

            // Copy to app-private files dir
            val storageDir = File(context.filesDir, "imported")
            if (!storageDir.exists()) storageDir.mkdirs()
            val destFile = File(storageDir, "${System.currentTimeMillis()}_$displayName")

            val sha256 = copyAndHash(uri, destFile)

            val entity = FileEntity(
                name = displayName,
                size = destFile.length(),
                mimeType = mimeType,
                sha256 = sha256,
                localUri = destFile.absolutePath,
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

    // ── Delete ─────────────────────────────────────────

    suspend fun deleteFile(fileEntity: FileEntity) = withContext(Dispatchers.IO) {
        // Delete local copy
        fileEntity.localUri?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        fileDao.deleteFile(fileEntity)
    }

    // ── Helpers ────────────────────────────────────────

    private fun resolveFileMetadata(uri: Uri): Pair<String, Long> {
        var name = "unknown"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: "unknown"
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }

    /**
     * Copies content from [uri] to [dest], returning the hex SHA-256 hash.
     */
    private fun copyAndHash(uri: Uri, dest: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                }
            }
        } ?: error("Cannot open input stream for $uri")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
