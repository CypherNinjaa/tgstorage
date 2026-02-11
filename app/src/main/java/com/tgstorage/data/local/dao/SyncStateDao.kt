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

    @Query("SELECT * FROM sync_state WHERE status = :status")
    suspend fun getByStatusSync(status: String): List<SyncStateEntity>

    @Query("SELECT COUNT(*) FROM sync_state WHERE status = :status")
    fun getCountByStatus(status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_state WHERE status = :status")
    suspend fun getCountByStatusSync(status: String): Int

    @Query("SELECT COUNT(*) FROM sync_state")
    fun getTotalCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(syncState: SyncStateEntity)

    @Query("UPDATE sync_state SET status = :status, last_attempt = :timestamp, error_message = :error WHERE file_id = :fileId")
    suspend fun updateStatus(fileId: Long, status: String, timestamp: Long = System.currentTimeMillis(), error: String? = null)

    @Query("UPDATE sync_state SET retry_count = retry_count + 1 WHERE file_id = :fileId")
    suspend fun incrementRetryCount(fileId: Long)

    @Query("UPDATE sync_state SET status = 'pending_upload', retry_count = 0, error_message = NULL WHERE file_id = :fileId")
    suspend fun resetForRetry(fileId: Long)

    @Query("UPDATE sync_state SET status = 'pending_upload', retry_count = 0, error_message = NULL WHERE status = 'failed'")
    suspend fun retryAllFailed()

    @Query("SELECT MAX(last_attempt) FROM sync_state WHERE status = 'uploaded'")
    fun getLastSyncTimestamp(): Flow<Long?>

    @Query("DELETE FROM sync_state WHERE file_id = :fileId")
    suspend fun deleteByFileId(fileId: Long)
}
