package com.tgstorage.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.common.NetworkMonitor
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.repository.FileRepository
import com.tgstorage.data.scanner.DeviceFile
import com.tgstorage.data.scanner.DeviceFileScanner
import com.tgstorage.data.transfer.TransferManager
import com.tgstorage.data.transfer.TransferStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── Filter categories ──────────────────────────────────

enum class FileFilter(val label: String, val mimePrefix: String?) {
    ALL("All", null),
    IMAGES("Images", "image/"),
    DOCUMENTS("Docs", "application/"),
    VIDEOS("Videos", "video/"),
    AUDIO("Audio", "audio/"),
}

enum class ViewMode { GRID, LIST }

// ── UI state ───────────────────────────────────────────

data class HomeUiState(
    val deviceFiles: List<DeviceFile> = emptyList(),
    val searchQuery: String = "",
    val activeFilter: FileFilter = FileFilter.ALL,
    val viewMode: ViewMode = ViewMode.LIST,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isOnline: Boolean = true,
    // Permission
    val hasPermission: Boolean = false,
    // Selection
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    // Auto upload
    val autoUpload: Boolean = false,
    // Uploaded tracking — set of file names that have been uploaded
    val uploadedNames: Set<String> = emptySet(),
    // Status
    val message: String? = null,
    val isImporting: Boolean = false,
    val activeUploads: Int = 0,
)

// ── ViewModel ──────────────────────────────────────────

class HomeViewModel(
    private val scanner: DeviceFileScanner,
    private val repository: FileRepository,
    private val networkMonitor: NetworkMonitor,
    private val metadataDao: MetadataDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var autoUploadJob: Job? = null

    init {
        observeNetwork()
        observeTransfers()
        observeUploadedNames()
        restoreAutoUpload()
    }

    /** Restore persisted auto-upload preference */
    private fun restoreAutoUpload() {
        viewModelScope.launch {
            val saved = metadataDao.getValue(MetadataKeys.AUTO_UPLOAD)
            if (saved == "true") {
                _uiState.update { it.copy(autoUpload = true) }
                // Don't restart auto-upload job — only on explicit toggle
            }
        }
    }

    // ── Permission ─────────────────────────────────────

    fun onPermissionGranted() {
        _uiState.update { it.copy(hasPermission = true) }
        loadDeviceFiles()
    }

    fun onPermissionDenied() {
        _uiState.update {
            it.copy(hasPermission = false, isLoading = false,
                error = "Storage permission is required to show your files")
        }
    }

    // ── Load device files ──────────────────────────────

    fun loadDeviceFiles() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val files = scanner.scanFiles(
                    mimeFilter = state.activeFilter.mimePrefix,
                    searchQuery = state.searchQuery.takeIf { it.isNotBlank() },
                )
                _uiState.update { it.copy(deviceFiles = files, isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Failed to scan: ${e.message}") }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        loadDeviceFiles()
    }

    fun onFilterChange(filter: FileFilter) {
        _uiState.update { it.copy(activeFilter = filter) }
        loadDeviceFiles()
    }

    fun onViewModeToggle() {
        val next = if (_uiState.value.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
        _uiState.update { it.copy(viewMode = next) }
    }

    fun refresh() {
        loadDeviceFiles()
    }

    // ── Selection ──────────────────────────────────────

    fun toggleSelection(fileId: Long) {
        _uiState.update { state ->
            val newSelected = state.selectedIds.toMutableSet()
            if (fileId in newSelected) newSelected.remove(fileId) else newSelected.add(fileId)
            state.copy(selectedIds = newSelected, selectionMode = newSelected.isNotEmpty())
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedIds = state.deviceFiles.map { it.id }.toSet(), selectionMode = true)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet(), selectionMode = false) }
    }

    // ── Upload selected files ──────────────────────────

    fun uploadSelected() {
        val state = _uiState.value
        val selected = state.deviceFiles.filter { it.id in state.selectedIds }
        if (selected.isEmpty()) return

        _uiState.update { it.copy(isImporting = true, message = null, selectionMode = false, selectedIds = emptySet()) }

        viewModelScope.launch {
            var ok = 0; var fail = 0
            for (file in selected) {
                // Skip already-uploaded files
                if (file.name in state.uploadedNames) { ok++; continue }

                repository.importFile(file.contentUri)
                    .onSuccess { entity -> ok++; TransferManager.enqueueUpload(entity) }
                    .onFailure { fail++ }
            }
            _uiState.update {
                it.copy(
                    isImporting = false,
                    message = when {
                        fail == 0 -> "$ok file(s) queued for upload!"
                        ok == 0 -> "Failed to import $fail file(s)"
                        else -> "$ok queued, $fail failed"
                    },
                )
            }
        }
    }

    // ── Auto upload toggle ─────────────────────────────

    fun toggleAutoUpload() {
        val newValue = !_uiState.value.autoUpload
        _uiState.update { it.copy(autoUpload = newValue) }

        // Persist to Room so it survives screen switches
        viewModelScope.launch {
            metadataDao.setValue(MetadataEntity(MetadataKeys.AUTO_UPLOAD, newValue.toString()))
        }

        if (newValue) {
            // Start uploading all non-uploaded device files
            startAutoUpload()
        } else {
            autoUploadJob?.cancel()
            autoUploadJob = null
            _uiState.update { it.copy(message = "Auto-upload stopped") }
        }
    }

    private fun startAutoUpload() {
        autoUploadJob?.cancel()
        autoUploadJob = viewModelScope.launch {
            val state = _uiState.value
            val toUpload = state.deviceFiles.filter { it.name !in state.uploadedNames }

            if (toUpload.isEmpty()) {
                _uiState.update { it.copy(message = "All files already uploaded!") }
                return@launch
            }

            _uiState.update {
                it.copy(isImporting = true, message = "Auto-uploading ${toUpload.size} file(s)…")
            }

            var ok = 0; var fail = 0
            for (file in toUpload) {
                if (!_uiState.value.autoUpload) break // User turned off

                repository.importFile(file.contentUri)
                    .onSuccess { entity -> ok++; TransferManager.enqueueUpload(entity) }
                    .onFailure { fail++ }
            }

            _uiState.update {
                it.copy(
                    isImporting = false,
                    message = when {
                        fail == 0 -> "$ok file(s) queued for auto-upload!"
                        ok == 0 -> "Failed to import $fail file(s)"
                        else -> "$ok queued, $fail failed"
                    },
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // ── Observers ──────────────────────────────────────

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

    /** Observe Room for uploaded file names — updates green ticks in real time */
    private fun observeUploadedNames() {
        viewModelScope.launch {
            repository.getUploadedFileNames().collect { names ->
                _uiState.update { it.copy(uploadedNames = names.toSet()) }
            }
        }
    }

    // ── Factory ────────────────────────────────────────

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                val scanner = DeviceFileScanner(app)
                val repository = FileRepository(app, db.fileDao(), db.syncStateDao())
                val networkMonitor = NetworkMonitor(app)
                val metadataDao = db.metadataDao()
                return HomeViewModel(scanner, repository, networkMonitor, metadataDao) as T
            }
        }
    }
}
