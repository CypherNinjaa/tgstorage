package com.tgstorage.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.common.NetworkMonitor
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.local.entity.SyncStateEntity
import com.tgstorage.data.repository.FileRepository
import com.tgstorage.data.transfer.TransferManager
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── File type filter categories ────────────────────────

enum class FileFilter(val label: String, val mimePrefix: String?) {
    ALL("All", null),
    IMAGES("Images", "image/"),
    DOCUMENTS("Docs", "application/"),
    VIDEOS("Videos", "video/"),
    AUDIO("Audio", "audio/"),
}

// ── View mode toggle ───────────────────────────────────

enum class ViewMode { GRID, LIST }

// ── UI state ───────────────────────────────────────────

data class FileWithSync(
    val file: FileEntity,
    val syncStatus: String? = null,
)

data class HomeUiState(
    val files: List<FileWithSync> = emptyList(),
    val searchQuery: String = "",
    val activeFilter: FileFilter = FileFilter.ALL,
    val viewMode: ViewMode = ViewMode.LIST,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isOnline: Boolean = true,
    // Quick upload
    val isImporting: Boolean = false,
    val importedFile: FileEntity? = null,
    val isUploading: Boolean = false,
    val uploadMessage: String? = null,
    val activeUploads: Int = 0,
)

// ── ViewModel ──────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: FileRepository,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _activeFilter = MutableStateFlow(FileFilter.ALL)
    private val _viewMode = MutableStateFlow(ViewMode.LIST)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeFiles()
        observeNetwork()
        observeTransfers()
    }

    private fun observeFiles() {
        viewModelScope.launch {
            combine(_searchQuery, _activeFilter) { query, filter -> query to filter }
                .flatMapLatest { (query, filter) ->
                    when {
                        query.isNotBlank() -> repository.searchFiles(query)
                        filter.mimePrefix != null -> repository.getFilesByMimePrefix(filter.mimePrefix)
                        else -> repository.getAllFiles()
                    }
                }
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
                .collect { files ->
                    val db = TgStorageApp.instance.database
                    val filesWithSync = files.map { file ->
                        val sync = db.syncStateDao().getSyncState(file.id)
                        FileWithSync(file = file, syncStatus = sync?.status)
                    }
                    _uiState.update {
                        it.copy(
                            files = filesWithSync,
                            isLoading = false,
                            error = null,
                            searchQuery = _searchQuery.value,
                            activeFilter = _activeFilter.value,
                            viewMode = _viewMode.value,
                        )
                    }
                }
        }
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
            }
        }
    }

    private fun observeTransfers() {
        viewModelScope.launch {
            TransferManager.transfers.collect { transfers ->
                val active = transfers.count {
                    it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.PENDING
                }
                _uiState.update { it.copy(activeUploads = active) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onFilterChange(filter: FileFilter) {
        _activeFilter.value = filter
        _uiState.update { it.copy(activeFilter = filter) }
    }

    fun onViewModeToggle() {
        val next = if (_viewMode.value == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
        _viewMode.value = next
        _uiState.update { it.copy(viewMode = next) }
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        val currentQuery = _searchQuery.value
        _searchQuery.value = currentQuery
    }

    // ── Quick file import + upload ─────────────────────

    /**
     * Import a file from a content URI directly from the Home screen.
     * If [autoUpload] is true, also enqueue it for Telegram upload.
     */
    fun quickImportFile(uri: Uri, autoUpload: Boolean = false) {
        _uiState.update { it.copy(isImporting = true, uploadMessage = null) }

        viewModelScope.launch {
            repository.importFile(uri)
                .onSuccess { file ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importedFile = file,
                            uploadMessage = "\"${file.name}\" added!",
                        )
                    }
                    if (autoUpload) {
                        uploadFile(file)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            uploadMessage = "Import failed: ${e.message}",
                        )
                    }
                }
        }
    }

    /**
     * Import multiple files at once.
     */
    fun quickImportMultiple(uris: List<Uri>, autoUpload: Boolean = false) {
        _uiState.update { it.copy(isImporting = true, uploadMessage = null) }

        viewModelScope.launch {
            var successCount = 0
            var failCount = 0
            uris.forEach { uri ->
                repository.importFile(uri)
                    .onSuccess { file ->
                        successCount++
                        if (autoUpload) uploadFile(file)
                    }
                    .onFailure { failCount++ }
            }
            _uiState.update {
                it.copy(
                    isImporting = false,
                    uploadMessage = when {
                        failCount == 0 -> "$successCount file(s) added!"
                        successCount == 0 -> "Failed to import $failCount file(s)"
                        else -> "$successCount added, $failCount failed"
                    },
                )
            }
        }
    }

    /**
     * Upload a file that's already in the Room DB to Telegram.
     */
    fun uploadFile(file: FileEntity) {
        TransferManager.enqueueUpload(file)
        _uiState.update { it.copy(uploadMessage = "Uploading \"${file.name}\"…") }
    }

    fun clearMessage() {
        _uiState.update { it.copy(uploadMessage = null, importedFile = null) }
    }

    fun deleteFile(file: FileEntity) {
        viewModelScope.launch {
            repository.deleteFile(file)
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
                val networkMonitor = NetworkMonitor(app)
                return HomeViewModel(repository, networkMonitor) as T
            }
        }
    }
}
