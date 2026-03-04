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
import kotlinx.coroutines.Job
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
    val detectAttempt: Int = 0,
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

    private var detectJob: Job? = null
    private var detectOffset: Long = 0 // tracks getUpdates offset for long-polling
    private val DETECT_POLL_TIMEOUT = 5 // seconds for long-poll
    private val MAX_DETECT_ATTEMPTS = 24 // ~2 minutes (each attempt = 5s long-poll)

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
        val chatId = _uiState.value.channelId.trim()
        if (chatId.isBlank()) {
            _uiState.update { it.copy(error = "No channel selected yet") }
            return
        }

        // Stop polling once we start verifying
        detectJob?.cancel()

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
     * Select a channel from the auto-detected list.
     * Auto-verifies immediately — no manual "Verify" button needed.
     */
    fun selectDetectedChannel(channel: DetectedChannel) {
        _uiState.update {
            it.copy(channelId = channel.id.toString(), error = null)
        }
        // Auto-verify the selected channel immediately
        verifyChannel()
    }

    /**
     * Polls getUpdates with long-polling + offset tracking.
     * Each call blocks ~5s waiting for new updates from Telegram.
     *
     * When a channel is detected (from my_chat_member, channel_post, or message),
     * the bot AUTOMATICALLY sends a test message to verify admin permissions.
     * If verification succeeds → auto-proceeds. No user action needed.
     *
     * If the user added the bot as admin, just wait — the bot will detect
     * my_chat_member, send a test message, and auto-verify.
     */
    private fun detectChannels() {
        detectJob?.cancel()
        detectOffset = 0 // reset offset for fresh detection
        val token = _uiState.value.botToken.trim()
        detectJob = viewModelScope.launch {
            // First, drain any old updates by calling with offset=0
            val (oldChannels, initialOffset) = repository.detectChannels(token, offset = 0)
            detectOffset = initialOffset

            // If old pending updates already contain channels, try auto-verify immediately
            if (oldChannels.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        detectedChannels = oldChannels,
                        isDetectingChannels = true,
                    )
                }
                // Try to verify each channel by sending a test message
                for (ch in oldChannels) {
                    val result = repository.verifyChannel(token, ch.id.toString())
                    if (result.isSuccess) {
                        // Bot successfully sent message → channel verified!
                        val resolvedChatId = result.getOrNull()?.chat?.id?.toString()
                            ?: ch.id.toString()
                        repository.saveChatId(resolvedChatId)
                        _uiState.update {
                            it.copy(
                                isDetectingChannels = false,
                                isChannelVerified = true,
                                channelId = resolvedChatId,
                                error = null,
                                currentStep = 3,
                                isSearchingBackup = true,
                            )
                        }
                        searchForBackup()
                        return@launch
                    }
                }
                // None verified — show them but continue polling
            }

            var attempts = 0
            val allDetected = mutableMapOf<Long, com.tgstorage.data.remote.DetectedChannel>()
            for (ch in oldChannels) allDetected[ch.id] = ch

            while (attempts < MAX_DETECT_ATTEMPTS) {
                attempts++
                _uiState.update { it.copy(isDetectingChannels = true, detectAttempt = attempts) }

                val (channels, nextOffset) = repository.detectChannels(token, offset = detectOffset)
                detectOffset = nextOffset

                // Accumulate detected channels across polls
                for (ch in channels) {
                    allDetected[ch.id] = ch
                }

                // Try auto-verify each newly detected channel
                if (channels.isNotEmpty()) {
                    _uiState.update {
                        it.copy(detectedChannels = allDetected.values.toList())
                    }
                    for (ch in channels) {
                        val result = repository.verifyChannel(token, ch.id.toString())
                        if (result.isSuccess) {
                            // Bot sent test message → auto-verified!
                            val resolvedChatId = result.getOrNull()?.chat?.id?.toString()
                                ?: ch.id.toString()
                            repository.saveChatId(resolvedChatId)
                            _uiState.update {
                                it.copy(
                                    isDetectingChannels = false,
                                    isChannelVerified = true,
                                    channelId = resolvedChatId,
                                    error = null,
                                    currentStep = 3,
                                    isSearchingBackup = true,
                                )
                            }
                            searchForBackup()
                            return@launch
                        }
                    }
                }

                // No verified channel yet — long-poll handles the wait (~5s)
            }

            // Exhausted attempts — show what we found (if any) + manual fallback
            _uiState.update {
                it.copy(
                    isDetectingChannels = false,
                    detectedChannels = allDetected.values.toList(),
                    error = "Could not auto-detect a verified channel. " +
                            "Make sure the bot is admin with message permissions, " +
                            "or enter the Channel ID manually below.",
                )
            }
        }
    }

    /**
     * Manually restart channel detection polling.
     */
    fun retryDetectChannels() {
        _uiState.update { it.copy(error = null, detectedChannels = emptyList()) }
        detectOffset = 0
        detectChannels()
    }

    /**
     * Verify a manually entered channel ID.
     */
    fun verifyManualChannelId() {
        val chatId = _uiState.value.channelId.trim()
        if (chatId.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a channel ID") }
            return
        }
        // Stop any running detection
        detectJob?.cancel()
        _uiState.update { it.copy(isDetectingChannels = false) }
        verifyChannel()
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
