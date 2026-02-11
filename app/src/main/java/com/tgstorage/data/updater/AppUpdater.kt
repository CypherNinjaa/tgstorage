package com.tgstorage.data.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import com.tgstorage.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Checks GitHub releases for app updates and handles APK download + install.
 */
class AppUpdater(private val context: Context) {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val repo = BuildConfig.GITHUB_REPO

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Serializable
    data class GitHubRelease(
        val tag_name: String,
        val name: String? = null,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String,
        val browser_download_url: String,
        val size: Long = 0,
    )

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data class UpdateAvailable(
            val version: String,
            val releaseNotes: String,
            val downloadUrl: String,
            val sizeBytes: Long,
        ) : UpdateState()
        data object NoUpdate : UpdateState()
        data class Downloading(val progress: Int) : UpdateState()
        data object Installing : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    /**
     * Check GitHub releases API for a newer version.
     */
    suspend fun checkForUpdate() {
        _state.value = UpdateState.Checking
        try {
            val release = fetchLatestRelease()
            if (release == null) {
                _state.value = UpdateState.Error("Could not fetch release info")
                return
            }

            val latestVersion = release.tag_name.removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME

            if (isNewerVersion(latestVersion, currentVersion)) {
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                if (apkAsset != null) {
                    _state.value = UpdateState.UpdateAvailable(
                        version = latestVersion,
                        releaseNotes = release.body ?: "No release notes",
                        downloadUrl = apkAsset.browser_download_url,
                        sizeBytes = apkAsset.size,
                    )
                } else {
                    _state.value = UpdateState.Error("No APK found in release")
                }
            } else {
                _state.value = UpdateState.NoUpdate
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Download the APK using DownloadManager and install it.
     */
    fun downloadAndInstall(downloadUrl: String, version: String) {
        _state.value = UpdateState.Downloading(progress = 0)

        val fileName = "TgStorage-v$version.apk"
        val downloadManager = context.getSystemService<DownloadManager>() ?: run {
            _state.value = UpdateState.Error("Download manager unavailable")
            return
        }

        // Clean up any previous APK
        val destFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            fileName,
        )
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("TgStorage Update v$version")
            .setDescription("Downloading update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(destFile))

        val downloadId = downloadManager.enqueue(request)

        // Register receiver for download completion
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    installApk(destFile)
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0,
        )
    }

    private fun installApk(file: File) {
        _state.value = UpdateState.Installing
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            _state.value = UpdateState.Idle
        } catch (e: Exception) {
            _state.value = UpdateState.Error("Install failed: ${e.message}")
        }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null

        val body = response.body?.string() ?: return@withContext null
        json.decodeFromString<GitHubRelease>(body)
    }

    /**
     * Compare semantic versions: returns true if [latest] > [current].
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun resetState() {
        _state.value = UpdateState.Idle
    }
}
