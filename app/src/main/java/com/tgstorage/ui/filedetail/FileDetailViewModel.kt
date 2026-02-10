package com.tgstorage.ui.filedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.repository.FileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── UI state ───────────────────────────────────────────

sealed interface FileDetailUiState {
    data object Loading : FileDetailUiState
    data class Loaded(
        val file: FileEntity,
        val syncStatus: String?,
        val chunkCount: Int = 0,
    ) : FileDetailUiState
    data class Error(val message: String) : FileDetailUiState
}

// ── ViewModel ──────────────────────────────────────────

class FileDetailViewModel(
    private val fileId: Long,
    private val repository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FileDetailUiState>(FileDetailUiState.Loading)
    val uiState: StateFlow<FileDetailUiState> = _uiState.asStateFlow()

    init {
        loadFile()
    }

    fun loadFile() {
        _uiState.value = FileDetailUiState.Loading
        viewModelScope.launch {
            try {
                val file = repository.getFileById(fileId)
                if (file == null) {
                    _uiState.value = FileDetailUiState.Error("File not found")
                    return@launch
                }
                val sync = repository.getSyncState(fileId)
                _uiState.value = FileDetailUiState.Loaded(
                    file = file,
                    syncStatus = sync?.status,
                )
            } catch (e: Exception) {
                _uiState.value = FileDetailUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun deleteFile(onDeleted: () -> Unit) {
        val current = _uiState.value
        if (current is FileDetailUiState.Loaded) {
            viewModelScope.launch {
                repository.deleteFile(current.file)
                onDeleted()
            }
        }
    }

    // ── Factory ────────────────────────────────────────

    class Factory(private val fileId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = TgStorageApp.instance
            val db = app.database
            val repository = FileRepository(app, db.fileDao(), db.syncStateDao())
            return FileDetailViewModel(fileId, repository) as T
        }
    }
}
