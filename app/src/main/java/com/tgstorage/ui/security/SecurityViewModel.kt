package com.tgstorage.ui.security

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tgstorage.TgStorageApp
import com.tgstorage.common.security.CryptoManager
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val PBKDF2_ITERATIONS = 120_000
private const val PBKDF2_KEY_LENGTH = 256


data class SecurityUiState(
    val encryptionEnabled: Boolean = true,
    val isHardwareBacked: Boolean = false,
    val keyCreatedAt: Long? = null,
    val passphraseSet: Boolean = false,
    val currentPassphrase: String = "",
    val newPassphrase: String = "",
    val confirmPassphrase: String = "",
    val isPassphraseVisible: Boolean = false,
    val isWorking: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class SecurityViewModel : ViewModel() {

    private val app = TgStorageApp.instance
    private val metadataDao = app.database.metadataDao()

    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        observeState()
    }

    fun toggleEncryption(enabled: Boolean) {
        viewModelScope.launch {
            metadataDao.setValue(MetadataEntity(MetadataKeys.ENCRYPTION_ENABLED, enabled.toString()))
            _uiState.update { it.copy(encryptionEnabled = enabled) }
        }
    }

    fun togglePassphraseVisibility() {
        _uiState.update { it.copy(isPassphraseVisible = !it.isPassphraseVisible) }
    }

    fun updateCurrentPassphrase(value: String) {
        _uiState.update { it.copy(currentPassphrase = value) }
    }

    fun updateNewPassphrase(value: String) {
        _uiState.update { it.copy(newPassphrase = value) }
    }

    fun updateConfirmPassphrase(value: String) {
        _uiState.update { it.copy(confirmPassphrase = value) }
    }

    fun savePassphrase() {
        val state = _uiState.value
        if (state.newPassphrase.length < 6) {
            _uiState.update { it.copy(error = "Passphrase must be at least 6 characters") }
            return
        }
        if (state.newPassphrase != state.confirmPassphrase) {
            _uiState.update { it.copy(error = "Passphrases do not match") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null, message = null) }

            if (state.passphraseSet && !verifyCurrentPassphrase(state.currentPassphrase)) {
                _uiState.update {
                    it.copy(isWorking = false, error = "Current passphrase is incorrect")
                }
                return@launch
            }

            val salt = SecureRandom().generateSeed(16)
            val hash = hashPassphrase(state.newPassphrase.toCharArray(), salt)
            metadataDao.setValue(MetadataEntity(MetadataKeys.PASSPHRASE_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)))
            metadataDao.setValue(MetadataEntity(MetadataKeys.PASSPHRASE_HASH, Base64.encodeToString(hash, Base64.NO_WRAP)))
            // Store encrypted plaintext for "forgot password" recovery via Telegram
            metadataDao.setValue(MetadataEntity(MetadataKeys.PASSPHRASE_ENCRYPTED, CryptoManager.encryptString(state.newPassphrase)))

            _uiState.update {
                it.copy(
                    isWorking = false,
                    message = if (state.passphraseSet) "Passphrase updated" else "Passphrase set",
                    currentPassphrase = "",
                    newPassphrase = "",
                    confirmPassphrase = "",
                    passphraseSet = true,
                )
            }
        }
    }

    fun wipeAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null, message = null) }
            runCatching {
                app.database.clearAllTables()
                deleteDir(app.filesDir)
                deleteDir(app.cacheDir)
                deleteDir(app.getExternalFilesDir(null) ?: File(app.filesDir, "external"))
            }.onFailure { e ->
                _uiState.update { it.copy(isWorking = false, error = e.message ?: "Failed to wipe data") }
            }.onSuccess {
                _uiState.update { it.copy(isWorking = false, message = "All local data wiped. Restart the app.") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    private fun observeState() {
        viewModelScope.launch {
            val encryptionRaw = metadataDao.getValue(MetadataKeys.ENCRYPTION_ENABLED)
            val encryption = encryptionRaw?.toBoolean() ?: true
            // Persist the default so ChunkManager always finds a DB value
            if (encryptionRaw == null) {
                metadataDao.setValue(MetadataEntity(MetadataKeys.ENCRYPTION_ENABLED, "true"))
            }
            val passphraseHash = metadataDao.getValue(MetadataKeys.PASSPHRASE_HASH)
            val createdAt = metadataDao.getValue(MetadataKeys.KEY_CREATED_AT)?.toLongOrNull()
            if (createdAt == null) {
                metadataDao.setValue(
                    MetadataEntity(MetadataKeys.KEY_CREATED_AT, System.currentTimeMillis().toString())
                )
            }

            _uiState.update {
                it.copy(
                    encryptionEnabled = encryption,
                    passphraseSet = !passphraseHash.isNullOrBlank(),
                    keyCreatedAt = createdAt ?: System.currentTimeMillis(),
                    isHardwareBacked = CryptoManager.isHardwareBacked(),
                )
            }
        }
    }

    private suspend fun verifyCurrentPassphrase(current: String): Boolean {
        if (current.isBlank()) return false
        val saltBase64 = metadataDao.getValue(MetadataKeys.PASSPHRASE_SALT) ?: return false
        val hashBase64 = metadataDao.getValue(MetadataKeys.PASSPHRASE_HASH) ?: return false
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val expected = Base64.decode(hashBase64, Base64.NO_WRAP)
        val actual = hashPassphrase(current.toCharArray(), salt)
        return expected.contentEquals(actual)
    }

    private fun hashPassphrase(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
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
                return SecurityViewModel() as T
            }
        }
    }
}
