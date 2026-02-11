package com.tgstorage.data.repository

import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.dao.SyncStateDao
import com.tgstorage.data.local.entity.BackupFrequency
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.local.entity.SyncStateEntity
import com.tgstorage.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Repository coordinating sync state queries and backup metadata.
 */
class SyncRepository(
    private val syncStateDao: SyncStateDao,
    private val fileDao: FileDao,
    private val metadataDao: MetadataDao,
) {

    // ─── Sync stats (reactive) ─────────────────────────

    val pendingCount: Flow<Int> = syncStateDao.getCountByStatus(SyncStatus.PENDING_UPLOAD)
    val uploadedCount: Flow<Int> = syncStateDao.getCountByStatus(SyncStatus.UPLOADED)
    val failedCount: Flow<Int> = syncStateDao.getCountByStatus(SyncStatus.FAILED)
    val totalCount: Flow<Int> = syncStateDao.getTotalCount()
    val lastSyncTimestamp: Flow<Long?> = syncStateDao.getLastSyncTimestamp()

    val pendingFiles: Flow<List<SyncStateEntity>> =
        syncStateDao.getByStatus(SyncStatus.PENDING_UPLOAD)

    val failedFiles: Flow<List<SyncStateEntity>> =
        syncStateDao.getByStatus(SyncStatus.FAILED)

    // ─── Sync operations ───────────────────────────────

    /** Get all files that need to be uploaded (for SyncWorker). */
    suspend fun getPendingUploads(): List<SyncStateEntity> =
        syncStateDao.getByStatusSync(SyncStatus.PENDING_UPLOAD)

    /** Mark a file as uploaded. */
    suspend fun markUploaded(fileId: Long) {
        syncStateDao.updateStatus(
            fileId = fileId,
            status = SyncStatus.UPLOADED,
            timestamp = System.currentTimeMillis(),
        )
    }

    /** Mark a file as failed with optional error message. */
    suspend fun markFailed(fileId: Long, error: String? = null) {
        syncStateDao.incrementRetryCount(fileId)
        syncStateDao.updateStatus(
            fileId = fileId,
            status = SyncStatus.FAILED,
            timestamp = System.currentTimeMillis(),
            error = error,
        )
    }

    /** Reset a single failed file for retry. */
    suspend fun retryFile(fileId: Long) {
        syncStateDao.resetForRetry(fileId)
    }

    /** Reset all failed files for retry. */
    suspend fun retryAllFailed() {
        syncStateDao.retryAllFailed()
    }

    // ─── Auto-sync setting ─────────────────────────────

    fun observeAutoSync(): Flow<Boolean> =
        metadataDao.observeValue(MetadataKeys.AUTO_SYNC_ENABLED).map { it?.toBoolean() ?: true }

    suspend fun setAutoSync(enabled: Boolean) {
        metadataDao.setValue(
            MetadataEntity(key = MetadataKeys.AUTO_SYNC_ENABLED, value = enabled.toString())
        )
    }

    suspend fun isAutoSyncEnabled(): Boolean =
        metadataDao.getValue(MetadataKeys.AUTO_SYNC_ENABLED)?.toBoolean() ?: true

    // ─── Backup metadata ───────────────────────────────

    suspend fun saveBackupInfo(messageId: Long, sizeBytes: Long) {
        metadataDao.setValue(
            MetadataEntity(key = MetadataKeys.LAST_BACKUP_MESSAGE_ID, value = messageId.toString())
        )
        metadataDao.setValue(
            MetadataEntity(key = MetadataKeys.LAST_BACKUP_TIMESTAMP, value = System.currentTimeMillis().toString())
        )
        metadataDao.setValue(
            MetadataEntity(key = MetadataKeys.LAST_BACKUP_SIZE, value = sizeBytes.toString())
        )
    }

    suspend fun getLastBackupMessageId(): Long? =
        metadataDao.getValue(MetadataKeys.LAST_BACKUP_MESSAGE_ID)?.toLongOrNull()

    fun observeLastBackupTimestamp(): Flow<Long?> =
        metadataDao.observeValue(MetadataKeys.LAST_BACKUP_TIMESTAMP).map { it?.toLongOrNull() }

    suspend fun getLastBackupTimestamp(): Long? =
        metadataDao.getValue(MetadataKeys.LAST_BACKUP_TIMESTAMP)?.toLongOrNull()

    suspend fun getLastBackupSize(): Long? =
        metadataDao.getValue(MetadataKeys.LAST_BACKUP_SIZE)?.toLongOrNull()

    // ─── Backup frequency ──────────────────────────────

    fun observeBackupFrequency(): Flow<String> =
        metadataDao.observeValue(MetadataKeys.AUTO_BACKUP_FREQUENCY).map { it ?: BackupFrequency.OFF }

    suspend fun setBackupFrequency(frequency: String) {
        metadataDao.setValue(
            MetadataEntity(key = MetadataKeys.AUTO_BACKUP_FREQUENCY, value = frequency)
        )
    }

    suspend fun getBackupFrequency(): String =
        metadataDao.getValue(MetadataKeys.AUTO_BACKUP_FREQUENCY) ?: BackupFrequency.OFF
}
