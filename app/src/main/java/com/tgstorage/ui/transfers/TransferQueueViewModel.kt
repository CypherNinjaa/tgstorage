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
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 50

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                val repository = FileRepository(app, db.fileDao(), db.syncStateDao())
                return TransferQueueViewModel(repository, db.fileDao()) as T
            }
        }
    }

    private val _uiState = MutableStateFlow(TransfersUiState())
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    private var currentPage = 0
    private var uploadedQueryJob: Job? = null

    init {
        observeTransfers()
        observeUploadedTabCount()
        loadUploadedPage(reset = true)
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun onTransferSearchChange(query: String) {
        _uiState.update { it.copy(transferSearchQuery = query) }
    }

    fun onTransferFilterChange(filter: TransferFileFilter) {
        _uiState.update { it.copy(transferFilter = filter) }
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
            val offset = currentPage * PAGE_SIZE
            val page = fileDao.getUploadedFilesPaged(query, mimePrefix, PAGE_SIZE, offset)

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

    private fun observeTransfers() {
        viewModelScope.launch {
            TransferManager.transfers.collect { transfers ->
                _uiState.update { it.copy(transfers = transfers) }
                val justFinished = transfers.any {
                    it.type == TransferType.UPLOAD && it.status == TransferStatus.COMPLETED
                }
                if (justFinished) refreshUploaded()
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
}
