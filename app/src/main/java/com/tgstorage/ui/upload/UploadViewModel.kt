package com.tgstorage.ui.upload

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.common.NetworkMonitor
import com.tgstorage.common.StorageUtils
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.repository.FileRepository
import com.tgstorage.data.transfer.ChunkManager
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

// ── UI state ───────────────────────────────────────────

data class UploadUiState(
    val selectedFile: FileEntity? = null,
    val isImporting: Boolean = false,
    val isUploading: Boolean = false,
    val uploadProgress: TransferProgress? = null,
    val estimatedChunks: Int = 0,
    val error: String? = null,
    val uploadComplete: Boolean = false,
    val uploadedFileId: Long? = null,
)

// ── ViewModel ──────────────────────────────────────────

class UploadViewModel(
    private val fileRepository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    /**
     * Called when user picks a file via ACTION_OPEN_DOCUMENT.
     * Imports the file to app-private storage and shows preview.
     * Phase 9: checks available storage before importing.
     */
    fun onFilePicked(uri: Uri) {
        _uiState.update { it.copy(isImporting = true, error = null, selectedFile = null) }

        viewModelScope.launch {
            // Phase 9: Check available storage before importing
            val context = TgStorageApp.instance
            val fileSize = resolveFileSize(context, uri)
            if (fileSize > 0 && !StorageUtils.hasEnoughSpace(context, fileSize)) {
                val available = StorageUtils.formatBytes(StorageUtils.getAvailableInternalStorage(context))
                val needed = StorageUtils.formatBytes(fileSize)
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        error = "Not enough storage space. Need $needed but only $available available.",
                    )
                }
                return@launch
            }

            fileRepository.importFile(uri)
                .onSuccess { file ->
                    val chunks = ((file.size + ChunkManager.DEFAULT_CHUNK_SIZE - 1) /
                            ChunkManager.DEFAULT_CHUNK_SIZE).toInt()
                    _uiState.update {
                        it.copy(
                            selectedFile = file,
                            isImporting = false,
                            estimatedChunks = chunks,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            error = e.message ?: "Failed to import file",
                        )
                    }
                }
        }
    }

    /**
     * Starts uploading the imported file to Telegram via TransferManager.
     * Phase 9: validates network connectivity before enqueuing.
     */
    fun startUpload() {
        val file = _uiState.value.selectedFile ?: return

        viewModelScope.launch {
            // Phase 9: Check network before upload
            val isOnline = NetworkMonitor(TgStorageApp.instance).isOnline.first()
            if (!isOnline) {
                _uiState.update {
                    it.copy(error = "No internet connection. Please connect and try again.")
                }
                return@launch
            }
            _uiState.update { it.copy(isUploading = true, error = null) }
            TransferManager.enqueueUpload(file)
        }

        // Observe progress from TransferManager
        viewModelScope.launch {
            TransferManager.transfers.collect { transfers ->
                val progress = transfers.find {
                    it.fileId == file.id && it.type == TransferType.UPLOAD
                }
                if (progress != null) {
                    _uiState.update {
                        it.copy(
                            uploadProgress = progress,
                            isUploading = progress.status == TransferStatus.IN_PROGRESS ||
                                    progress.status == TransferStatus.PENDING,
                            uploadComplete = progress.status == TransferStatus.COMPLETED,
                            uploadedFileId = if (progress.status == TransferStatus.COMPLETED)
                                file.id else null,
                            error = if (progress.status == TransferStatus.FAILED)
                                progress.error else null,
                        )
                    }
                }
            }
        }
    }

    fun cancelUpload() {
        val file = _uiState.value.selectedFile ?: return
        TransferManager.cancelTransfer(file.id, TransferType.UPLOAD)
        _uiState.update { it.copy(isUploading = false) }
    }

    fun retry() {
        startUpload()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Phase 9: resolve file size from content URI before import. */
    private fun resolveFileSize(context: android.content.Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                cursor.moveToFirst()
                if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    // ── Factory ────────────────────────────────────────

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                val repository = FileRepository(app, db.fileDao(), db.syncStateDao())
                return UploadViewModel(repository) as T
            }
        }
    }
}
