package com.tgstorage.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.data.local.entity.SyncStateEntity
import com.tgstorage.data.repository.FileRepository
import com.tgstorage.data.scanner.DeviceFileScanner
import com.tgstorage.data.transfer.TransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based background auto-backup worker.
 * 
 * This worker runs EVEN WHEN APP IS CLOSED and:
 * 1. Scans device for new images/videos/audio
 * 2. Compares with already-uploaded files in database
 * 3. Imports new files to database
 * 4. Queues new files for upload via TransferManager
 * 
 * - Runs immediately via OneTimeWorkRequest when auto-upload is first enabled
 * - Then runs every 15 minutes via PeriodicWorkRequest
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "AutoBackupWorker"
        const val UNIQUE_WORK_NAME = "tgstorage_auto_backup"
        const val UNIQUE_WORK_NAME_IMMEDIATE = "tgstorage_auto_backup_immediate"
        private const val NOTIFICATION_CHANNEL_ID = "tgstorage_backup_channel"
        private const val NOTIFICATION_ID = 1002

        /**
         * Run an IMMEDIATE one-time scan and upload.
         * Called when user first enables auto-upload.
         */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediateRequest = androidx.work.OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME_IMMEDIATE,
                androidx.work.ExistingWorkPolicy.REPLACE,
                immediateRequest,
            )
            Log.d(TAG, "Auto-backup running immediately")
        }

        /**
         * Schedule periodic auto-backup every 15 minutes.
         * Runs even when app is closed.
         */
        fun schedule(context: Context) {
            // First run immediately
            runNow(context)

            // Then schedule periodic
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                repeatInterval = 10,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.d(TAG, "Auto-backup scheduled (every 15 min)")
        }

        /** Cancel the periodic auto-backup. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Auto-backup cancelled")
        }

        /** Check if auto-backup is currently scheduled. */
        fun isScheduled(context: Context): Boolean {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
                .get()
            return workInfos.any { !it.state.isFinished }
        }

        private fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Auto Backup",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows progress while backing up new files"
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("TgStorage Backup")
            .setContentText("Scanning for new files...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "AutoBackupWorker started (attempt $runAttemptCount)")

        // Run as foreground service so it continues when app is closed
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground: ${e.message}")
        }

        val app = TgStorageApp.instance
        val db = app.database
        val metadataDao = db.metadataDao()

        // Check if auto-upload is still enabled
        val autoUploadEnabled = metadataDao.getValue(MetadataKeys.AUTO_UPLOAD)?.toBoolean() ?: false
        if (!autoUploadEnabled) {
            Log.d(TAG, "Auto-upload disabled, skipping")
            return@withContext Result.success()
        }

        // Get ALL file names already in database (uploaded, pending, failed) to avoid re-importing
        val existingNames = db.fileDao().getAllFileNamesSync().toSet()
        Log.d(TAG, "Found ${existingNames.size} files already in database")

        // Scan device for all images, videos, audio
        val scanner = DeviceFileScanner(applicationContext)
        
        // Scan images
        val images = scanner.scanFiles(mimeFilter = "image/")
        val videos = scanner.scanFiles(mimeFilter = "video/")
        val audio = scanner.scanFiles(mimeFilter = "audio/")
        
        val allDeviceFiles = (images + videos + audio).distinctBy { it.name }
        Log.d(TAG, "Found ${allDeviceFiles.size} media files on device")

        // Find files not yet in database
        val newFiles = allDeviceFiles.filter { it.name !in existingNames }
        Log.d(TAG, "Found ${newFiles.size} new files to backup")

        if (newFiles.isEmpty()) {
            Log.d(TAG, "No new files to backup")
            return@withContext Result.success()
        }

        // Import new files to database and queue for upload
        val repository = FileRepository(app, db.fileDao(), db.syncStateDao())
        var importedCount = 0
        var failedCount = 0

        for (file in newFiles) {
            try {
                // Use file path directly when available (more efficient - no copy needed)
                val result = if (file.path.isNotBlank() && java.io.File(file.path).exists()) {
                    repository.importFileFromPath(file.path, file.name, file.size, file.mimeType)
                } else {
                    // Fall back to content URI
                    repository.importFile(file.contentUri)
                }
                
                result.onSuccess { entity ->
                    // Queue for upload via TransferManager
                    TransferManager.enqueueUpload(entity)
                    importedCount++
                    Log.d(TAG, "Imported and queued: ${file.name}")
                }.onFailure { e ->
                    failedCount++
                    Log.w(TAG, "Failed to import ${file.name}: ${e.message}")
                }
            } catch (e: Exception) {
                failedCount++
                Log.w(TAG, "Exception importing ${file.name}: ${e.message}")
            }

            // Small delay to avoid overwhelming the system
            kotlinx.coroutines.delay(100)
        }

        Log.d(TAG, "Auto-backup complete: $importedCount imported, $failedCount failed")

        // Update notification with result
        if (importedCount > 0) {
            showCompletionNotification(importedCount)
        }

        Result.success()
    }

    private fun showCompletionNotification(count: Int) {
        createNotificationChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("TgStorage Backup")
            .setContentText("$count new file(s) queued for upload")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID + 1, notification)
    }
}
