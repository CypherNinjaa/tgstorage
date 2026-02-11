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
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.repository.SyncRepository
import com.tgstorage.data.repository.TelegramRepository
import com.tgstorage.data.transfer.ChunkManager
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import com.tgstorage.data.transfer.TransferProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based background sync worker with foreground notification.
 * Runs as a foreground service so uploads continue even when app is closed.
 * Respects Telegram rate limits by adding delays between uploads.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "SyncWorker"
        const val UNIQUE_WORK_NAME = "tgstorage_sync"
        private const val MAX_RETRY_COUNT = 5
        private const val DELAY_BETWEEN_UPLOADS_MS = 3500L // 3.5s between API calls to avoid rate-limiting
        private const val NOTIFICATION_CHANNEL_ID = "tgstorage_sync_channel"
        private const val NOTIFICATION_ID = 1001

        /** Schedule periodic sync every 15 minutes — runs even when app is closed. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = 15,
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
            Log.d(TAG, "Periodic sync scheduled (every 15 min, requires network)")
        }

        /** Cancel the periodic sync. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Periodic sync cancelled")
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "File Sync",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows progress while syncing files to Telegram"
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
            .setContentTitle("TgStorage Sync")
            .setContentText("Syncing files to Telegram...")
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
        Log.d(TAG, "SyncWorker started (attempt $runAttemptCount)")

        // Run as foreground service so it continues when app is closed
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

        // Check auto-sync setting
        if (!syncRepository.isAutoSyncEnabled()) {
            Log.d(TAG, "Auto-sync disabled, skipping")
            return Result.success()
        }

        val telegramRepository = TelegramRepository(
            api = TelegramApiService(),
            metadataDao = db.metadataDao(),
        )

        val token = telegramRepository.getToken()
        val chatId = telegramRepository.getChatId()

        if (token == null || chatId == null) {
            Log.w(TAG, "Bot token or channel not configured, skipping sync")
            return Result.success()
        }

        val pendingFiles = syncRepository.getPendingUploads()
        if (pendingFiles.isEmpty()) {
            Log.d(TAG, "No pending uploads")
            return Result.success()
        }

        Log.d(TAG, "Processing ${pendingFiles.size} pending uploads")

        val chunkManager = ChunkManager(
            api = TelegramApiService(),
            chunkDao = db.chunkDao(),
            syncStateDao = db.syncStateDao(),
        )

        var successCount = 0
        var failCount = 0
        var rateLimitedCount = 0

        for ((index, syncState) in pendingFiles.withIndex()) {
            // Skip files that have been retried too many times
            if (syncState.retryCount >= MAX_RETRY_COUNT) {
                Log.w(TAG, "Skipping file ${syncState.fileId} — exceeded max retries (${syncState.retryCount})")
                continue
            }

            val fileEntity = db.fileDao().getFileById(syncState.fileId) ?: continue
            val localFile = fileEntity.localUri?.let { File(it) }

            if (localFile == null || !localFile.exists()) {
                syncRepository.markFailed(syncState.fileId, "Local file not found")
                failCount++
                continue
            }

            // Update notification
            try {
                val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("TgStorage Sync")
                    .setContentText("Uploading ${fileEntity.name} (${index + 1}/${pendingFiles.size})")
                    .setProgress(pendingFiles.size, index, false)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    ForegroundInfo(NOTIFICATION_ID, notification)
                }
                setForeground(info)
            } catch (_: Exception) { }

            val progressFlow = MutableStateFlow(
                TransferProgress(
                    fileId = fileEntity.id,
                    fileName = fileEntity.name,
                    type = TransferType.UPLOAD,
                    totalBytes = fileEntity.size,
                    status = TransferStatus.PENDING,
                )
            )

            try {
                chunkManager.uploadFile(
                    token = token,
                    chatId = chatId,
                    fileId = fileEntity.id,
                    localFile = localFile,
                    fileName = fileEntity.name,
                    progressFlow = progressFlow,
                )

                if (progressFlow.value.status == TransferStatus.COMPLETED) {
                    successCount++
                    Log.d(TAG, "Uploaded: ${fileEntity.name}")
                } else {
                    val error = progressFlow.value.error ?: "Upload failed"
                    // Check for rate limiting
                    val retryAfterSeconds = parseRetryAfter(error)
                    if (retryAfterSeconds != null) {
                        rateLimitedCount++
                        Log.w(TAG, "Rate limited, waiting ${retryAfterSeconds}s before next upload")
                        delay(retryAfterSeconds * 1000L + 1000L) // Wait retry_after + 1s buffer
                        // Don't mark as failed — leave as pending for next attempt
                        syncRepository.markFailed(syncState.fileId, error)
                    } else {
                        syncRepository.markFailed(syncState.fileId, error)
                    }
                    failCount++
                    Log.w(TAG, "Failed to upload: ${fileEntity.name} — $error")
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                val retryAfterSeconds = parseRetryAfter(errorMsg)
                if (retryAfterSeconds != null) {
                    rateLimitedCount++
                    Log.w(TAG, "Rate limited (exception), waiting ${retryAfterSeconds}s")
                    delay(retryAfterSeconds * 1000L + 1000L)
                }
                syncRepository.markFailed(syncState.fileId, errorMsg)
                failCount++
                Log.e(TAG, "Exception uploading ${fileEntity.name}", e)
            }

            // Delay between uploads to avoid Telegram rate-limiting
            if (index < pendingFiles.size - 1) {
                delay(DELAY_BETWEEN_UPLOADS_MS)
            }
        }

        Log.d(TAG, "SyncWorker finished — $successCount uploaded, $failCount failed, $rateLimitedCount rate-limited")
        return if (failCount > 0 && successCount == 0) Result.retry() else Result.success()
    }

    /**
     * Parse "retry after X" from Telegram error messages.
     * Returns seconds to wait, or null if not a rate-limit error.
     */
    private fun parseRetryAfter(error: String): Long? {
        val regex = Regex("retry after (\\d+)", RegexOption.IGNORE_CASE)
        return regex.find(error)?.groupValues?.get(1)?.toLongOrNull()
    }
}
