package com.tgstorage.ui.transfers

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.UploadedFileInfo
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.repository.FileRepository
import com.tgstorage.data.repository.SyncRepository
import com.tgstorage.data.transfer.TransferManager
import com.tgstorage.data.transfer.TransferProgress
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransfersUiState(
    val transfers: List<TransferProgress> = emptyList(),
    
    // Pagination for transfers tab
    val transfersDisplayed: List<TransferProgress> = emptyList(),
    val transfersTotalCount: Int = 0,
    val hasMoreTransfers: Boolean = false,

    // Paginated uploaded list — never holds ALL files
    val uploadedFiles: List<UploadedFileInfo> = emptyList(),
    val uploadedTotalCount: Int = 0,
    val uploadedTabCount: Int = 0,
    val isLoadingUploaded: Boolean = false,
    val hasMorePages: Boolean = false,

    val selectedTab: Int = 0,
    val downloadingIds: Set<Long> = emptySet(),
    val transferSearchQuery: String = "",
    val uploadedSearchQuery: String = "",
    val transferFilter: TransferFileFilter = TransferFileFilter.ALL,
    val uploadedFilter: TransferFileFilter = TransferFileFilter.ALL,
    val uploadedViewMode: TransferViewMode = TransferViewMode.LIST,

    // Sync stats (merged from SyncDashboard)
    val syncPendingCount: Int = 0,
    val syncUploadedCount: Int = 0,
    val syncFailedCount: Int = 0,
    val syncTotalCount: Int = 0,
)

enum class TransferFileFilter(val label: String, val mimePrefix: String?) {
    ALL("All", null),
    IMAGES("Images", "image/"),
    DOCUMENTS("Docs", "application/"),
    VIDEOS("Videos", "video/"),
    AUDIO("Audio", "audio/"),
}

enum class TransferViewMode { LIST, GRID }

