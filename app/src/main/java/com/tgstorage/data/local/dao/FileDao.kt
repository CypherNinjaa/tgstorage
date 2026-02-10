package com.tgstorage.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tgstorage.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    @Query("SELECT * FROM files ORDER BY updated_at DESC")
    fun getAllFiles(): Flow<List<FileEntity>>

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
}
