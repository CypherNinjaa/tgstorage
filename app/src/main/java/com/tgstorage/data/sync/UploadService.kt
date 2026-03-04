package com.tgstorage.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tgstorage.MainActivity
import com.tgstorage.data.transfer.TransferManager
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app process alive while
 * uploads are running — even after the user swipes the app away.
 *
 * How it works:
 * ─────────────
 * 1. Started via [start] when uploads are enqueued.
 * 2. Immediately promotes itself to a foreground service with a persistent notification.
 * 3. Holds a partial wake lock so the CPU stays on.
 * 4. Polls [TransferManager.transfers] every 2 s to update the notification.
 * 5. Automatically stops itself when no uploads remain (pending / in-progress).
 *
 * This does NOT duplicate upload logic — [TransferManager] remains the engine.
 * This service only keeps the process alive + shows a notification.
 */
class UploadService : Service() {

    companion object {
        private const val TAG = "UploadService"
        private const val NOTIFICATION_CHANNEL_ID = "tgstorage_upload_channel"
        private const val NOTIFICATION_ID = 2001
        private const val POLL_INTERVAL_MS = 2_000L
        private const val IDLE_GRACE_MS = 10_000L // wait 10 s after last upload before stopping

        // Intent actions for notification buttons
        const val ACTION_PAUSE = "com.tgstorage.UPLOAD_PAUSE"
        const val ACTION_RESUME = "com.tgstorage.UPLOAD_RESUME"

        /** Convenience: start the service if not already running. */
        fun start(context: Context) {
            val intent = Intent(context, UploadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Upload service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start upload service: ${e.message}")
            }
        }

        /** Stop the service explicitly. */
        fun stop(context: Context) {
            context.stopService(Intent(context, UploadService::class.java))
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "File Uploads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows progress while uploading files to Telegram"
                    setShowBadge(false)
                }
                val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var idleSince: Long = 0L
    @Volatile
    private var isPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
        Log.d(TAG, "UploadService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle notification action buttons
        when (intent?.action) {
            ACTION_PAUSE -> {
                isPaused = true
                TransferManager.pauseAll()
                Log.d(TAG, "Uploads paused via notification")
                return START_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                TransferManager.resumeAll()
                Log.d(TAG, "Uploads resumed via notification")
                return START_STICKY
            }
        }

        promoteToForeground()
        acquireWakeLock()
        startProgressPolling()
        return START_STICKY // restart if killed
    }

    override fun onDestroy() {
        pollJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        Log.d(TAG, "UploadService destroyed")
        super.onDestroy()
    }

    // ─── Foreground Notification ───────────────────────

    private fun promoteToForeground() {
        val notification = buildNotification(
            title = "Uploading files…",
            text = "Preparing uploads",
            progress = -1, // indeterminate
            max = 0,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        max: Int,
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .apply {
                if (progress < 0) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(max, progress, false)
                }
            }

        // Add pause / resume action button
        if (isPaused) {
            val resumeIntent = Intent(this, UploadService::class.java).apply {
                action = ACTION_RESUME
            }
            val resumePi = PendingIntent.getService(
                this, 1, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Resume",
                resumePi,
            )
        } else {
            val pauseIntent = Intent(this, UploadService::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePi = PendingIntent.getService(
                this, 2, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                pausePi,
            )
        }

        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ─── Wake Lock ─────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TgStorage::UploadWakeLock",
            ).apply {
                acquire(4 * 60 * 60 * 1000L) // max 4 hours
            }
            Log.d(TAG, "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            Log.d(TAG, "Wake lock released")
        }
        wakeLock = null
    }

    // ─── Progress Polling ──────────────────────────────

    private fun startProgressPolling() {
        if (pollJob?.isActive == true) return

        pollJob = serviceScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)

                val transfers = TransferManager.transfers.value
                val uploads = transfers.filter { it.type == TransferType.UPLOAD }

                val inProgress = uploads.filter { it.status == TransferStatus.IN_PROGRESS }
                val pending = uploads.filter { it.status == TransferStatus.PENDING }
                val completed = uploads.filter { it.status == TransferStatus.COMPLETED }
                val failed = uploads.filter { it.status == TransferStatus.FAILED }

                val paused = uploads.filter { it.status == TransferStatus.PAUSED }
                val activeCount = inProgress.size + pending.size + paused.size

                if (activeCount > 0 || isPaused) {
                    // Reset idle timer
                    idleSince = 0L

                    if (isPaused) {
                        val pausedTotal = paused.size + inProgress.size + pending.size
                        updateNotification(
                            title = "Uploads paused ($pausedTotal file(s))",
                            text = "Tap Resume to continue" +
                                    if (completed.isNotEmpty()) " • ${completed.size} done" else "",
                            progress = -1,
                            max = 0,
                        )
                    } else {
                        // Compute aggregate byte progress
                        val totalBytes = uploads
                            .filter { it.status in setOf(TransferStatus.IN_PROGRESS, TransferStatus.COMPLETED) }
                            .sumOf { it.totalBytes }
                        val doneBytes = uploads
                            .filter { it.status in setOf(TransferStatus.IN_PROGRESS, TransferStatus.COMPLETED) }
                            .sumOf { it.bytesTransferred }

                        val percent = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt() else 0
                        val currentFile = inProgress.firstOrNull()?.fileName ?: "Uploading…"

                        val text = buildString {
                            append(currentFile)
                            if (completed.isNotEmpty()) append(" • ${completed.size} done")
                            if (pending.isNotEmpty()) append(" • ${pending.size} queued")
                        }

                        updateNotification(
                            title = "Uploading ${inProgress.size + pending.size} file(s)…",
                            text = text,
                            progress = percent,
                            max = 100,
                        )
                    }
                } else {
                    // No active uploads — start idle grace period
                    val now = System.currentTimeMillis()
                    if (idleSince == 0L) {
                        idleSince = now
                        updateNotification(
                            title = "Uploads finished",
                            text = "${completed.size} uploaded" +
                                    if (failed.isNotEmpty()) ", ${failed.size} failed" else "",
                            progress = 100,
                            max = 100,
                        )
                    } else if (now - idleSince > IDLE_GRACE_MS) {
                        // Grace period expired — stop service
                        Log.d(TAG, "No active uploads for ${IDLE_GRACE_MS}ms — stopping service")
                        stopSelf()
                        return@launch
                    }
                }
            }
        }
    }

    private fun updateNotification(title: String, text: String, progress: Int, max: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, text, progress, max))
    }
}
