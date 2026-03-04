package com.tgstorage.ui.trash

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

data class TrashUiState(
    val files: List<FileEntity> = emptyList(),
    val isLoading: Boolean = true,
    val selectedIds: Set<Long> = emptySet(),
    val selectionMode: Boolean = false,
    val message: String? = null,
)

class TrashViewModel(
    private val repository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                val repository = FileRepository(app, db.fileDao(), db.syncStateDao())
                return TrashViewModel(repository) as T
            }
        }
    }

    init {
        observeTrash()
    }

    private fun observeTrash() {
        viewModelScope.launch {
            repository.getTrashedFiles().collect { files ->
                _uiState.update {
                    it.copy(files = files, isLoading = false)
                }
            }
        }
    }

    fun toggleSelection(fileId: Long) {
        _uiState.update { state ->
            val newSelected = state.selectedIds.toMutableSet()
            if (fileId in newSelected) newSelected.remove(fileId) else newSelected.add(fileId)
            state.copy(selectedIds = newSelected, selectionMode = newSelected.isNotEmpty())
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedIds = state.files.map { it.id }.toSet(), selectionMode = true)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet(), selectionMode = false) }
    }

    fun restoreSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.restoreFiles(ids)
            _uiState.update {
                it.copy(
                    selectedIds = emptySet(),
                    selectionMode = false,
                    message = "${ids.size} file(s) restored",
                )
            }
        }
    }

    fun restoreFile(fileId: Long) {
        viewModelScope.launch {
            repository.restoreFile(fileId)
            _uiState.update { it.copy(message = "File restored") }
        }
    }

    fun permanentlyDelete(fileId: Long) {
        viewModelScope.launch {
            repository.permanentlyDeleteFile(fileId)
            _uiState.update { it.copy(message = "File permanently deleted") }
        }
    }

    fun permanentlyDeleteSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            for (id in ids) {
                repository.permanentlyDeleteFile(id)
            }
            _uiState.update {
                it.copy(
                    selectedIds = emptySet(),
                    selectionMode = false,
                    message = "${ids.size} file(s) permanently deleted",
                )
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val count = repository.emptyTrash()
            _uiState.update { it.copy(message = "$count file(s) permanently deleted") }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
