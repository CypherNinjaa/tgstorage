package com.tgstorage.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tgstorage.TgStorageApp
import com.tgstorage.data.repository.FileRepository
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based worker that auto-purges trashed files older than 30 days.
 * Runs once per day.
 */
class TrashCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "TrashCleanupWorker"
        const val UNIQUE_WORK_NAME = "tgstorage_trash_cleanup"
        private const val TRASH_RETENTION_DAYS = 30

        /** Schedule daily trash cleanup. Call from Application.onCreate(). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(
                1, TimeUnit.DAYS,
            )
                .addTag(TAG)
                .setConstraints(
                    Constraints.Builder().build(), // No network needed — local DB only
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.d(TAG, "Trash cleanup scheduled (daily)")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as TgStorageApp
            val db = app.database
            val repository = FileRepository(app, db.fileDao(), db.syncStateDao())

            val purgedCount = repository.purgeOldTrash(TRASH_RETENTION_DAYS)
            if (purgedCount > 0) {
                Log.i(TAG, "Purged $purgedCount trashed file(s) older than $TRASH_RETENTION_DAYS days")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Trash cleanup failed", e)
            Result.retry()
        }
    }
}
