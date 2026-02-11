package com.tgstorage.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.BotEntity
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.repository.BotRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BotSettingsUiState(
    val bots: List<BotEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    
    // Add bot dialog state
    val showAddDialog: Boolean = false,
    val newBotName: String = "",
    val newBotToken: String = "",
    val isAddingBot: Boolean = false,
    val addBotError: String? = null,
    
    // Verify dialog state
    val verifyingBotId: Long? = null,
)

class BotSettingsViewModel(
    private val botRepository: BotRepository,
) : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TgStorageApp.instance
                val db = app.database
                val botRepo = BotRepository(
                    botDao = db.botDao(),
                    metadataDao = db.metadataDao(),
                    api = TelegramApiService(),
                )
                return BotSettingsViewModel(botRepo) as T
            }
        }
    }

    private val _uiState = MutableStateFlow(BotSettingsUiState())
    val uiState: StateFlow<BotSettingsUiState> = _uiState.asStateFlow()

    init {
        observeBots()
    }

    private fun observeBots() {
        viewModelScope.launch {
            botRepository.getAllBots().collect { bots ->
                _uiState.update { it.copy(bots = bots, isLoading = false) }
            }
        }
    }

    // ─── Add Bot Dialog ────────────────────────────────

    fun showAddBotDialog() {
        _uiState.update { 
            it.copy(
                showAddDialog = true,
                newBotName = "Bot ${it.bots.size + 1}",
                newBotToken = "",
                addBotError = null,
            )
        }
    }

    fun hideAddBotDialog() {
        _uiState.update { 
            it.copy(
                showAddDialog = false,
                newBotName = "",
                newBotToken = "",
                addBotError = null,
                isAddingBot = false,
            )
        }
    }

    fun updateNewBotName(name: String) {
        _uiState.update { it.copy(newBotName = name) }
    }

    fun updateNewBotToken(token: String) {
        _uiState.update { it.copy(newBotToken = token, addBotError = null) }
    }

    fun addBot() {
        val state = _uiState.value
        val name = state.newBotName.trim()
        val token = state.newBotToken.trim()

        if (name.isBlank()) {
            _uiState.update { it.copy(addBotError = "Please enter a name for the bot") }
            return
        }

        if (token.isBlank()) {
            _uiState.update { it.copy(addBotError = "Please enter the bot token") }
            return
        }

        // Basic token format validation
        if (!token.matches(Regex("^\\d+:[A-Za-z0-9_-]+$"))) {
            _uiState.update { it.copy(addBotError = "Invalid token format. Get it from @BotFather") }
            return
        }

        _uiState.update { it.copy(isAddingBot = true, addBotError = null) }

        viewModelScope.launch {
            try {
                // Validate token with Telegram API
                val api = TelegramApiService()
                val meResult = api.getMe(token)
                
                if (meResult.isFailure) {
                    _uiState.update { 
                        it.copy(
                            isAddingBot = false, 
                            addBotError = "Invalid token: ${meResult.exceptionOrNull()?.message}",
                        )
                    }
                    return@launch
                }

                // Get channel ID from primary bot (all bots use the same channel)
                val primaryBot = botRepository.getPrimaryBot()
                val chatId = primaryBot?.chatId
                
                if (chatId == null) {
                    _uiState.update { 
                        it.copy(
                            isAddingBot = false, 
                            addBotError = "No primary bot configured. Complete onboarding first.",
                        )
                    }
                    return@launch
                }

                // Verify the bot can send to the channel
                val sendResult = api.sendMessage(
                    token = token,
                    chatId = chatId,
                    text = "✅ Bot '$name' connected to TgStorage for parallel uploads!",
                )

                if (sendResult.isFailure) {
                    _uiState.update { 
                        it.copy(
                            isAddingBot = false, 
                            addBotError = "Bot cannot send to channel. Make sure it's an admin.",
                        )
                    }
                    return@launch
                }

                // Delete the test message
                sendResult.getOrNull()?.let { msg ->
                    api.deleteMessage(token, chatId, msg.messageId)
                }

                // Add the bot to database
                val botId = botRepository.addBot(
                    name = name,
                    token = token,
                    chatId = chatId,
                    isPrimary = false,
                )

                if (botId == -1L) {
                    _uiState.update { 
                        it.copy(
                            isAddingBot = false, 
                            addBotError = "A bot with this token already exists",
                        )
                    }
                    return@launch
                }

                // Mark as verified since we just tested it
                botRepository.verifyBot(botId)

                _uiState.update { 
                    it.copy(
                        showAddDialog = false,
                        newBotName = "",
                        newBotToken = "",
                        isAddingBot = false,
                        message = "Bot '$name' added successfully!",
                    )
                }

            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isAddingBot = false, 
                        addBotError = "Error: ${e.message}",
                    )
                }
            }
        }
    }

    // ─── Bot Actions ───────────────────────────────────

    fun toggleBotActive(bot: BotEntity) {
        viewModelScope.launch {
            botRepository.setActive(bot.id, !bot.isActive)
        }
    }

    fun verifyBot(botId: Long) {
        _uiState.update { it.copy(verifyingBotId = botId) }
        
        viewModelScope.launch {
            val result = botRepository.verifyBot(botId)
            _uiState.update { 
                it.copy(
                    verifyingBotId = null,
                    message = result.getOrNull(),
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun deleteBot(botId: Long) {
        viewModelScope.launch {
            try {
                botRepository.deleteBot(botId)
                _uiState.update { it.copy(message = "Bot deleted") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun setPrimary(botId: Long) {
        viewModelScope.launch {
            botRepository.setPrimary(botId)
            _uiState.update { it.copy(message = "Primary bot updated") }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }
}
