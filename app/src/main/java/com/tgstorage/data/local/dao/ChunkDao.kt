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
}
