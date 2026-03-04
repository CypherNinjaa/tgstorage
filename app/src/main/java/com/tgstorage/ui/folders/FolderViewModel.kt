package com.tgstorage.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.dao.FolderDao
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.local.entity.FolderEntity
import com.tgstorage.data.repository.FileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderUiState(
    val currentFolder: FolderEntity? = null, // null = root
    val breadcrumbs: List<FolderEntity> = emptyList(), // navigation trail
    val subFolders: List<FolderEntity> = emptyList(),
    val files: List<FileEntity> = emptyList(),
    val allFolders: List<FolderEntity> = emptyList(), // for move-to picker
    val isLoading: Boolean = true,
    val message: String? = null,
    val showCreateDialog: Boolean = false,
    val showRenameDialog: FolderEntity? = null,
    val showMoveDialog: Boolean = false,
    val selectedFileIds: Set<Long> = emptySet(),
    val selectionMode: Boolean = false,
)

class FolderViewModel(
    private val folderDao: FolderDao,
    private val repository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderUiState())
    val uiState: StateFlow<FolderUiState> = _uiState.asStateFlow()

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                return FolderViewModel(
                    folderDao = db.folderDao(),
                    repository = FileRepository(app, db.fileDao(), db.syncStateDao()),
                ) as T
            }
        }
    }

    init {
        loadFolder(null)
    }

    fun loadFolder(folder: FolderEntity?) {
        _uiState.update { it.copy(currentFolder = folder, isLoading = true) }

        viewModelScope.launch {
            // Build breadcrumb trail
            val breadcrumbs = buildBreadcrumbs(folder)

            // Observe subfolders
            val subFoldersFlow = if (folder == null) {
                folderDao.getRootFolders()
            } else {
                folderDao.getSubFolders(folder.id)
            }

            // Start collecting subfolders
            launch {
                subFoldersFlow.collect { subs ->
                    _uiState.update { it.copy(subFolders = subs, isLoading = false) }
                }
            }

            // Observe files in this folder
            val filesFlow = if (folder == null) {
                repository.getAllFiles() // For root, show unfoldered files — will be filtered
            } else {
                com.tgstorage.TgStorageApp.instance.database.fileDao()
                    .getFilesInFolder(folder.id)
            }

            launch {
                filesFlow.collect { files ->
                    // For root view, filter to only unfoldered + non-trashed
                    val filtered = if (folder == null) {
                        files.filter { it.folderId == null && it.trashedAt == null }
                    } else {
                        files
                    }
                    _uiState.update { it.copy(files = filtered) }
                }
            }

            _uiState.update { it.copy(breadcrumbs = breadcrumbs) }
        }
    }

    private suspend fun buildBreadcrumbs(folder: FolderEntity?): List<FolderEntity> {
        if (folder == null) return emptyList()
        val trail = mutableListOf(folder)
        var current = folder
        while (current?.parentId != null) {
            current = folderDao.getById(current.parentId!!)
            if (current != null) trail.add(0, current)
        }
        return trail
    }

    fun navigateUp(): Boolean {
        val current = _uiState.value.currentFolder ?: return false
        val parentId = current.parentId
        viewModelScope.launch {
            val parent = if (parentId != null) folderDao.getById(parentId) else null
            loadFolder(parent)
        }
        return true
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val parentId = _uiState.value.currentFolder?.id
            folderDao.insert(
                FolderEntity(
                    name = name.trim(),
                    parentId = parentId,
                )
            )
            _uiState.update {
                it.copy(showCreateDialog = false, message = "Folder '$name' created")
            }
        }
    }

    fun renameFolder(folder: FolderEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            folderDao.update(folder.copy(name = newName.trim(), updatedAt = System.currentTimeMillis()))
            _uiState.update {
                it.copy(showRenameDialog = null, message = "Folder renamed to '$newName'")
            }
        }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch {
            folderDao.delete(folder)
            _uiState.update { it.copy(message = "Folder '${folder.name}' deleted") }
        }
    }

    fun showCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = true) }
    }

    fun hideCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = false) }
    }

    fun showRenameDialog(folder: FolderEntity) {
        _uiState.update { it.copy(showRenameDialog = folder) }
    }

    fun hideRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = null) }
    }

    // ── File operations within folders ──────────────────

    fun toggleFileSelection(fileId: Long) {
        _uiState.update { state ->
            val newSelected = state.selectedFileIds.toMutableSet()
            if (fileId in newSelected) newSelected.remove(fileId) else newSelected.add(fileId)
            state.copy(selectedFileIds = newSelected, selectionMode = newSelected.isNotEmpty())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedFileIds = emptySet(), selectionMode = false) }
    }

    fun showMoveDialog() {
        viewModelScope.launch {
            val allFolders = folderDao.getAllFoldersSync()
            _uiState.update { it.copy(showMoveDialog = true, allFolders = allFolders) }
        }
    }

    fun hideMoveDialog() {
        _uiState.update { it.copy(showMoveDialog = false) }
    }

    fun moveSelectedToFolder(targetFolderId: Long?) {
        val ids = _uiState.value.selectedFileIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.moveFilesToFolder(ids, targetFolderId)
            _uiState.update {
                it.copy(
                    selectedFileIds = emptySet(),
                    selectionMode = false,
                    showMoveDialog = false,
                    message = "${ids.size} file(s) moved",
                )
            }
        }
    }

    fun trashSelected() {
        val ids = _uiState.value.selectedFileIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.trashFiles(ids)
            _uiState.update {
                it.copy(
                    selectedFileIds = emptySet(),
                    selectionMode = false,
                    message = "${ids.size} file(s) moved to trash",
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
