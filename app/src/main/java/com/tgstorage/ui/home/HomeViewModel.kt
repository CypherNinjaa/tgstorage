package com.tgstorage.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.common.NetworkMonitor
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.dao.FolderDao
import com.tgstorage.data.local.entity.FolderEntity
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.repository.FileRepository
import com.tgstorage.data.scanner.DeviceFile
import com.tgstorage.data.scanner.DeviceFileScanner
import com.tgstorage.data.sync.AutoBackupWorker
import com.tgstorage.data.transfer.TransferManager
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val allFilesCount: Int = 0, // Total files available (for "X more" indicator)
    val hasMoreFiles: Boolean = false, // True if there are more files to load
    val searchQuery: String = "",
    val activeFilter: FileFilter = FileFilter.ALL,
    val viewMode: ViewMode = ViewMode.LIST,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
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
    // Batch progress (auto-upload)
    val batchProgress: BatchProgress? = null,
)

/**
 * Tracks batch-by-batch auto-upload progress.
 */
data class BatchProgress(
    val currentBatch: Int,
    val totalBatches: Int,
    val uploadedFiles: Int,
    val failedFiles: Int,
    val totalFiles: Int,
    val batchSize: Int,
) {
    /** Files remaining = total - uploaded - failed */
    val remainingFiles: Int get() = (totalFiles - uploadedFiles - failedFiles).coerceAtLeast(0)
    /** Overall progress fraction (0.0 .. 1.0) */
    val progress: Float get() = if (totalFiles > 0) (uploadedFiles + failedFiles).toFloat() / totalFiles else 0f
}

// ── ViewModel ──────────────────────────────────────────