class TransferQueueViewModel(
    private val repository: FileRepository,
    private val fileDao: FileDao,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    companion object {
        private const val INITIAL_PAGE_SIZE = 4
        private const val LOAD_MORE_SIZE = 10
        private const val TRANSFERS_PAGE_SIZE = 20 // Show 20 transfers at a time

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                val repository = FileRepository(app, db.fileDao(), db.syncStateDao())
                val syncRepository = SyncRepository(db.syncStateDao(), db.fileDao(), db.metadataDao())
                return TransferQueueViewModel(repository, db.fileDao(), syncRepository) as T
            }
        }
    }

    private val _uiState = MutableStateFlow(TransfersUiState())
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    private var currentPage = 0
    private var uploadedQueryJob: Job? = null
    private var transfersDisplayLimit = TRANSFERS_PAGE_SIZE

    init {
        observeTransfers()
        observeUploadedTabCount()
        observeSyncStats()
        loadUploadedPage(reset = true)
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun onTransferSearchChange(query: String) {
        _uiState.update { it.copy(transferSearchQuery = query) }
        transfersDisplayLimit = TRANSFERS_PAGE_SIZE // Reset pagination on search
        updateFilteredTransfers()
    }

    fun onTransferFilterChange(filter: TransferFileFilter) {
        _uiState.update { it.copy(transferFilter = filter) }
        transfersDisplayLimit = TRANSFERS_PAGE_SIZE // Reset pagination on filter change
        updateFilteredTransfers()
    }

    fun loadMoreTransfers() {
        transfersDisplayLimit += TRANSFERS_PAGE_SIZE
        updateFilteredTransfers()
    }

    private fun updateFilteredTransfers() {
        val state = _uiState.value
        val allTransfers = state.transfers
        
        // Apply search and filter
        val filtered = allTransfers.filter { transfer ->
            val matchesSearch = state.transferSearchQuery.isBlank() ||
                transfer.fileName.contains(state.transferSearchQuery, ignoreCase = true)
            val matchesFilter = state.transferFilter.mimePrefix == null ||
                (transfer.mimeType?.startsWith(state.transferFilter.mimePrefix) ?: false)
            matchesSearch && matchesFilter
        }
        
        // Apply pagination - take only the first transfersDisplayLimit items
        val displayed = filtered.take(transfersDisplayLimit)
        
        _uiState.update { 
            it.copy(
                transfersDisplayed = displayed,
                transfersTotalCount = filtered.size,
                hasMoreTransfers = displayed.size < filtered.size,
            )
        }
    }

    fun onUploadedSearchChange(query: String) {
        _uiState.update { it.copy(uploadedSearchQuery = query) }
        loadUploadedPage(reset = true)
    }

    fun onUploadedFilterChange(filter: TransferFileFilter) {
        _uiState.update { it.copy(uploadedFilter = filter) }
        loadUploadedPage(reset = true)
    }

    fun toggleUploadedViewMode() {
        _uiState.update {
            val next = if (it.uploadedViewMode == TransferViewMode.GRID)
                TransferViewMode.LIST else TransferViewMode.GRID
            it.copy(uploadedViewMode = next)
        }
    }

    fun loadMoreUploaded() {
        loadUploadedPage(reset = false)
    }

    private fun loadUploadedPage(reset: Boolean) {
        uploadedQueryJob?.cancel()
        uploadedQueryJob = viewModelScope.launch {
            if (reset) {
                currentPage = 0
                _uiState.update { it.copy(uploadedFiles = emptyList(), isLoadingUploaded = true) }
            } else {
                _uiState.update { it.copy(isLoadingUploaded = true) }
            }

            val state = _uiState.value
            val query = state.uploadedSearchQuery.trim()
            val mimePrefix = state.uploadedFilter.mimePrefix ?: ""

            val totalCount = fileDao.getUploadedFilesCount(query, mimePrefix)
            val pageSize = if (currentPage == 0) INITIAL_PAGE_SIZE else LOAD_MORE_SIZE
            val offset = if (currentPage == 0) 0 else INITIAL_PAGE_SIZE + (currentPage - 1) * LOAD_MORE_SIZE
            val page = fileDao.getUploadedFilesPaged(query, mimePrefix, pageSize, offset)

            currentPage++

            _uiState.update { s ->
                val combined = if (reset) page else s.uploadedFiles + page
                s.copy(
                    uploadedFiles = combined,
                    uploadedTotalCount = totalCount,
                    hasMorePages = combined.size < totalCount,
                    isLoadingUploaded = false,
                )
            }
        }
    }

    fun refreshUploaded() {
        loadUploadedPage(reset = true)
    }

    fun cancelTransfer(fileId: Long, type: TransferType = TransferType.UPLOAD) {
        TransferManager.cancelTransfer(fileId, type)
    }

    fun pauseTransfer(fileId: Long, type: TransferType = TransferType.UPLOAD) {
        TransferManager.pauseTransfer(fileId, type)
    }

    fun resumeTransfer(fileId: Long, type: TransferType = TransferType.UPLOAD) {
        TransferManager.resumeTransfer(fileId, type)
    }

    fun retryTransfer(fileId: Long, type: TransferType = TransferType.UPLOAD) {
        TransferManager.retryTransfer(fileId, type)
    }

    fun retryAllFailed() {
        TransferManager.retryAllFailed()
    }

    fun pauseAll() {
        TransferManager.pauseAll()
    }

    fun resumeAll() {
        TransferManager.resumeAll()
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

        _uiState.update { it.copy(downloadingIds = it.downloadingIds + file.id) }

        val entity = FileEntity(
            id = file.id,
            name = file.name,
            size = file.size,
            mimeType = file.mimeType,
            localUri = file.localUri,
        )
        TransferManager.enqueueDownload(entity, outputFile)
        _uiState.update { it.copy(selectedTab = 0) }

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
            val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) { }
    }

    private var lastCompletedCount = 0

    private fun observeTransfers() {
        viewModelScope.launch {
            TransferManager.transfers.collect { transfers ->
                _uiState.update { it.copy(transfers = transfers) }
                updateFilteredTransfers()
                // Only refresh uploaded list when a NEW upload completes (not on every emission)
                val completedCount = transfers.count {
                    it.type == TransferType.UPLOAD && it.status == TransferStatus.COMPLETED
                }
                if (completedCount > lastCompletedCount) {
                    lastCompletedCount = completedCount
                    refreshUploaded()
                }
            }
        }
    }

    private fun observeUploadedTabCount() {
        viewModelScope.launch {
            fileDao.getUploadedTotalCount().collect { count ->
                _uiState.update { it.copy(uploadedTabCount = count) }
            }
        }
    }

    private fun observeSyncStats() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                syncRepository.pendingCount,
                syncRepository.uploadedCount,
                syncRepository.failedCount,
                syncRepository.totalCount,
            ) { pending, uploaded, failed, total ->
                SyncStats(pending, uploaded, failed, total)
            }.collect { stats ->
                _uiState.update {
                    it.copy(
                        syncPendingCount = stats.pending,
                        syncUploadedCount = stats.uploaded,
                        syncFailedCount = stats.failed,
                        syncTotalCount = stats.total,
                    )
                }
            }
        }
    }

    private data class SyncStats(val pending: Int, val uploaded: Int, val failed: Int, val total: Int)
}
