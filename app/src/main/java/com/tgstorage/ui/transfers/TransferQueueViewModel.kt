package com.tgstorage.ui.transfers

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.dao.UploadedFileInfo
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.repository.FileRepository
import com.tgstorage.data.transfer.TransferManager
import com.tgstorage.data.transfer.TransferProgress
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransfersUiState(
    val transfers: List<TransferProgress> = emptyList(),
    val uploadedFiles: List<UploadedFileInfo> = emptyList(),
    val selectedTab: Int = 0, // 0 = Transfers, 1 = Uploaded
    val downloadingIds: Set<Long> = emptySet(), // file IDs currently downloading
)

class TransferQueueViewModel(
    private val repository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransfersUiState())
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    init {
        observeTransfers()
        observeUploadedFiles()
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun cancelTransfer(fileId: Long, type: TransferType = TransferType.UPLOAD) {
        TransferManager.cancelTransfer(fileId, type)
    }

    fun clearFinished() {
        TransferManager.clearFinished()
    }

    fun isActive(progress: TransferProgress): Boolean =
        progress.status == TransferStatus.IN_PROGRESS ||
            progress.status == TransferStatus.PENDING ||
            progress.status == TransferStatus.PAUSED

    fun enqueueDownload(file: UploadedFileInfo) {
        val app = TgStorageApp.instance
        val outputDir = java.io.File(
            app.getExternalFilesDir(null) ?: app.filesDir, "downloads"
        )
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = java.io.File(outputDir, file.name)

        // Mark this file as downloading
        _uiState.update { it.copy(downloadingIds = it.downloadingIds + file.id) }

        // Create a FileEntity for TransferManager
        val entity = FileEntity(
            id = file.id,
            name = file.name,
            size = file.size,
            mimeType = file.mimeType,
            localUri = file.localUri,
        )
        TransferManager.enqueueDownload(entity, outputFile)

        // Switch to Transfers tab to show progress
        _uiState.update { it.copy(selectedTab = 0) }

        // Monitor for completion and open file
        viewModelScope.launch {
            TransferManager.transfers.collect { transfers ->
                val dl = transfers.find { it.fileId == file.id && it.type == TransferType.DOWNLOAD }
                if (dl?.status == TransferStatus.COMPLETED) {
                    _uiState.update { it.copy(downloadingIds = it.downloadingIds - file.id) }
                    openFile(outputFile, file.mimeType)
                    return@collect
                }
                if (dl?.status == TransferStatus.FAILED || dl?.status == TransferStatus.CANCELLED) {
                    _uiState.update { it.copy(downloadingIds = it.downloadingIds - file.id) }
                    return@collect
                }
            }
        }
    }

    private fun openFile(file: java.io.File, mimeType: String) {
        try {
            val app = TgStorageApp.instance
            val uri: Uri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(chooser)
        } catch (e: Exception) {
            // Silently fail if no app can handle the file
        }
    }

    private fun observeTransfers() {
        viewModelScope.launch {
            TransferManager.transfers.collect { transfers ->
                _uiState.update { it.copy(transfers = transfers) }
            }
        }
    }

    private fun observeUploadedFiles() {
        viewModelScope.launch {
            repository.getUploadedFilesDetailed().collect { files ->
                _uiState.update { it.copy(uploadedFiles = files) }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                val repository = FileRepository(app, db.fileDao(), db.syncStateDao())
                return TransferQueueViewModel(repository) as T
            }
        }
    }
}
