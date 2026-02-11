package com.tgstorage.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.repository.SyncRepository
import com.tgstorage.data.repository.TelegramRepository
import com.tgstorage.data.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class ThemeMode(val label: String, val value: String) {
    SYSTEM("System", "system"),
    LIGHT("Light", "light"),
    DARK("Dark", "dark"),
}

data class SettingsUiState(
    val isLoading: Boolean = true,
    val botToken: String? = null,
    val isTokenVisible: Boolean = false,
    val chatId: String? = null,
    val isReverifying: Boolean = false,
    val cacheBytes: Long = 0L,
    val pendingUploadBytes: Long = 0L,
    val isClearingCache: Boolean = false,
    val isClearingPending: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val syncWifiOnly: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val appLockEnabled: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class SettingsViewModel(
    private val telegramRepository: TelegramRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    private val app = TgStorageApp.instance
    private val metadataDao = app.database.metadataDao()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observePreferences()
        refreshAccount()
        refreshStorageSizes()
    }

    fun toggleTokenVisibility() {
        _uiState.update { it.copy(isTokenVisible = !it.isTokenVisible) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    fun reverifyChannel() {
        val token = _uiState.value.botToken
        val chatId = _uiState.value.chatId
        if (token.isNullOrBlank() || chatId.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Bot token or channel not configured") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isReverifying = true, error = null, message = null) }
            telegramRepository.verifyChannel(token, chatId)
                .onSuccess {
                    _uiState.update { it.copy(isReverifying = false, message = "Channel verified") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isReverifying = false, error = e.message ?: "Verification failed") }
                }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            syncRepository.setAutoSync(enabled)
            scheduleSync(enabled, _uiState.value.syncWifiOnly)
        }
    }

    fun setSyncWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            metadataDao.setValue(MetadataEntity(MetadataKeys.SYNC_WIFI_ONLY, enabled.toString()))
            scheduleSync(_uiState.value.autoSyncEnabled, enabled)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            metadataDao.setValue(MetadataEntity(MetadataKeys.THEME_MODE, mode.value))
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            metadataDao.setValue(MetadataEntity(MetadataKeys.DYNAMIC_COLOR, enabled.toString()))
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                // Disable app lock: clear passphrase data
                metadataDao.deleteValue(MetadataKeys.PASSPHRASE_HASH)
                metadataDao.deleteValue(MetadataKeys.PASSPHRASE_SALT)
                metadataDao.deleteValue(MetadataKeys.PASSPHRASE_ENCRYPTED)
            }
            metadataDao.setValue(MetadataEntity(MetadataKeys.APP_LOCK_ENABLED, enabled.toString()))
            _uiState.update { it.copy(appLockEnabled = enabled, message = if (enabled) "App lock enabled" else "App lock disabled") }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingCache = true, message = null, error = null) }
            runCatching {
                deleteDir(app.cacheDir)
            }.onFailure { e ->
                _uiState.update { it.copy(isClearingCache = false, error = e.message ?: "Failed to clear cache") }
            }.onSuccess {
                refreshStorageSizes()
                _uiState.update { it.copy(isClearingCache = false, message = "Cache cleared") }
            }
        }
    }
    
    fun clearPendingUploads() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingPending = true, message = null, error = null) }
            runCatching {
                // Clean up any temp files from upload_temp cache
                val uploadTempDir = File(app.cacheDir, "upload_temp")
                deleteDir(uploadTempDir)
                
                // Also clean up legacy imported directory if it exists
                val importedDir = File(app.filesDir, "imported")
                deleteDir(importedDir)
                
                // Clear pending upload entries from database
                val fileDao = app.database.fileDao()
                val syncStateDao = app.database.syncStateDao()
                
                // Get all files with pending upload status and remove them
                val pendingFiles = syncStateDao.getByStatusSync("pending_upload")
                for (syncState in pendingFiles) {
                    val file = fileDao.getFileById(syncState.fileId)
                    if (file != null) {
                        fileDao.deleteFile(file)
                        syncStateDao.deleteByFileId(syncState.fileId)
                    }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isClearingPending = false, error = e.message ?: "Failed to clear pending uploads") }
            }.onSuccess {
                refreshStorageSizes()
                _uiState.update { it.copy(isClearingPending = false, message = "Pending uploads cleared") }
            }
        }
    }

    private fun refreshAccount() {
        viewModelScope.launch {
            val token = telegramRepository.getToken()
            val chatId = telegramRepository.getChatId()
            _uiState.update { it.copy(botToken = token, chatId = chatId, isLoading = false) }
        }
    }

    private fun refreshStorageSizes() {
        viewModelScope.launch {
            val cacheSize = dirSize(app.cacheDir)
            
            // Check upload_temp directory (active uploads)
            val uploadTempDir = File(app.cacheDir, "upload_temp")
            val uploadTempSize = dirSize(uploadTempDir)
            
            // Also check legacy imported directory
            val importedDir = File(app.filesDir, "imported")
            val legacySize = dirSize(importedDir)
            
            // Total pending storage = upload temp + legacy imported
            val pendingSize = uploadTempSize + legacySize
            
            _uiState.update { it.copy(cacheBytes = cacheSize, pendingUploadBytes = pendingSize) }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            combine(
                metadataDao.observeValue(MetadataKeys.THEME_MODE),
                metadataDao.observeValue(MetadataKeys.DYNAMIC_COLOR),
                syncRepository.observeAutoSync(),
                metadataDao.observeValue(MetadataKeys.SYNC_WIFI_ONLY),
                metadataDao.observeValue(MetadataKeys.APP_LOCK_ENABLED),
            ) { themeModeRaw, dynamicRaw, autoSync, wifiOnlyRaw, appLockRaw ->
                val themeMode = when (themeModeRaw) {
                    ThemeMode.LIGHT.value -> ThemeMode.LIGHT
                    ThemeMode.DARK.value -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                }
                val dynamic = dynamicRaw?.toBoolean() ?: true
                val wifiOnly = wifiOnlyRaw?.toBoolean() ?: false
                val appLockEnabled = appLockRaw?.toBoolean() ?: false
                SettingsUiState(
                    botToken = _uiState.value.botToken,
                    isTokenVisible = _uiState.value.isTokenVisible,
                    chatId = _uiState.value.chatId,
                    isReverifying = _uiState.value.isReverifying,
                    cacheBytes = _uiState.value.cacheBytes,
                    pendingUploadBytes = _uiState.value.pendingUploadBytes,
                    isClearingCache = _uiState.value.isClearingCache,
                    isClearingPending = _uiState.value.isClearingPending,
                    autoSyncEnabled = autoSync,
                    syncWifiOnly = wifiOnly,
                    themeMode = themeMode,
                    dynamicColor = dynamic,
                    appLockEnabled = appLockEnabled,
                    isLoading = false,
                    message = _uiState.value.message,
                    error = _uiState.value.error,
                )
            }.collect { merged ->
                _uiState.value = merged
            }
        }
    }

    private fun scheduleSync(enabled: Boolean, wifiOnly: Boolean) {
        if (enabled) SyncWorker.schedule(app, wifiOnly) else SyncWorker.cancel(app)
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    private fun deleteDir(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) deleteDir(file) else file.delete()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = TgStorageApp.instance.database
                val repo = TelegramRepository(
                    api = com.tgstorage.data.remote.TelegramApiService(),
                    metadataDao = db.metadataDao(),
                )
                val syncRepo = SyncRepository(db.syncStateDao(), db.fileDao(), db.metadataDao())
                return SettingsViewModel(repo, syncRepo) as T
            }
        }
    }
}
