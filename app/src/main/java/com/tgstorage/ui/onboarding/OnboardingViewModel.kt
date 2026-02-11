package com.tgstorage.ui.onboarding

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.remote.DetectedChannel
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.remote.TelegramUser
import com.tgstorage.data.repository.TelegramRepository
import com.tgstorage.data.sync.BackupInfo
import com.tgstorage.data.sync.BackupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val currentStep: Int = 0,  // 0=HowItWorks, 1=BotToken, 2=VerifyChannel, 3=BackupRestore
    val botToken: String = "",
    val channelId: String = "",
    val isTokenVisible: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val botUser: TelegramUser? = null,
    val isTokenValid: Boolean = false,
    val isChannelVerified: Boolean = false,
    val isComplete: Boolean = false,
    // Channel auto-detection
    val detectedChannels: List<DetectedChannel> = emptyList(),
    val isDetectingChannels: Boolean = false,
    // Backup restore
    val backupInfo: BackupInfo? = null,
    val isSearchingBackup: Boolean = false,
    val isRestoring: Boolean = false,
    val restoreComplete: Boolean = false,
)

class OnboardingViewModel(
    private val repository: TelegramRepository,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun nextStep() {
        _uiState.update { it.copy(currentStep = (it.currentStep + 1).coerceAtMost(3), error = null) }
    }

    fun previousStep() {
        _uiState.update { it.copy(currentStep = (it.currentStep - 1).coerceAtLeast(0), error = null) }
    }

    fun updateBotToken(token: String) {
        _uiState.update { it.copy(botToken = token, error = null, isTokenValid = false, botUser = null) }
    }

    fun updateChannelId(channelId: String) {
        _uiState.update { it.copy(channelId = channelId, error = null, isChannelVerified = false) }
    }

    fun toggleTokenVisibility() {
        _uiState.update { it.copy(isTokenVisible = !it.isTokenVisible) }
    }

    fun validateToken() {
        val token = _uiState.value.botToken.trim()
        if (token.isBlank()) {
            _uiState.update { it.copy(error = "Please enter your bot token") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.validateToken(token)
                .onSuccess { user ->
                    repository.saveToken(token)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            botUser = user,
                            isTokenValid = true,
                            error = null,
                            currentStep = 2,
                            isDetectingChannels = true,
                        )
                    }
                    // Auto-detect channels in background
                    detectChannels()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Invalid token: ${e.message}",
                            isTokenValid = false,
                        )
                    }
                }
        }
    }

    fun verifyChannel() {
        val token = _uiState.value.botToken.trim()
        var chatId = _uiState.value.channelId.trim()
        if (chatId.isBlank()) {
            _uiState.update { it.copy(error = "Please enter your channel ID") }
            return
        }
        // Auto-prefix with @ if it looks like a username (not numeric/starting with -)
        if (!chatId.startsWith("@") && !chatId.startsWith("-") && chatId.toLongOrNull() == null) {
            chatId = "@$chatId"
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.verifyChannel(token, chatId)
                .onSuccess { msg ->
                    val resolvedChatId = msg.chat.id.toString()
                    repository.saveChatId(resolvedChatId)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isChannelVerified = true,
                            channelId = resolvedChatId,
                            error = null,
                            currentStep = 3,
                            isSearchingBackup = true,
                        )
                    }
                    // Automatically search for backup in the channel
                    searchForBackup()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Channel verification failed: ${e.message}",
                            isChannelVerified = false,
                        )
                    }
                }
        }
    }

    /**
     * Select a channel from the auto-detected list and populate the channel ID field.
     */
    fun selectDetectedChannel(channel: DetectedChannel) {
        _uiState.update {
            it.copy(channelId = channel.id.toString(), error = null)
        }
    }

    private fun detectChannels() {
        val token = _uiState.value.botToken.trim()
        viewModelScope.launch {
            val channels = repository.detectChannels(token)
            _uiState.update {
                it.copy(
                    detectedChannels = channels,
                    isDetectingChannels = false,
                )
            }
        }
    }

    private fun searchForBackup() {
        val token = _uiState.value.botToken.trim()
        val chatId = _uiState.value.channelId.trim()

        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingBackup = true, error = null) }
            val backupInfo = backupManager.findBackupInChannel(token, chatId)
            _uiState.update {
                it.copy(
                    isSearchingBackup = false,
                    backupInfo = backupInfo,
                )
            }
        }
    }

    fun restoreBackup() {
        val token = _uiState.value.botToken.trim()
        val chatId = _uiState.value.channelId.trim()
        val fileId = _uiState.value.backupInfo?.fileId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true, error = null) }
            backupManager.restoreFromTelegram(
                token = token,
                chatId = chatId,
                fileId = fileId,
            )
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isRestoring = false,
                            restoreComplete = true,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isRestoring = false,
                            error = "Restore failed: ${e.message}",
                        )
                    }
                }
        }
    }

    fun skipRestore() {
        viewModelScope.launch {
            repository.setOnboardingCompleted(true)
            _uiState.update { it.copy(isComplete = true) }
        }
    }

    /**
     * Restart the app after a successful database restore.
     */
    fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NEW_TASK
            )
            context.startActivity(intent)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                val repo = TelegramRepository(
                    api = TelegramApiService(),
                    metadataDao = db.metadataDao(),
                )
                val backupManager = BackupManager(app)
                return OnboardingViewModel(repo, backupManager) as T
            }
        }
    }
}