class HomeViewModel(
    private val scanner: DeviceFileScanner,
    private val repository: FileRepository,
    private val networkMonitor: NetworkMonitor,
    private val metadataDao: MetadataDao,
    private val folderDao: FolderDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** All folders for the move-to-folder picker */
    val folders: StateFlow<List<FolderEntity>> = MutableStateFlow<List<FolderEntity>>(emptyList()).also { flow ->
        viewModelScope.launch {
            folderDao.getAllFolders().collect { list -> (flow as MutableStateFlow).value = list }
        }
    }

    private var autoUploadJob: Job? = null
    private var searchJob: Job? = null
    private var loadJob: Job? = null

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 400L // Debounce search to avoid lag
        private const val INITIAL_PAGE_SIZE = 50    // Load first 50 files immediately
        private const val PAGE_SIZE = 100           // Load 100 more on scroll

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                val scanner = DeviceFileScanner(app)
                val repository = FileRepository(app, db.fileDao(), db.syncStateDao())
                val networkMonitor = NetworkMonitor(app)
                val metadataDao = db.metadataDao()
                val folderDao = db.folderDao()
                return HomeViewModel(scanner, repository, networkMonitor, metadataDao, folderDao) as T
            }
        }
    }

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
                // Re-schedule background worker in case it was cancelled
                AutoBackupWorker.schedule(TgStorageApp.instance)
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

    // Cached all files for current filter (to avoid re-scanning on each page load)
    private var cachedAllFiles: List<DeviceFile> = emptyList()
    private var cachedFilter: FileFilter? = null
    private var cachedQuery: String? = null

    fun loadDeviceFiles(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val state = _uiState.value
                val filter = state.activeFilter
                val query = state.searchQuery.takeIf { it.isNotBlank() }

                // Only re-scan if filter/query changed or forced
                if (forceRefresh || cachedFilter != filter || cachedQuery != query) {
                    cachedAllFiles = scanner.scanFiles(
                        mimeFilter = filter.mimePrefix,
                        searchQuery = query,
                    )
                    cachedFilter = filter
                    cachedQuery = query
                }

                // Show first page only for faster UI
                val firstPage = cachedAllFiles.take(INITIAL_PAGE_SIZE)
                _uiState.update {
                    it.copy(
                        deviceFiles = firstPage,
                        allFilesCount = cachedAllFiles.size,
                        hasMoreFiles = cachedAllFiles.size > INITIAL_PAGE_SIZE,
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Failed to scan: ${e.message}") }
            }
        }
    }

    /** Load more files for infinite scroll */
    fun loadMoreFiles() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMoreFiles) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val currentCount = state.deviceFiles.size
            val nextPage = cachedAllFiles.take(currentCount + PAGE_SIZE)
            _uiState.update {
                it.copy(
                    deviceFiles = nextPage,
                    hasMoreFiles = cachedAllFiles.size > nextPage.size,
                    isLoadingMore = false,
                )
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        // Debounce search to avoid scanning on every keystroke
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            loadDeviceFiles()
        }
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
        loadDeviceFiles(forceRefresh = true)
    }

    /** Load all files (used by auto-upload). Uses cached data when available. */
    private suspend fun getAllFilesForUpload(): List<DeviceFile> {
        val state = _uiState.value
        // If we have cached data for "All" filter with no search, use it
        if (cachedFilter == FileFilter.ALL && cachedQuery == null) {
            return cachedAllFiles
        }
        // Otherwise scan fresh
        return scanner.scanFiles(mimeFilter = null, searchQuery = null)
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

    // ── Move selected to folder ────────────────────────

    fun moveSelectedToFolder(folderId: Long) {
        val selectedIds = _uiState.value.selectedIds.toList()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            // First import selected device files to Room DB if not already there,
            // then move them to the folder
            val state = _uiState.value
            val selected = state.deviceFiles.filter { it.id in state.selectedIds }

            for (file in selected) {
                // Import to Room if needed, then move
                repository.importFile(file.contentUri)
                    .onSuccess { entity ->
                        repository.moveFileToFolder(entity.id, folderId)
                    }
            }

            val folderName = folderDao.getById(folderId)?.name ?: "folder"
            _uiState.update {
                it.copy(
                    selectedIds = emptySet(),
                    selectionMode = false,
                    message = "${selected.size} file(s) moved to $folderName",
                )
            }
        }
    }

    // ── Upload selected files (batched, memory-safe) ──

    fun uploadSelected() {
        val state = _uiState.value
        val selected = state.deviceFiles.filter { it.id in state.selectedIds }
        if (selected.isEmpty()) return

        // Filter out already-uploaded
        val toUpload = selected.filter { it.name !in state.uploadedNames }

        _uiState.update {
            it.copy(
                isImporting = false,
                message = null,
                selectionMode = false,
                selectedIds = emptySet(),
            )
        }

        if (toUpload.isEmpty()) {
            _uiState.update { it.copy(message = "All selected files already uploaded!") }
            return
        }

        // Use the same batched pipeline as auto-upload
        viewModelScope.launch {
            val batchSize = TransferManager.getOptimalBatchSize()
            val totalFiles = toUpload.size
            val batches = toUpload.chunked(batchSize)
            val totalBatches = batches.size
            var uploaded = 0
            var failed = 0

            // Show batch progress if more than one batch needed
            if (totalBatches > 1) {
                _uiState.update {
                    it.copy(
                        batchProgress = BatchProgress(
                            currentBatch = 1,
                            totalBatches = totalBatches,
                            uploadedFiles = 0,
                            failedFiles = 0,
                            totalFiles = totalFiles,
                            batchSize = batchSize,
                        ),
                    )
                }
            }

            for ((batchIndex, batch) in batches.withIndex()) {
                val currentBatchNum = batchIndex + 1
                val enqueuedIds = mutableSetOf<Long>()

                for (file in batch) {
                    repository.importFile(file.contentUri)
                        .onSuccess { entity ->
                            enqueuedIds.add(entity.id)
                            TransferManager.enqueueUpload(entity)
                        }
                        .onFailure { failed++ }
                }

                if (enqueuedIds.isEmpty()) continue

                // Update batch progress
                if (totalBatches > 1) {
                    _uiState.update {
                        it.copy(
                            batchProgress = BatchProgress(
                                currentBatch = currentBatchNum,
                                totalBatches = totalBatches,
                                uploadedFiles = uploaded,
                                failedFiles = failed,
                                totalFiles = totalFiles,
                                batchSize = batchSize,
                            ),
                        )
                    }
                }

                // Wait for batch to complete
                TransferManager.transfers.first { list ->
                    val stillActive = list.any { tp ->
                        tp.fileId in enqueuedIds &&
                                tp.type == TransferType.UPLOAD &&
                                (tp.status == TransferStatus.IN_PROGRESS ||
                                 tp.status == TransferStatus.PENDING)
                    }
                    !stillActive
                }

                // Count results
                val batchResults = TransferManager.transfers.value.filter {
                    it.fileId in enqueuedIds && it.type == TransferType.UPLOAD
                }
                uploaded += batchResults.count { it.status == TransferStatus.COMPLETED }
                failed += batchResults.count { it.status == TransferStatus.FAILED }

                // Update progress
                if (totalBatches > 1) {
                    _uiState.update {
                        it.copy(
                            batchProgress = BatchProgress(
                                currentBatch = currentBatchNum,
                                totalBatches = totalBatches,
                                uploadedFiles = uploaded,
                                failedFiles = failed,
                                totalFiles = totalFiles,
                                batchSize = batchSize,
                            ),
                        )
                    }
                }

                // Force-clear memory
                TransferManager.forceCleanCompletedTransfers()
                delay(200)
            }

            _uiState.update {
                it.copy(
                    batchProgress = null,
                    message = when {
                        failed == 0 -> "$uploaded file(s) uploaded successfully!"
                        uploaded == 0 -> "Failed to upload $failed file(s)"
                        else -> "$uploaded uploaded, $failed failed"
                    },
                )
            }
        }
    }

    // ── Auto upload toggle ─────────────────────────────

    fun toggleAutoUpload() {
        val newValue = !_uiState.value.autoUpload
        _uiState.update { it.copy(autoUpload = newValue) }

        val app = TgStorageApp.instance

        // Persist to Room so it survives screen switches
        viewModelScope.launch {
            metadataDao.setValue(MetadataEntity(MetadataKeys.AUTO_UPLOAD, newValue.toString()))
        }

        if (newValue) {
            // Schedule background worker for continuous auto-backup (even when app is closed)
            AutoBackupWorker.schedule(app)
            // Also do an immediate upload of visible files
            startAutoUpload()
        } else {
            // Cancel background worker
            AutoBackupWorker.cancel(app)
            autoUploadJob?.cancel()
            autoUploadJob = null
            _uiState.update { it.copy(message = "Auto-upload stopped", batchProgress = null) }
        }
    }

    private fun startAutoUpload() {
        autoUploadJob?.cancel()
        autoUploadJob = viewModelScope.launch {
            _uiState.update { it.copy(message = "Checking for new files...") }

            // Get all files efficiently (uses cache if available)
            val allFiles = getAllFilesForUpload()
            val uploadedNames = _uiState.value.uploadedNames
            val toUpload = allFiles.filter { it.name !in uploadedNames }

            if (toUpload.isEmpty()) {
                _uiState.update { it.copy(message = "All files already uploaded!", batchProgress = null) }
                return@launch
            }

            // ── BATCHED PIPELINE: 2 files per bot, upload, clear, repeat ──
            val batchSize = TransferManager.getOptimalBatchSize()
            val totalFiles = toUpload.size
            val batches = toUpload.chunked(batchSize)
            val totalBatches = batches.size
            var uploaded = 0
            var failed = 0

            // Show initial batch progress
            _uiState.update {
                it.copy(
                    batchProgress = BatchProgress(
                        currentBatch = 1,
                        totalBatches = totalBatches,
                        uploadedFiles = 0,
                        failedFiles = 0,
                        totalFiles = totalFiles,
                        batchSize = batchSize,
                    ),
                    message = null,
                )
            }

            for ((batchIndex, batch) in batches.withIndex()) {
                if (!_uiState.value.autoUpload) break // User turned off

                val currentBatchNum = batchIndex + 1
                val enqueuedIds = mutableSetOf<Long>()

                // Step 1: Import & enqueue only this small batch
                for (file in batch) {
                    if (!_uiState.value.autoUpload) break
                    repository.importFile(file.contentUri)
                        .onSuccess { entity ->
                            enqueuedIds.add(entity.id)
                            TransferManager.enqueueUpload(entity)
                        }
                        .onFailure { failed++ }
                }

                if (enqueuedIds.isEmpty()) continue

                // Update batch progress
                _uiState.update {
                    it.copy(
                        batchProgress = BatchProgress(
                            currentBatch = currentBatchNum,
                            totalBatches = totalBatches,
                            uploadedFiles = uploaded,
                            failedFiles = failed,
                            totalFiles = totalFiles,
                            batchSize = batchSize,
                        ),
                    )
                }

                // Step 2: Wait for this entire batch to finish uploading
                TransferManager.transfers.first { list ->
                    val stillActive = list.any { tp ->
                        tp.fileId in enqueuedIds &&
                                tp.type == TransferType.UPLOAD &&
                                (tp.status == TransferStatus.IN_PROGRESS ||
                                 tp.status == TransferStatus.PENDING)
                    }
                    !stillActive
                }

                // Count results from this batch
                val batchResults = TransferManager.transfers.value.filter {
                    it.fileId in enqueuedIds && it.type == TransferType.UPLOAD
                }
                uploaded += batchResults.count { it.status == TransferStatus.COMPLETED }
                failed += batchResults.count { it.status == TransferStatus.FAILED }

                // Update progress after batch completes
                _uiState.update {
                    it.copy(
                        batchProgress = BatchProgress(
                            currentBatch = currentBatchNum,
                            totalBatches = totalBatches,
                            uploadedFiles = uploaded,
                            failedFiles = failed,
                            totalFiles = totalFiles,
                            batchSize = batchSize,
                        ),
                    )
                }

                // Step 3: Force-clear completed transfers from memory
                TransferManager.forceCleanCompletedTransfers()

                // Give GC a chance
                delay(200)
            }

            // Clear batch progress, show final message
            _uiState.update {
                it.copy(
                    batchProgress = null,
                    message = when {
                        failed == 0 -> "$uploaded file(s) uploaded successfully!"
                        uploaded == 0 -> "Failed to upload $failed file(s)"
                        else -> "$uploaded uploaded, $failed failed"
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
}
