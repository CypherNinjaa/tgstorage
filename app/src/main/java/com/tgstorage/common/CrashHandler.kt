package com.tgstorage.common

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught exception handler that logs crashes to a file
 * before letting the system handle them. This helps diagnose
 * crashes in release builds where logcat isn't available.
 *
 * Phase 9 — Hardening: zero critical crashes goal.
 */
class CrashHandler private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_LOG_DIR = "crash_logs"
        private const val MAX_LOG_FILES = 5

        fun install(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(context.applicationContext, defaultHandler)
            )
            Log.d(TAG, "CrashHandler installed")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashLog(thread, throwable)
            cleanupTempFiles()
        } catch (_: Exception) {
            // Don't let crash logging itself crash
        }

        // Delegate to the system default handler (shows ANR dialog or kills app)
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        val logDir = File(context.filesDir, CRASH_LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()

        // Maintain only the last N crash logs
        val existingLogs = logDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        if (existingLogs.size >= MAX_LOG_FILES) {
            existingLogs.take(existingLogs.size - MAX_LOG_FILES + 1).forEach { it.delete() }
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val fileName = "crash_${dateFormat.format(Date())}.log"
        val logFile = File(logDir, fileName)

        FileWriter(logFile).use { writer ->
            val pw = PrintWriter(writer)
            pw.println("=== TgStorage Crash Report ===")
            pw.println("Time: ${Date()}")
            pw.println("Thread: ${thread.name} (id=${thread.id})")
            pw.println("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            pw.println()
            pw.println("--- Exception ---")
            throwable.printStackTrace(pw)
            pw.flush()
        }

        Log.e(TAG, "Crash log saved: ${logFile.absolutePath}")
    }

    /**
     * Best-effort cleanup of temp/chunk files on crash
     * to prevent storage leaks from interrupted operations.
     */
    private fun cleanupTempFiles() {
        try {
            // Clean up any temp chunk directories
            val filesDir = context.filesDir
            val importedDir = File(filesDir, "imported")
            importedDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.startsWith("chunks_")) {
                    file.deleteRecursively()
                }
            }

            // Clean up cache temp files
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.startsWith("tmp_") || file.name.startsWith("enc_"))) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
            // Ignore cleanup errors during crash
        }
    }
}
