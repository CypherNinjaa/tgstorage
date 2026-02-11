package com.tgstorage.ui.transfers

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.dao.FailedFileInfo
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

    // Failed files list
    val failedFiles: List<FailedFileInfo> = emptyList(),
    val failedTabCount: Int = 0,
    val isLoadingFailed: Boolean = false,
    val isRetryingAll: Boolean = false,

    val selectedTab: Int = 0,
    val downloadingIds: Set<Long> = emptySet(),
    val retryingIds: Set<Long> = emptySet(),
    val transferSearchQuery: String = "",
    val uploadedSearchQuery: String = "",
    val transferFilter: TransferFileFilter = TransferFileFilter.ALL,
    val uploadedFilter: TransferFileFilter = TransferFileFilter.ALL,
    val uploadedViewMode: TransferViewMode = TransferViewMode.LIST,

    // ── Bulk selection ──
    val isSelectionMode: Boolean = false,
    val selectedFileIds: Set<Long> = emptySet(),

    // ── Online preview ──
    val previewFile: UploadedFileInfo? = null,
    val previewUrl: String? = null,
    val isLoadingPreview: Boolean = false,

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
        observeFailedTabCount()
        observeSyncStats()
        loadUploadedPage(reset = true)
        loadFailedFiles()
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
        // Load failed files when switching to failed tab
        if (index == 2) {
            loadFailedFiles()
        }
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

    // ─── Failed Files ──────────────────────────────────

    private fun loadFailedFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFailed = true) }
            fileDao.getFailedFilesDetailed().collect { files ->
                _uiState.update { it.copy(failedFiles = files, isLoadingFailed = false) }
            }
        }
    }

    private fun observeFailedTabCount() {
        viewModelScope.launch {
            fileDao.getFailedTotalCount().collect { count ->
                _uiState.update { it.copy(failedTabCount = count) }
            }
        }
    }

    /**
     * Retry a single failed file upload.
     * Resets sync state and re-enqueues the upload.
     */
    fun retryFailedFile(file: FailedFileInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(retryingIds = it.retryingIds + file.id) }
            
            try {
                // Reset sync state to pending
                syncRepository.retryFile(file.id)
                
                // Get the file entity
                val fileEntity = fileDao.getFileById(file.id)
                if (fileEntity != null) {
                    // Re-enqueue the upload
                    TransferManager.enqueueUpload(fileEntity)
                    
                    // Switch to transfers tab to show progress
                    _uiState.update { it.copy(selectedTab = 0) }
                }
            } finally {
                _uiState.update { it.copy(retryingIds = it.retryingIds - file.id) }
            }
        }
    }

    /**
     * Retry all failed uploads.
     * Resets all failed sync states and re-enqueues them.
     */
    fun retryAllFailedUploads() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRetryingAll = true) }
            
            try {
                // Reset all failed sync states
                syncRepository.retryAllFailed()
                
                // Get all files that were failed (now pending)
                val pendingUploads = syncRepository.getPendingUploads()
                
                // Re-enqueue each file
                for (syncState in pendingUploads) {
                    val fileEntity = fileDao.getFileById(syncState.fileId)
                    if (fileEntity != null) {
                        TransferManager.enqueueUpload(fileEntity)
                    }
                }
                
                // Switch to transfers tab
                _uiState.update { it.copy(selectedTab = 0) }
            } finally {
                _uiState.update { it.copy(isRetryingAll = false) }
            }
        }
    }

    fun isActive(progress: TransferProgress): Boolean =
        progress.status == TransferStatus.IN_PROGRESS ||
            progress.status == TransferStatus.PENDING ||
            progress.status == TransferStatus.PAUSED

    // ── Bulk Selection ─────────────────────────────────

    fun enterSelectionMode(firstFileId: Long) {
        _uiState.update {
            it.copy(isSelectionMode = true, selectedFileIds = setOf(firstFileId))
        }
    }

    fun exitSelectionMode() {
        _uiState.update {
            it.copy(isSelectionMode = false, selectedFileIds = emptySet())
        }
    }

    fun toggleFileSelection(fileId: Long) {
        _uiState.update {
            val newSet = if (fileId in it.selectedFileIds)
                it.selectedFileIds - fileId else it.selectedFileIds + fileId
            // Exit selection mode if nothing selected
            if (newSet.isEmpty()) it.copy(isSelectionMode = false, selectedFileIds = emptySet())
            else it.copy(selectedFileIds = newSet)
        }
    }

    fun selectAllFiles() {
        _uiState.update {
            it.copy(selectedFileIds = it.uploadedFiles.map { f -> f.id }.toSet())
        }
    }

    fun deselectAllFiles() {
        _uiState.update {
            it.copy(selectedFileIds = emptySet())
        }
    }

    /**
     * Download all currently selected files as a batch.
     * Each file is enqueued as a separate download.
     */
    fun downloadSelected() {
        val state = _uiState.value
        val filesToDownload = state.uploadedFiles.filter { it.id in state.selectedFileIds }
        if (filesToDownload.isEmpty()) return

        // Enqueue each file
        for (file in filesToDownload) {
            enqueueDownload(file)
        }

        // Exit selection mode and switch to transfers tab
        _uiState.update {
            it.copy(isSelectionMode = false, selectedFileIds = emptySet(), selectedTab = 0)
        }
    }

    // ── Online Preview ─────────────────────────────────

    /**
     * Open a file preview by fetching its temporary URL from Telegram.
     * Works for images, videos, audio, PDFs — Telegram provides a 1-hour-valid link.
     */
    fun previewFile(file: UploadedFileInfo) {
        _uiState.update { it.copy(previewFile = file, isLoadingPreview = true, previewUrl = null) }

        viewModelScope.launch {
            try {
                val app = TgStorageApp.instance
                val db = app.database
                val api = com.tgstorage.data.remote.TelegramApiService()
                val metadataDao = db.metadataDao()
                val chunkDao = db.chunkDao()

                // Get bot token
                val token = metadataDao.getValue(
                    com.tgstorage.data.local.entity.MetadataKeys.BOT_TOKEN
                ) ?: throw IllegalStateException("No bot token configured")

                val decryptedToken = com.tgstorage.common.security.CryptoManager.decrypt(
                    android.util.Base64.decode(token, android.util.Base64.NO_WRAP)
                ).decodeToString()

                // Get chunks for this file — we need the first chunk's telegramFileId
                val chunks = chunkDao.getChunksForFileSync(file.id)
                if (chunks.isEmpty()) throw IllegalStateException("No chunks for this file")

                // For preview, we use the first (or only) chunk
                val firstChunk = chunks.first()
                val tgFileId = firstChunk.telegramFileId
                    ?: throw IllegalStateException("No Telegram file ID")

                // Get file path from Telegram (cached for 50 min)
                val filePath = api.getFilePathCached(decryptedToken, tgFileId).getOrThrow()

                // Build the direct download URL
                val previewUrl = "https://api.telegram.org/file/bot${decryptedToken}/${filePath}"

                _uiState.update {
                    it.copy(previewUrl = previewUrl, isLoadingPreview = false)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoadingPreview = false, previewFile = null, previewUrl = null)
                }
            }
        }
    }

    fun dismissPreview() {
        _uiState.update {
            it.copy(previewFile = null, previewUrl = null, isLoadingPreview = false)
        }
    }

    fun openPreviewExternally() {
        val url = _uiState.value.previewUrl ?: return
        try {
            val app = TgStorageApp.instance
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        } catch (_: Exception) { }
    }

    fun enqueueDownload(file: UploadedFileInfo) {
        val app = TgStorageApp.instance
        
        // Download to temp file first, then copy to public Downloads
        val tempFile = com.tgstorage.common.StorageUtils.getTempDownloadFile(app, file.name)

        _uiState.update { it.copy(downloadingIds = it.downloadingIds + file.id) }

        val entity = FileEntity(
            id = file.id,
            name = file.name,
            size = file.size,
            mimeType = file.mimeType,
            localUri = file.localUri,
        )
        TransferManager.enqueueDownload(entity, tempFile)
        _uiState.update { it.copy(selectedTab = 0) }

        viewModelScope.launch {
            TransferManager.transfers.collect { transfers ->
                val dl = transfers.find { it.fileId == file.id && it.type == TransferType.DOWNLOAD }
                if (dl?.status == TransferStatus.COMPLETED) {
                    _uiState.update { it.copy(downloadingIds = it.downloadingIds - file.id) }
                    
                    // Save to public Downloads folder so user can see it in file manager
                    val savedUri = com.tgstorage.common.StorageUtils.saveToPublicDownloads(
                        context = app,
                        sourceFile = tempFile,
                        fileName = file.name,
                        mimeType = file.mimeType,
                    )
                    
                    // Clean up temp file
                    tempFile.delete()
                    
                    // Open the downloaded file
                    if (savedUri != null) {
                        openFileFromUri(savedUri, file.mimeType)
                    }
                    return@collect
                }
                if (dl?.status == TransferStatus.FAILED || dl?.status == TransferStatus.CANCELLED) {
                    _uiState.update { it.copy(downloadingIds = it.downloadingIds - file.id) }
                    tempFile.delete() // Clean up on failure too
                    return@collect
                }
            }
        }
    }

    private fun openFileFromUri(uri: Uri, mimeType: String) {
        try {
            val app = TgStorageApp.instance
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
