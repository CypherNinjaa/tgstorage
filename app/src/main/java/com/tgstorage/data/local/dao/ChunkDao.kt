package com.tgstorage.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgstorage.data.local.entity.ChunkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {

    @Query("SELECT * FROM chunks WHERE file_id = :fileId ORDER BY chunk_index ASC")
    fun getChunksForFile(fileId: Long): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE file_id = :fileId ORDER BY chunk_index ASC")
    suspend fun getChunksForFileSync(fileId: Long): List<ChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: ChunkEntity): Long

    @Query("DELETE FROM chunks WHERE file_id = :fileId")
    suspend fun deleteChunksForFile(fileId: Long)

    /**
     * Update a chunk's telegram_file_id (used by FileRecoveryManager
     * when refreshing stale file_ids via forwardMessage).
     */
    @Query("UPDATE chunks SET telegram_file_id = :newFileId WHERE id = :chunkId")
    suspend fun updateChunkFileId(chunkId: Long, newFileId: String)

    /**
     * Update a chunk's file_unique_id for future-proofing.
     */
    @Query("UPDATE chunks SET telegram_file_unique_id = :uniqueId WHERE id = :chunkId")
    suspend fun updateChunkFileUniqueId(chunkId: Long, uniqueId: String)

    /** Total chunk count across all files */
    @Query("SELECT COUNT(*) FROM chunks")
    fun getTotalChunkCount(): Flow<Int>

    /** Total size of all chunks (approx Telegram storage used) */
    @Query("SELECT COALESCE(SUM(size), 0) FROM chunks")
    fun getTotalChunkSize(): Flow<Long>
}
