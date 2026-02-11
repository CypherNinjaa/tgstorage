package com.tgstorage.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.data.transfer.TransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based automatic retry worker for failed uploads.
 * 
 * This worker runs periodically and:
 * 1. Finds all files with FAILED sync status
 * 2. Re-queues them for upload via TransferManager
 * 3. Uses exponential backoff between retries
 * 
 * Runs every 5 minutes when there are failed uploads.
 */
class AutoRetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "AutoRetryWorker"
        const val UNIQUE_WORK_NAME = "tgstorage_auto_retry"
        // No max retry limit - keep trying indefinitely until uploaded

        /**
         * Schedule periodic auto-retry every 5 minutes.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoRetryWorker>(
                repeatInterval = 5,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace, keep existing schedule
                request,
            )
            Log.d(TAG, "Auto-retry scheduled (every 5 min)")
        }

        /** Cancel the periodic auto-retry. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Auto-retry cancelled")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "AutoRetryWorker started (attempt $runAttemptCount)")

        val app = TgStorageApp.instance
        val db = app.database
        val syncStateDao = db.syncStateDao()
        val fileDao = db.fileDao()

        // Get all failed uploads - retry indefinitely
        val failedSyncs = syncStateDao.getAllSyncStatesSync()
            .filter { it.status == SyncStatus.FAILED }

        if (failedSyncs.isEmpty()) {
            Log.d(TAG, "No failed uploads to retry")
            return@withContext Result.success()
        }

        Log.d(TAG, "Found ${failedSyncs.size} failed uploads to retry")

        var retriedCount = 0
        var skippedCount = 0

        for (syncState in failedSyncs) {
            try {
                val file = fileDao.getFileById(syncState.fileId)
                if (file == null) {
                    Log.w(TAG, "File ${syncState.fileId} not found, skipping")
                    skippedCount++
                    continue
                }

                // Check if file still has local content to upload
                val localFile = file.localUri?.let { java.io.File(it) }
                if (localFile == null || !localFile.exists()) {
                    Log.w(TAG, "Local file for ${file.name} not found, skipping")
                    skippedCount++
                    continue
                }

                // Increment retry count
                syncStateDao.insertOrUpdate(
                    syncState.copy(
                        retryCount = syncState.retryCount + 1,
                        lastAttempt = System.currentTimeMillis(),
                    )
                )

                // Re-queue for upload
                TransferManager.enqueueUpload(file)
                retriedCount++
                Log.d(TAG, "Re-queued failed upload: ${file.name} (attempt ${syncState.retryCount + 1})")

                // Small delay between retries to avoid overwhelming
                kotlinx.coroutines.delay(500)
            } catch (e: Exception) {
                Log.w(TAG, "Error retrying ${syncState.fileId}: ${e.message}")
                skippedCount++
            }
        }

        Log.d(TAG, "Auto-retry complete: $retriedCount retried, $skippedCount skipped")
        Result.success()
    }
}
