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
    val isClearingCache: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val syncWifiOnly: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
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
        refreshCacheSize()
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

    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingCache = true, message = null, error = null) }
            runCatching {
                deleteDir(app.cacheDir)
            }.onFailure { e ->
                _uiState.update { it.copy(isClearingCache = false, error = e.message ?: "Failed to clear cache") }
            }.onSuccess {
                refreshCacheSize()
                _uiState.update { it.copy(isClearingCache = false, message = "Cache cleared") }
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

    private fun refreshCacheSize() {
        viewModelScope.launch {
            val size = dirSize(app.cacheDir)
            _uiState.update { it.copy(cacheBytes = size) }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            combine(
                metadataDao.observeValue(MetadataKeys.THEME_MODE),
                metadataDao.observeValue(MetadataKeys.DYNAMIC_COLOR),
                syncRepository.observeAutoSync(),
                metadataDao.observeValue(MetadataKeys.SYNC_WIFI_ONLY),
            ) { themeModeRaw, dynamicRaw, autoSync, wifiOnlyRaw ->
                val themeMode = when (themeModeRaw) {
                    ThemeMode.LIGHT.value -> ThemeMode.LIGHT
                    ThemeMode.DARK.value -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                }
                val dynamic = dynamicRaw?.toBoolean() ?: true
                val wifiOnly = wifiOnlyRaw?.toBoolean() ?: false
                SettingsUiState(
                    botToken = _uiState.value.botToken,
                    isTokenVisible = _uiState.value.isTokenVisible,
                    chatId = _uiState.value.chatId,
                    isReverifying = _uiState.value.isReverifying,
                    cacheBytes = _uiState.value.cacheBytes,
                    isClearingCache = _uiState.value.isClearingCache,
                    autoSyncEnabled = autoSync,
                    syncWifiOnly = wifiOnly,
                    themeMode = themeMode,
                    dynamicColor = dynamic,
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
