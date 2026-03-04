package com.tgstorage.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tgstorage.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow

/** Projection for the Uploaded tab — rich file info with upload date + Telegram IDs */
data class UploadedFileInfo(
    val id: Long,
    val name: String,
    val size: Long,
    val mimeType: String,
    val localUri: String?,
    val thumbnailUri: String?,
    val updatedAt: Long,
    val uploadedAt: Long?,
    val telegramFileId: String?,
)

/** Projection for failed files — includes error message and retry count */
data class FailedFileInfo(
    val id: Long,
    val name: String,
    val size: Long,
    val mimeType: String,
    val localUri: String?,
    val thumbnailUri: String?,
    val failedAt: Long?,
    val errorMessage: String?,
    val retryCount: Int,
)

/** Projection for storage stats — file type breakdown */
data class FileTypeStats(
    val category: String,
    val fileCount: Int,
    val totalSize: Long,
)

@Dao
interface FileDao {

    @Query("SELECT * FROM files ORDER BY updated_at DESC")
    fun getAllFiles(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files ORDER BY updated_at DESC")
    suspend fun getAllFilesSync(): List<FileEntity>

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun getFileById(id: Long): FileEntity?

    @Query("SELECT * FROM files WHERE name LIKE '%' || :query || '%'")
    fun searchFiles(query: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE mime_type LIKE :mimeTypePrefix || '%' ORDER BY updated_at DESC")
    fun getFilesByType(mimeTypePrefix: String): Flow<List<FileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: FileEntity): Long

    @Update
    suspend fun updateFile(file: FileEntity)

    @Delete
    suspend fun deleteFile(file: FileEntity)

    @Query("SELECT COUNT(*) FROM files")
    fun getFileCount(): Flow<Int>

    @Query("SELECT f.name FROM files f INNER JOIN sync_state s ON f.id = s.file_id WHERE s.status = 'uploaded'")
    fun getUploadedFileNames(): Flow<List<String>>

    /** Sync version for background workers */
    @Query("SELECT f.name FROM files f INNER JOIN sync_state s ON f.id = s.file_id WHERE s.status = 'uploaded'")
    suspend fun getUploadedFileNamesSync(): List<String>

    /** Get ALL file names in database (uploaded, pending, failed) - for auto-backup deduplication */
    @Query("SELECT name FROM files")
    suspend fun getAllFileNamesSync(): List<String>

    @Query("SELECT f.* FROM files f INNER JOIN sync_state s ON f.id = s.file_id WHERE s.status = 'uploaded' ORDER BY f.updated_at DESC")
    fun getUploadedFiles(): Flow<List<FileEntity>>

    @Query("""
        SELECT f.id, f.name, f.size, f.mime_type AS mimeType, f.local_uri AS localUri,
               f.thumbnail_uri AS thumbnailUri,
               f.updated_at AS updatedAt, s.last_attempt AS uploadedAt,
               c.telegram_file_id AS telegramFileId
        FROM files f
        INNER JOIN sync_state s ON f.id = s.file_id
        LEFT JOIN chunks c ON f.id = c.file_id AND c.chunk_index = 0
        WHERE s.status = 'uploaded'
        ORDER BY s.last_attempt DESC
    """)
    fun getUploadedFilesDetailed(): Flow<List<UploadedFileInfo>>

    /** Paginated uploaded files with search + filter — all filtering in SQL */
    @Query("""
        SELECT f.id, f.name, f.size, f.mime_type AS mimeType, f.local_uri AS localUri,
               f.thumbnail_uri AS thumbnailUri,
               f.updated_at AS updatedAt, s.last_attempt AS uploadedAt,
               c.telegram_file_id AS telegramFileId
        FROM files f
        INNER JOIN sync_state s ON f.id = s.file_id
        LEFT JOIN chunks c ON f.id = c.file_id AND c.chunk_index = 0
        WHERE s.status = 'uploaded'
          AND (:query = '' OR f.name LIKE '%' || :query || '%')
          AND (:mimePrefix = '' OR f.mime_type LIKE :mimePrefix || '%')
        ORDER BY s.last_attempt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getUploadedFilesPaged(
        query: String,
        mimePrefix: String,
        limit: Int,
        offset: Int,
    ): List<UploadedFileInfo>

    /** Count of uploaded files matching search + filter */
    @Query("""
        SELECT COUNT(*)
        FROM files f
        INNER JOIN sync_state s ON f.id = s.file_id
        WHERE s.status = 'uploaded'
          AND (:query = '' OR f.name LIKE '%' || :query || '%')
          AND (:mimePrefix = '' OR f.mime_type LIKE :mimePrefix || '%')
    """)
    suspend fun getUploadedFilesCount(query: String, mimePrefix: String): Int

    /** Total uploaded count (for tab header) — reactive Flow */
    @Query("SELECT COUNT(*) FROM sync_state WHERE status = 'uploaded'")
    fun getUploadedTotalCount(): Flow<Int>

    // ── Failed Files Queries ────────────────────────

    /** Get all failed files with their error info */
    @Query("""
        SELECT f.id, f.name, f.size, f.mime_type AS mimeType, f.local_uri AS localUri,
               f.thumbnail_uri AS thumbnailUri,
               s.last_attempt AS failedAt, s.error_message AS errorMessage,
               s.retry_count AS retryCount
        FROM files f
        INNER JOIN sync_state s ON f.id = s.file_id
        WHERE s.status = 'failed'
        ORDER BY s.last_attempt DESC
    """)
    fun getFailedFilesDetailed(): Flow<List<FailedFileInfo>>

    /** Get failed files count */
    @Query("SELECT COUNT(*) FROM sync_state WHERE status = 'failed'")
    fun getFailedTotalCount(): Flow<Int>

    /** Paginated failed files */
    @Query("""
        SELECT f.id, f.name, f.size, f.mime_type AS mimeType, f.local_uri AS localUri,
               f.thumbnail_uri AS thumbnailUri,
               s.last_attempt AS failedAt, s.error_message AS errorMessage,
               s.retry_count AS retryCount
        FROM files f
        INNER JOIN sync_state s ON f.id = s.file_id
        WHERE s.status = 'failed'
        ORDER BY s.last_attempt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFailedFilesPaged(limit: Int, offset: Int): List<FailedFileInfo>

    /** Clear the local_uri after upload to free storage */
    @Query("UPDATE files SET local_uri = NULL WHERE id = :fileId")
    suspend fun clearLocalUri(fileId: Long)

    /** Set thumbnail URI */
    @Query("UPDATE files SET thumbnail_uri = :uri WHERE id = :fileId")
    suspend fun setThumbnailUri(fileId: Long, uri: String)

    // ── Storage Stats Queries ────────────────────────

    /** Total size of all files */
    @Query("SELECT COALESCE(SUM(size), 0) FROM files")
    fun getTotalSize(): Flow<Long>

    /** Size breakdown by mime type prefix (image, video, audio, application, etc.) */
    @Query("""
        SELECT 
            CASE 
                WHEN mime_type LIKE 'image/%' THEN 'Images'
                WHEN mime_type LIKE 'video/%' THEN 'Videos'
                WHEN mime_type LIKE 'audio/%' THEN 'Audio'
                WHEN mime_type LIKE 'text/%' THEN 'Documents'
                WHEN mime_type LIKE 'application/pdf' THEN 'Documents'
                WHEN mime_type LIKE 'application/msword%' THEN 'Documents'
                WHEN mime_type LIKE 'application/vnd.openxmlformats%' THEN 'Documents'
                WHEN mime_type LIKE 'application/zip%' THEN 'Archives'
                WHEN mime_type LIKE 'application/x-rar%' THEN 'Archives'
                WHEN mime_type LIKE 'application/x-7z%' THEN 'Archives'
                WHEN mime_type LIKE 'application/gzip%' THEN 'Archives'
                WHEN mime_type LIKE 'application/x-tar%' THEN 'Archives'
                ELSE 'Other'
            END AS category,
            COUNT(*) AS fileCount,
            COALESCE(SUM(size), 0) AS totalSize
        FROM files
        GROUP BY category
        ORDER BY totalSize DESC
    """)
    suspend fun getFileTypeBreakdown(): List<FileTypeStats>

    /** Largest files (top 10) */
    @Query("SELECT * FROM files ORDER BY size DESC LIMIT 10")
    suspend fun getLargestFiles(): List<FileEntity>

    /** Total uploaded size (files with 'uploaded' sync status) */
    @Query("""
        SELECT COALESCE(SUM(f.size), 0)
        FROM files f
        INNER JOIN sync_state s ON f.id = s.file_id
        WHERE s.status = 'uploaded'
    """)
    fun getUploadedTotalSize(): Flow<Long>

    // ── Folder queries ──────────────────────────────────

    /** Get files in a specific folder (non-trashed only) */
    @Query("SELECT * FROM files WHERE folder_id = :folderId AND trashed_at IS NULL ORDER BY updated_at DESC")
    fun getFilesInFolder(folderId: Long): Flow<List<FileEntity>>

    /** Get root files (no folder, non-trashed) */
    @Query("SELECT * FROM files WHERE folder_id IS NULL AND trashed_at IS NULL ORDER BY updated_at DESC")
    fun getRootFiles(): Flow<List<FileEntity>>

    /** Move file to a folder */
    @Query("UPDATE files SET folder_id = :folderId, updated_at = :now WHERE id = :fileId")
    suspend fun moveToFolder(fileId: Long, folderId: Long?, now: Long = System.currentTimeMillis())

    /** Move multiple files to a folder */
    @Query("UPDATE files SET folder_id = :folderId, updated_at = :now WHERE id IN (:fileIds)")
    suspend fun moveMultipleToFolder(fileIds: List<Long>, folderId: Long?, now: Long = System.currentTimeMillis())

    // ── Trash / Recycle Bin ─────────────────────────────

    /** Soft-delete: move to trash */
    @Query("UPDATE files SET trashed_at = :now WHERE id = :fileId")
    suspend fun moveToTrash(fileId: Long, now: Long = System.currentTimeMillis())

    /** Soft-delete multiple files */
    @Query("UPDATE files SET trashed_at = :now WHERE id IN (:fileIds)")
    suspend fun moveMultipleToTrash(fileIds: List<Long>, now: Long = System.currentTimeMillis())

    /** Restore from trash */
    @Query("UPDATE files SET trashed_at = NULL WHERE id = :fileId")
    suspend fun restoreFromTrash(fileId: Long)

    /** Restore multiple from trash */
    @Query("UPDATE files SET trashed_at = NULL WHERE id IN (:fileIds)")
    suspend fun restoreMultipleFromTrash(fileIds: List<Long>)

    /** Get all trashed files */
    @Query("SELECT * FROM files WHERE trashed_at IS NOT NULL ORDER BY trashed_at DESC")
    fun getTrashedFiles(): Flow<List<FileEntity>>

    /** Get trashed files older than given timestamp (for auto-purge) */
    @Query("SELECT * FROM files WHERE trashed_at IS NOT NULL AND trashed_at < :cutoff")
    suspend fun getTrashedFilesBefore(cutoff: Long): List<FileEntity>

    /** Count trashed files */
    @Query("SELECT COUNT(*) FROM files WHERE trashed_at IS NOT NULL")
    fun getTrashedCount(): Flow<Int>
}
