package com.tgstorage.ui.lock

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.common.security.CryptoManager
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.remote.TelegramApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val PBKDF2_ITERATIONS = 120_000
private const val PBKDF2_KEY_LENGTH = 256

data class LockUiState(
    val passphrase: String = "",
    val isPassphraseVisible: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isUnlocked: Boolean = false,
    val forgotPasswordSent: Boolean = false,
    val forgotPasswordMessage: String? = null,
)

class LockViewModel(
    private val metadataDao: MetadataDao,
    private val api: TelegramApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    fun updatePassphrase(value: String) {
        _uiState.update { it.copy(passphrase = value, error = null) }
    }

    fun toggleVisibility() {
        _uiState.update { it.copy(isPassphraseVisible = !it.isPassphraseVisible) }
    }

    fun unlock() {
        val input = _uiState.value.passphrase.trim()
        if (input.isBlank()) {
            _uiState.update { it.copy(error = "Please enter your passphrase") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val verified = verifyPassphrase(input)
            if (verified) {
                // Delete the forgot-password message from Telegram if one was sent
                deleteForgotPasswordMessage()
                _uiState.update { it.copy(isLoading = false, isUnlocked = true) }
            } else {
                _uiState.update {
                    it.copy(isLoading = false, error = "Incorrect passphrase")
                }
            }
        }
    }

    fun forgotPassword() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, forgotPasswordMessage = null) }

            try {
                // Decrypt the stored passphrase
                val encryptedPassphrase = metadataDao.getValue(MetadataKeys.PASSPHRASE_ENCRYPTED)
                if (encryptedPassphrase == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Recovery not available. Please reinstall the app.",
                        )
                    }
                    return@launch
                }

                val plainPassphrase = CryptoManager.decryptString(encryptedPassphrase)

                // Get bot token and chat ID
                val encryptedToken = metadataDao.getValue(MetadataKeys.BOT_TOKEN)
                val chatId = metadataDao.getValue(MetadataKeys.CHAT_ID)
                if (encryptedToken == null || chatId == null) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Bot configuration missing")
                    }
                    return@launch
                }

                val token = CryptoManager.decryptString(encryptedToken)

                // Send passphrase to Telegram channel
                val messageText = "\uD83D\uDD10 Your TgStorage passphrase:\n\n" +
                        "$plainPassphrase\n\n" +
                        "\u26A0\uFE0F This message will be auto-deleted after you log in."

                val result = api.sendMessage(
                    token = token,
                    chatId = chatId,
                    text = messageText,
                )

                result.onSuccess { msg ->
                    // Store message ID so we can delete it after successful unlock
                    metadataDao.setValue(
                        MetadataEntity(
                            key = MetadataKeys.FORGOT_PASSWORD_MSG_ID,
                            value = msg.messageId.toString(),
                        )
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            forgotPasswordSent = true,
                            forgotPasswordMessage = "Passphrase sent to your Telegram channel. Check now!",
                        )
                    }
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to send: ${e.message}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Recovery failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun deleteForgotPasswordMessage() {
        try {
            val msgIdStr = metadataDao.getValue(MetadataKeys.FORGOT_PASSWORD_MSG_ID) ?: return
            val msgId = msgIdStr.toLongOrNull() ?: return
            val encryptedToken = metadataDao.getValue(MetadataKeys.BOT_TOKEN) ?: return
            val chatId = metadataDao.getValue(MetadataKeys.CHAT_ID) ?: return
            val token = CryptoManager.decryptString(encryptedToken)

            api.deleteMessage(token, chatId, msgId)
            metadataDao.deleteValue(MetadataKeys.FORGOT_PASSWORD_MSG_ID)
        } catch (_: Exception) {
            // Best-effort delete
        }
    }

    private suspend fun verifyPassphrase(input: String): Boolean {
        val saltBase64 = metadataDao.getValue(MetadataKeys.PASSPHRASE_SALT) ?: return false
        val hashBase64 = metadataDao.getValue(MetadataKeys.PASSPHRASE_HASH) ?: return false
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val expected = Base64.decode(hashBase64, Base64.NO_WRAP)
        val actual = hashPassphrase(input.toCharArray(), salt)
        return expected.contentEquals(actual)
    }

    private fun hashPassphrase(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = TgStorageApp.instance.database
                return LockViewModel(
                    metadataDao = db.metadataDao(),
                    api = TelegramApiService(),
                ) as T
            }
        }
    }
}
