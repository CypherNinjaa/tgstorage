package com.tgstorage.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.BackupFrequency
import com.tgstorage.data.local.entity.MetadataKeys
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based scheduled backup worker.
 * Supports daily, weekly, and monthly auto-backup of the encrypted Room DB.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "BackupWorker"
        const val UNIQUE_WORK_NAME = "tgstorage_auto_backup"
        private const val NOTIFICATION_CHANNEL_ID = "tgstorage_backup_channel"
        private const val NOTIFICATION_ID = 1002

        /**
         * Schedule automatic backup based on frequency.
         * @param frequency one of [BackupFrequency] constants: daily, weekly, monthly, off
         */
        fun schedule(context: Context, frequency: String) {
            val workManager = WorkManager.getInstance(context)

            if (frequency == BackupFrequency.OFF) {
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                Log.d(TAG, "Auto backup cancelled")
                return
            }

            val (interval, unit) = when (frequency) {
                BackupFrequency.DAILY -> 24L to TimeUnit.HOURS
                BackupFrequency.WEEKLY -> 7L to TimeUnit.DAYS
                BackupFrequency.MONTHLY -> 30L to TimeUnit.DAYS
                else -> {
                    workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                    return
                }
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                repeatInterval = interval,
                repeatIntervalTimeUnit = unit,
            )
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.d(TAG, "Auto backup scheduled ($frequency)")
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Auto Backup",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows progress during automatic database backup"
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
            .setContentText("Creating encrypted backup...")
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
        Log.d(TAG, "BackupWorker started (attempt $runAttemptCount)")

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground: ${e.message}")
        }

        val backupManager = BackupManager(applicationContext)
        val result = backupManager.createAndUploadBackup()

        return result.fold(
            onSuccess = { messageId ->
                Log.d(TAG, "Auto backup succeeded (message_id=$messageId)")

                // Show success notification
                try {
                    createNotificationChannel(applicationContext)
                    val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                        .setContentTitle("Backup Complete")
                        .setContentText("Database backed up to Telegram successfully")
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setAutoCancel(true)
                        .build()
                    nm.notify(NOTIFICATION_ID + 1, notification)
                } catch (_: Exception) { }

                Result.success()
            },
            onFailure = { e ->
                Log.e(TAG, "Auto backup failed: ${e.message}", e)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            },
        )
    }
}
