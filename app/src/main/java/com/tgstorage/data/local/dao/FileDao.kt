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

    /** Clear the local_uri after upload to free storage */
    @Query("UPDATE files SET local_uri = NULL WHERE id = :fileId")
    suspend fun clearLocalUri(fileId: Long)

    /** Set thumbnail URI */
    @Query("UPDATE files SET thumbnail_uri = :uri WHERE id = :fileId")
    suspend fun setThumbnailUri(fileId: Long, uri: String)
}
