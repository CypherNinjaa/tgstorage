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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic cleanup worker that removes orphaned chunk temp files
 * and clears stale cache data.
 */
class CleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "CleanupWorker"
        const val UNIQUE_WORK_NAME = "tgstorage_cleanup"
        private const val MAX_TEMP_AGE_MS = 24L * 60 * 60 * 1000 // 24 hours

        /** Schedule daily cleanup. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<CleanupWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.d(TAG, "Daily cleanup scheduled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "CleanupWorker started")

        var deletedCount = 0

        // 1. Clean orphaned temp chunk files
        val chunksDir = File(applicationContext.cacheDir, "chunks")
        if (chunksDir.exists()) {
            val now = System.currentTimeMillis()
            chunksDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && (now - dir.lastModified()) > MAX_TEMP_AGE_MS) {
                    val count = dir.listFiles()?.size ?: 0
                    dir.deleteRecursively()
                    deletedCount += count
                    Log.d(TAG, "Deleted stale chunk dir: ${dir.name} ($count files)")
                }
            }
        }

        // 2. Clean old temp files from cache root
        applicationContext.cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("tmp_") &&
                (System.currentTimeMillis() - file.lastModified()) > MAX_TEMP_AGE_MS
            ) {
                file.delete()
                deletedCount++
            }
        }
        
        // 3. Clean stale upload temp files (from content:// URI copies)
        val uploadTempDir = File(applicationContext.cacheDir, "upload_temp")
        if (uploadTempDir.exists()) {
            val now = System.currentTimeMillis()
            uploadTempDir.listFiles()?.forEach { file ->
                // Delete temp files older than 1 hour (should be deleted immediately after upload)
                if (file.isFile && (now - file.lastModified()) > 60 * 60 * 1000) {
                    file.delete()
                    deletedCount++
                    Log.d(TAG, "Deleted stale upload temp: ${file.name}")
                }
            }
        }

        // 4. Clean imported temp dir (legacy - for backward compatibility)
        val importedDir = File(applicationContext.filesDir, "imported")
        if (importedDir.exists()) {
            val db = TgStorageApp.instance.database
            val fileDao = db.fileDao()
            // Get all known local URIs from Room to avoid deleting active files
            val knownPaths = fileDao.getAllFilesSync().mapNotNull { it.localUri }.toSet()

            importedDir.listFiles()?.forEach { file ->
                if (file.absolutePath !in knownPaths) {
                    file.delete()
                    deletedCount++
                    Log.d(TAG, "Deleted orphaned imported file: ${file.name}")
                }
            }
        }

        Log.d(TAG, "CleanupWorker finished — removed $deletedCount files")
        return Result.success()
    }
}
