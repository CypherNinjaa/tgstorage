package com.tgstorage.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.common.NetworkMonitor
import com.tgstorage.data.local.entity.SyncStateEntity
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.data.repository.SyncRepository
import com.tgstorage.data.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Sync Dashboard. */
sealed interface SyncDashboardUiState {
    data object Loading : SyncDashboardUiState
    data class Populated(
        val pendingCount: Int,
        val uploadedCount: Int,
        val failedCount: Int,
        val totalCount: Int,
        val lastSyncTimestamp: Long?,
        val isAutoSyncEnabled: Boolean,
        val isOnline: Boolean,
        val failedFiles: List<SyncStateEntity>,
        val isSyncing: Boolean,
    ) : SyncDashboardUiState
    data class Error(val message: String) : SyncDashboardUiState
}

class SyncDashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val db = TgStorageApp.instance.database
    private val syncRepository = SyncRepository(
        syncStateDao = db.syncStateDao(),
        fileDao = db.fileDao(),
        metadataDao = db.metadataDao(),
    )
    private val networkMonitor = NetworkMonitor(application)

    private val _isSyncing = MutableStateFlow(false)

    val uiState: StateFlow<SyncDashboardUiState> = combine(
        syncRepository.pendingCount,
        syncRepository.uploadedCount,
        syncRepository.failedCount,
        syncRepository.totalCount,
        syncRepository.lastSyncTimestamp,
        syncRepository.observeAutoSync(),
        networkMonitor.isOnline,
        syncRepository.failedFiles,
        _isSyncing.asStateFlow(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SyncDashboardUiState.Populated(
            pendingCount = values[0] as Int,
            uploadedCount = values[1] as Int,
            failedCount = values[2] as Int,
            totalCount = values[3] as Int,
            lastSyncTimestamp = values[4] as Long?,
            isAutoSyncEnabled = values[5] as Boolean,
            isOnline = values[6] as Boolean,
            failedFiles = values[7] as List<SyncStateEntity>,
            isSyncing = values[8] as Boolean,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncDashboardUiState.Loading,
    )

    fun syncNow() {
        viewModelScope.launch {
            _isSyncing.value = true
            // Trigger immediate one-shot WorkManager sync
            val workManager = androidx.work.WorkManager.getInstance(getApplication())
            val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            workManager.enqueue(request)

            // Wait a bit then stop the spinner (WorkManager is async)
            kotlinx.coroutines.delay(2000)
            _isSyncing.value = false
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            syncRepository.setAutoSync(enabled)
            if (enabled) {
                SyncWorker.schedule(getApplication())
            } else {
                SyncWorker.cancel(getApplication())
            }
        }
    }

    fun retryFile(fileId: Long) {
        viewModelScope.launch {
            syncRepository.retryFile(fileId)
        }
    }

    fun retryAllFailed() {
        viewModelScope.launch {
            syncRepository.retryAllFailed()
        }
    }
}
