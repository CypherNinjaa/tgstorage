package com.tgstorage.ui.download

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.common.NetworkMonitor
import com.tgstorage.common.StorageUtils
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.repository.FileRepository
import com.tgstorage.data.transfer.TransferManager
import com.tgstorage.data.transfer.TransferProgress
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DownloadUiState(
    val file: FileEntity? = null,
    val isLoading: Boolean = true,
    val isDownloading: Boolean = false,
    val downloadProgress: TransferProgress? = null,
    val downloadComplete: Boolean = false,
    val savedPath: String? = null,
    val error: String? = null,
)

class DownloadViewModel(
    private val fileId: Long,
    private val fileRepository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    init {
        loadFile()
    }

    private fun loadFile() {
        viewModelScope.launch {
            try {
                val file = fileRepository.getFileById(fileId)
                _uiState.update { it.copy(file = file, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load file: ${e.message}",
                    )
                }
            }
        }
    }

    fun startDownload() {
        val file = _uiState.value.file ?: return

        viewModelScope.launch {
            // Phase 9: Check network before download
            val context = TgStorageApp.instance
            val isOnline = NetworkMonitor(context).isOnline.first()
            if (!isOnline) {
                _uiState.update {
                    it.copy(error = "No internet connection. Please connect and try again.")
                }
                return@launch
            }

            // Phase 9: Check storage space before download
            if (!StorageUtils.hasEnoughSpace(context, file.size, useExternal = true)) {
                val available = StorageUtils.formatBytes(StorageUtils.getAvailableExternalStorage(context))
                val needed = StorageUtils.formatBytes(file.size)
                _uiState.update {
                    it.copy(error = "Not enough storage space. Need $needed but only $available available.")
                }
                return@launch
            }

            _uiState.update { it.copy(isDownloading = true, error = null) }
            try {
                // Destination: app-private files dir; user can export later
                val destDir = File(
                    TgStorageApp.instance.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "tgstorage",
                )
                destDir.mkdirs()
                val destFile = File(destDir, file.name)

                TransferManager.enqueueDownload(
                    file = file,
                    outputFile = destFile,
                )

                // Observe progress
                TransferManager.transfers.collect { list ->
                    val progress = list.find { it.fileId == file.id }
                    if (progress != null) {
                        _uiState.update {
                            it.copy(
                                downloadProgress = progress,
                                downloadComplete = progress.status == TransferStatus.COMPLETED,
                                savedPath = if (progress.status == TransferStatus.COMPLETED) destFile.absolutePath else null,
                                isDownloading = progress.status == TransferStatus.IN_PROGRESS || progress.status == TransferStatus.PENDING,
                                error = if (progress.status == TransferStatus.FAILED) progress.error else null,
                            )
                        }
                        if (progress.status == TransferStatus.COMPLETED ||
                            progress.status == TransferStatus.FAILED ||
                            progress.status == TransferStatus.CANCELLED
                        ) {
                            return@collect
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        error = "Download failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        val fileId = _uiState.value.file?.id ?: return
        TransferManager.cancelTransfer(fileId, TransferType.DOWNLOAD)
        _uiState.update { it.copy(isDownloading = false) }
    }

    fun retry() {
        _uiState.update { it.copy(error = null, downloadComplete = false, savedPath = null) }
        startDownload()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    class Factory(private val fileId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = TgStorageApp.instance.database
            val repo = FileRepository(
                context = TgStorageApp.instance,
                fileDao = db.fileDao(),
                syncStateDao = db.syncStateDao(),
            )
            return DownloadViewModel(fileId, repo) as T
        }
    }
}
