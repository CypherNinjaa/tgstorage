package com.tgstorage.data.remote

import android.util.Log
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.repository.TelegramRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Validates the Telegram bot token health and handles revocation.
 * Exposes a [tokenStatus] flow for the UI to observe.
 *
 * Phase 9 — Hardening: token expiry / revocation handling.
 */
object TokenValidator {

    private const val TAG = "TokenValidator"

    /** Metadata key for storing the last successful token validation timestamp. */
    private const val LAST_VALID_CHECK = "token_last_valid_check"

    /** Minimum interval between token health checks (1 hour). */
    private const val CHECK_INTERVAL_MS = 60L * 60 * 1000

    sealed class TokenStatus {
        data object Valid : TokenStatus()
        data object Invalid : TokenStatus()
        data object Revoked : TokenStatus()
        data object RateLimited : TokenStatus()
        data object NetworkError : TokenStatus()
        data object Unknown : TokenStatus()
    }

    private val _tokenStatus = MutableStateFlow<TokenStatus>(TokenStatus.Unknown)
    val tokenStatus: StateFlow<TokenStatus> = _tokenStatus.asStateFlow()

    /**
     * Validates the token by calling getMe. Only performs the check
     * if [CHECK_INTERVAL_MS] has elapsed since the last successful check.
     *
     * @return true if token is valid
     */
    suspend fun validateToken(
        api: TelegramApiService,
        token: String,
        metadataDao: MetadataDao,
        force: Boolean = false,
    ): Boolean {
        if (!force) {
            val lastCheck = metadataDao.getValue(LAST_VALID_CHECK)?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                // Recent check was good, don't re-validate
                _tokenStatus.value = TokenStatus.Valid
                return true
            }
        }

        return try {
            val result = api.getMe(token)
            result.fold(
                onSuccess = {
                    _tokenStatus.value = TokenStatus.Valid
                    metadataDao.setValue(
                        MetadataEntity(
                            key = LAST_VALID_CHECK,
                            value = System.currentTimeMillis().toString(),
                        )
                    )
                    Log.d(TAG, "Token validated OK: @${it.username}")
                    true
                },
                onFailure = { e ->
                    handleTokenError(e)
                    false
                },
            )
        } catch (e: Exception) {
            handleTokenError(e)
            false
        }
    }

    /**
     * Classifies a Telegram API error into the appropriate [TokenStatus].
     */
    private fun handleTokenError(error: Throwable) {
        when (error) {
            is TelegramApiException -> {
                when (error.errorCode) {
                    401 -> {
                        Log.e(TAG, "Token is unauthorized (401) — revoked or invalid")
                        _tokenStatus.value = TokenStatus.Revoked
                    }
                    403 -> {
                        Log.e(TAG, "Token is forbidden (403)")
                        _tokenStatus.value = TokenStatus.Revoked
                    }
                    429 -> {
                        Log.w(TAG, "Rate limited (429)")
                        _tokenStatus.value = TokenStatus.RateLimited
                    }
                    else -> {
                        Log.e(TAG, "API error ${error.errorCode}: ${error.message}")
                        _tokenStatus.value = TokenStatus.Invalid
                    }
                }
            }
            is java.net.UnknownHostException,
            is java.net.SocketTimeoutException,
            is java.io.IOException -> {
                Log.w(TAG, "Network error during token validation: ${error.message}")
                _tokenStatus.value = TokenStatus.NetworkError
            }
            else -> {
                Log.e(TAG, "Unknown error during token validation", error)
                _tokenStatus.value = TokenStatus.Invalid
            }
        }
    }

    /**
     * Checks if a Telegram API error indicates the token has been revoked.
     * Useful for inline error handling in upload/download paths.
     */
    fun isTokenRevoked(error: Throwable): Boolean {
        return error is TelegramApiException && error.errorCode in listOf(401, 403)
    }

    /**
     * Checks if a Telegram API error is a rate limit.
     */
    fun isRateLimited(error: Throwable): Boolean {
        return error is TelegramApiException && error.errorCode == 429
    }

    /** Reset status (e.g., when user enters a new token). */
    fun reset() {
        _tokenStatus.value = TokenStatus.Unknown
    }
}
