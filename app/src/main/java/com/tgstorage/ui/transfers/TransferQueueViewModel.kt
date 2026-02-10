package com.tgstorage.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
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
    val uploadedFiles: List<FileEntity> = emptyList(),
    val selectedTab: Int = 0, // 0 = Transfers, 1 = Uploaded
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

    fun enqueueDownload(file: FileEntity) {
        val app = TgStorageApp.instance
        val outputDir = java.io.File(
            app.getExternalFilesDir(null) ?: app.filesDir, "downloads"
        )
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = java.io.File(outputDir, file.name)
        TransferManager.enqueueDownload(file, outputFile)
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
            repository.getUploadedFiles().collect { files ->
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
