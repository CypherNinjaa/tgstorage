package com.tgstorage.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.dao.FileTypeStats
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.repository.StorageStatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class StatsUiState {
    data object Loading : StatsUiState()
    data class Loaded(
        val totalFiles: Int,
        val totalChunks: Int,
        val totalSize: Long,
        val uploadedSize: Long,
        val localCacheSize: Long,
        val telegramChunkSize: Long,
        val typeBreakdown: List<FileTypeStats>,
        val largestFiles: List<FileEntity>,
    ) : StatsUiState()
    data class Error(val message: String) : StatsUiState()
}

class StorageStatsViewModel(
    private val repository: StorageStatsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<StatsUiState>(StatsUiState.Loading)
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    fun retry() {
        _uiState.value = StatsUiState.Loading
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                // Load the one-shot data first
                val typeBreakdown = repository.getTypeBreakdown()
                val largestFiles = repository.getLargestFiles()
                val localCacheSize = repository.getLocalCacheSize()

                // Now combine the reactive flows
                combine(
                    repository.observeTotalFiles(),
                    repository.observeTotalChunks(),
                    repository.observeTotalSize(),
                    repository.observeUploadedSize(),
                    repository.observeChunkSize(),
                ) { totalFiles, totalChunks, totalSize, uploadedSize, chunkSize ->
                    StatsUiState.Loaded(
                        totalFiles = totalFiles,
                        totalChunks = totalChunks,
                        totalSize = totalSize,
                        uploadedSize = uploadedSize,
                        localCacheSize = localCacheSize,
                        telegramChunkSize = chunkSize,
                        typeBreakdown = typeBreakdown,
                        largestFiles = largestFiles,
                    )
                }.collect { state ->
                    _uiState.value = state
                }
            } catch (e: Exception) {
                _uiState.value = StatsUiState.Error(e.message ?: "Failed to load stats")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val repo = StorageStatsRepository(
                    context = app,
                    fileDao = app.database.fileDao(),
                    chunkDao = app.database.chunkDao(),
                )
                return StorageStatsViewModel(repo) as T
            }
        }
    }
}
