package com.tgstorage.ui.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.BackupFrequency
import com.tgstorage.data.repository.SyncRepository
import com.tgstorage.data.sync.BackupManager
import com.tgstorage.data.sync.BackupWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for Backup & Restore screen. */
sealed interface BackupRestoreUiState {
    data object Loading : BackupRestoreUiState
    data class Populated(
        val lastBackupTimestamp: Long?,
        val lastBackupSize: Long?,
        val isBackingUp: Boolean,
        val isRestoring: Boolean,
        val backupError: String?,
        val restoreError: String?,
        val backupSuccess: Boolean,
        val restoreSuccess: Boolean,
        val backupFrequency: String,
    ) : BackupRestoreUiState
}

class BackupRestoreViewModel(application: Application) : AndroidViewModel(application) {

    private val db = TgStorageApp.instance.database
    private val syncRepository = SyncRepository(
        syncStateDao = db.syncStateDao(),
        fileDao = db.fileDao(),
        metadataDao = db.metadataDao(),
    )
    private val backupManager = BackupManager(application)

    private val _isBackingUp = MutableStateFlow(false)
    private val _isRestoring = MutableStateFlow(false)
    private val _backupError = MutableStateFlow<String?>(null)
    private val _restoreError = MutableStateFlow<String?>(null)
    private val _backupSuccess = MutableStateFlow(false)
    private val _restoreSuccess = MutableStateFlow(false)

    val uiState: StateFlow<BackupRestoreUiState> = combine(
        syncRepository.observeLastBackupTimestamp(),
        _isBackingUp,
        _isRestoring,
        _backupError,
        _restoreError,
        _backupSuccess,
        _restoreSuccess,
        syncRepository.observeBackupFrequency(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        BackupRestoreUiState.Populated(
            lastBackupTimestamp = values[0] as Long?,
            lastBackupSize = null,
            isBackingUp = values[1] as Boolean,
            isRestoring = values[2] as Boolean,
            backupError = values[3] as String?,
            restoreError = values[4] as String?,
            backupSuccess = values[5] as Boolean,
            restoreSuccess = values[6] as Boolean,
            backupFrequency = values[7] as String,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BackupRestoreUiState.Loading,
    )

    fun createBackup() {
        viewModelScope.launch {
            _isBackingUp.value = true
            _backupError.value = null
            _backupSuccess.value = false

            val result = backupManager.createAndUploadBackup()
            result.fold(
                onSuccess = {
                    _backupSuccess.value = true
                },
                onFailure = { e ->
                    _backupError.value = e.message ?: "Backup failed"
                },
            )
            _isBackingUp.value = false
        }
    }

    fun restoreBackup() {
        viewModelScope.launch {
            _isRestoring.value = true
            _restoreError.value = null
            _restoreSuccess.value = false

            val result = backupManager.downloadAndRestoreBackup()
            result.fold(
                onSuccess = {
                    _restoreSuccess.value = true
                },
                onFailure = { e ->
                    _restoreError.value = e.message ?: "Restore failed"
                },
            )
            _isRestoring.value = false
        }
    }

    fun setBackupFrequency(frequency: String) {
        viewModelScope.launch {
            syncRepository.setBackupFrequency(frequency)
            BackupWorker.schedule(getApplication(), frequency)
        }
    }

    fun clearMessages() {
        _backupError.value = null
        _restoreError.value = null
        _backupSuccess.value = false
        _restoreSuccess.value = false
    }
}
