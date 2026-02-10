package com.tgstorage.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.remote.TelegramUser
import com.tgstorage.data.repository.TelegramRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val currentStep: Int = 0,  // 0=HowItWorks, 1=BotToken, 2=VerifyChannel
    val botToken: String = "",
    val channelId: String = "",
    val isTokenVisible: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val botUser: TelegramUser? = null,
    val isTokenValid: Boolean = false,
    val isChannelVerified: Boolean = false,
    val isComplete: Boolean = false,
)

class OnboardingViewModel(
    private val repository: TelegramRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun nextStep() {
        _uiState.update { it.copy(currentStep = (it.currentStep + 1).coerceAtMost(2), error = null) }
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
                        )
                    }
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
                    repository.setOnboardingCompleted(true)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isChannelVerified = true,
                            channelId = resolvedChatId,
                            error = null,
                            isComplete = true,
                        )
                    }
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

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = TgStorageApp.instance.database
                val repo = TelegramRepository(
                    api = TelegramApiService(),
                    metadataDao = db.metadataDao(),
                )
                return OnboardingViewModel(repo) as T
            }
        }
    }
}
