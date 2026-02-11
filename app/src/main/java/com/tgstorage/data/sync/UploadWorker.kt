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
import com.tgstorage.data.repository.SyncRepository
import com.tgstorage.data.transfer.ChunkManager
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based background upload worker with foreground notification.
 * Runs as a foreground service so uploads continue even when app is closed.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "UploadWorker"
        const val UNIQUE_WORK_NAME = "tgstorage_upload"
        private const val NOTIFICATION_CHANNEL_ID = "tgstorage_upload_channel"
        private const val NOTIFICATION_ID = 1003
        private const val DELAY_BETWEEN_UPLOADS_MS = 3500L

        /** Schedule periodic upload every 10 minutes */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UploadWorker>(
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
            Log.d(TAG, "Periodic upload scheduled (every 10 min, requires network)")
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "File Upload",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows progress while uploading files to Telegram"
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
            .setContentTitle("TgStorage Upload")
            .setContentText("Uploading files to Telegram...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "UploadWorker started (attempt $runAttemptCount)")
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground: ${e.message}")
        }

        val db = TgStorageApp.instance.database
        val syncRepository = SyncRepository(
            syncStateDao = db.syncStateDao(),
            fileDao = db.fileDao(),
            metadataDao = db.metadataDao(),
        )

        val pendingUploads = syncRepository.getPendingUploads()
        if (pendingUploads.isEmpty()) {
            Log.d(TAG, "No pending uploads")
            return Result.success()
        }

        val chunkManager = ChunkManager(
            api = com.tgstorage.data.remote.TelegramApiService(),
            chunkDao = db.chunkDao(),
            syncStateDao = db.syncStateDao(),
        )

        for ((index, syncState) in pendingUploads.withIndex()) {
            val fileEntity = db.fileDao().getFileById(syncState.fileId) ?: continue
            val localFile = fileEntity.localUri?.let { File(it) }
            if (localFile == null || !localFile.exists()) {
                syncRepository.markFailed(syncState.fileId, "Local file not found")
                continue
            }
            try {
                chunkManager.uploadFile(fileEntity, localFile)
                syncRepository.markUploaded(syncState.fileId)
            } catch (e: Exception) {
                syncRepository.markFailed(syncState.fileId, e.message ?: "Upload failed")
            }
            delay(DELAY_BETWEEN_UPLOADS_MS)
        }
        Log.d(TAG, "UploadWorker finished")
        return Result.success()
    }
}
