package com.tgstorage.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgstorage.data.local.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE file_id = :fileId")
    suspend fun getSyncState(fileId: Long): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE status = :status")
    fun getByStatus(status: String): Flow<List<SyncStateEntity>>

    @Query("SELECT COUNT(*) FROM sync_state WHERE status = :status")
    fun getCountByStatus(status: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(syncState: SyncStateEntity)
}
