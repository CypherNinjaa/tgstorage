package com.tgstorage.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tgstorage.MainActivity
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.data.repository.FileRepository
import com.tgstorage.data.scanner.DeviceFileScanner
import com.tgstorage.data.transfer.TransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that detects new photos, videos, and documents
 * added to the device since the last scan.
 *
 * Behaviour:
 * ──────────
 * • If auto-upload is ON  → immediately enqueue new files for upload.
 * • If auto-upload is OFF → show a notification "N new files detected — Upload now?"
 *
 * Runs every 15 minutes. Lightweight — only queries MediaStore, no heavy IO.
 */
class NewMediaWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "NewMediaWorker"
        const val UNIQUE_WORK_NAME = "tgstorage_new_media_detect"
        private const val NOTIFICATION_CHANNEL_ID = "tgstorage_new_media_channel"
        private const val NOTIFICATION_ID = 2002
        private const val LAST_SCAN_KEY = "last_media_scan_timestamp"

        /** Schedule periodic new-media detection every 15 minutes. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<NewMediaWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.d(TAG, "New media detection scheduled (every 15 min)")
        }

        /** Cancel detection. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "New media detection cancelled")
        }

        /** Run a one-shot scan immediately (e.g. on app open). */
        fun runOnce(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<NewMediaWorker>()
                .setConstraints(constraints)
                .addTag("${TAG}_oneshot")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${UNIQUE_WORK_NAME}_oneshot",
                ExistingWorkPolicy.REPLACE,
                request,
            )
            Log.d(TAG, "One-shot new media scan queued")
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "New Files Detected",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifies when new photos, videos, or documents are found on device"
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scanning for new media…")

        val db = TgStorageApp.instance.database
        val metadataDao = db.metadataDao()

        // Check if onboarding complete (bot + channel configured)
        val onboarded = metadataDao.getValue(MetadataKeys.ONBOARDING_COMPLETED)?.toBoolean() ?: false
        if (!onboarded) {
            Log.d(TAG, "Onboarding not complete, skipping")
            return@withContext Result.success()
        }

        // Get last scan timestamp
        val lastScan = metadataDao.getValue(LAST_SCAN_KEY)?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()

        // Scan device files
        val scanner = DeviceFileScanner(applicationContext)
        val allFiles = scanner.scanFiles()

        // Filter to files modified since last scan
        val newFiles = if (lastScan > 0L) {
            allFiles.filter { it.dateModified * 1000 > lastScan } // dateModified is in seconds
        } else {
            // First run — don't flood with all files; just record timestamp
            metadataDao.setValue(MetadataEntity(LAST_SCAN_KEY, now.toString()))
            Log.d(TAG, "First scan — recorded baseline timestamp, ${allFiles.size} total files on device")
            return@withContext Result.success()
        }

        if (newFiles.isEmpty()) {
            Log.d(TAG, "No new files since last scan")
            metadataDao.setValue(MetadataEntity(LAST_SCAN_KEY, now.toString()))
            return@withContext Result.success()
        }

        Log.i(TAG, "Found ${newFiles.size} new file(s) since last scan")

        // Filter out files already in our DB (by name + size match)
        val existingFiles = db.fileDao().getAllFilesSync()
        val existingKeys = existingFiles.map { "${it.name}_${it.size}" }.toSet()
        val trulyNew = newFiles.filter { "${it.name}_${it.size}" !in existingKeys }

        if (trulyNew.isEmpty()) {
            Log.d(TAG, "All new files already tracked in DB")
            metadataDao.setValue(MetadataEntity(LAST_SCAN_KEY, now.toString()))
            return@withContext Result.success()
        }

        // Check auto-upload setting
        val autoUpload = metadataDao.getValue(MetadataKeys.AUTO_UPLOAD)?.toBoolean() ?: false

        if (autoUpload) {
            // Auto-upload: import + enqueue all new files
            val repository = FileRepository(
                applicationContext,
                db.fileDao(),
                db.syncStateDao(),
            )

            var imported = 0
            for (file in trulyNew) {
                repository.importFile(file.contentUri)
                    .onSuccess { entity ->
                        TransferManager.enqueueUpload(entity)
                        imported++
                    }
                    .onFailure { e ->
                        Log.w(TAG, "Failed to import ${file.name}: ${e.message}")
                    }
            }

            Log.i(TAG, "Auto-uploaded $imported new file(s)")
        } else {
            // Show notification prompting upload
            showNewFilesNotification(trulyNew.size)
        }

        // Update scan timestamp
        metadataDao.setValue(MetadataEntity(LAST_SCAN_KEY, now.toString()))
        return@withContext Result.success()
    }

    private fun showNewFilesNotification(count: Int) {
        createNotificationChannel(applicationContext)

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            applicationContext, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val fileWord = if (count == 1) "file" else "files"
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("$count new $fileWord detected")
            .setContentText("Open TgStorage to upload to Telegram")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }
}
