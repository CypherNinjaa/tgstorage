package com.tgstorage.data.transfer

import android.util.Log
import com.tgstorage.data.local.dao.ChunkDao
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.entity.ChunkEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.remote.TelegramApiException
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.repository.BotRepository
import kotlinx.coroutines.delay

/**
 * Recovers stale Telegram file_ids that cause "Bad Request: wrong file_id" errors.
 *
 * HOW IT WORKS:
 * 1. Each uploaded chunk has a `telegram_message_id` (the message number in the channel).
 * 2. When `getFile` fails because the `file_id` has gone stale, we can recover:
 *    a) Forward the original message to the **same channel** via `forwardMessage`.
 *    b) The forwarded message contains a fresh `document.file_id`.
 *    c) Update the chunk's `telegram_file_id` in the database.
 *    d) Delete the forwarded copy to keep the channel clean.
 *    e) Retry the download with the new file_id.
 *
 * WHY THIS IS NECESSARY:
 * - Telegram's `file_id` values can become invalid over time.
 * - The bot API documentation says "Each file object has a unique file_id which can be
 *   used to retrieve the file. However, it is not guaranteed that the file_id will remain
 *   valid forever."
 * - The `file_unique_id` is permanent but cannot be used with `getFile` — it's only for
 *   deduplication/identity checks.
 * - The `message_id` + `chat_id` pair is permanent and can always retrieve the message.
 *
 * FALLBACK STRATEGY:
 * - If the primary bot can't forward (e.g., removed from channel), try other active bots.
 * - If no bot works, the recovery fails and user must restore from backup.
 */
class FileRecoveryManager(
    private val api: TelegramApiService,
    private val chunkDao: ChunkDao,
    private val metadataDao: MetadataDao,
    private val botRepository: BotRepository,
) {
    companion object {
        private const val TAG = "FileRecoveryManager"
        private const val MAX_RECOVERY_RETRIES = 2

        /**
         * Check if an error message indicates a stale file_id.
         */
        fun isStaleFileIdError(errorMessage: String): Boolean {
            val msg = errorMessage.lowercase()
            return msg.contains("wrong file_id") ||
                msg.contains("file is temporarily unavailable") ||
                (msg.contains("bad request") && msg.contains("file"))
        }

        /**
         * Check if a Throwable is a stale file_id error.
         */
        fun isStaleFileIdError(error: Throwable): Boolean {
            return isStaleFileIdError(error.message ?: "")
        }
    }

    /**
     * Refresh a single chunk's file_id by forwarding its message and
     * extracting the fresh document.file_id from the forwarded copy.
     *
     * @return The new file_id, or null if recovery failed.
     */
    suspend fun refreshChunkFileId(
        token: String,
        chatId: String,
        chunk: ChunkEntity,
    ): String? {
        val messageId = chunk.telegramMessageId
        if (messageId == null) {
            Log.w(TAG, "Chunk ${chunk.id} has no message_id — cannot recover")
            return null
        }

        return try {
            Log.i(TAG, "Recovering file_id for chunk ${chunk.chunkIndex} (msg $messageId)")

            // Step 1: Forward the original message to the same channel
            val forwardedMsg = api.forwardMessage(
                token = token,
                chatId = chatId,
                fromChatId = chatId,
                messageId = messageId,
            ).getOrThrow()

            val newFileId = forwardedMsg.document?.fileId
            val newFileUniqueId = forwardedMsg.document?.fileUniqueId

            if (newFileId == null) {
                Log.w(TAG, "Forwarded message has no document — original may not be a file")
                // Clean up the forwarded message
                tryDeleteMessage(token, chatId, forwardedMsg.messageId)
                return null
            }

            // Step 2: Update the chunk in the database with the fresh file_id
            chunkDao.updateChunkFileId(chunk.id, newFileId)

            // Also store the file_unique_id if we don't have it yet
            if (newFileUniqueId != null) {
                chunkDao.updateChunkFileUniqueId(chunk.id, newFileUniqueId)
            }

            Log.i(TAG, "Recovered chunk ${chunk.chunkIndex}: old=${chunk.telegramFileId?.take(20)}… new=${newFileId.take(20)}…")

            // Step 3: Delete the forwarded copy to keep the channel clean
            tryDeleteMessage(token, chatId, forwardedMsg.messageId)

            // Invalidate any cached file_path for the old file_id
            // (The cache uses file_id as key, so old entry won't match anyway)

            newFileId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recover chunk ${chunk.id}: ${e.message}")
            null
        }
    }

    /**
     * Recover all chunks for a file by refreshing their file_ids.
     * Returns the number of chunks successfully recovered.
     */
    suspend fun recoverAllChunksForFile(
        token: String,
        chatId: String,
        fileId: Long,
    ): Int {
        val chunks = chunkDao.getChunksForFileSync(fileId)
        var recovered = 0

        for (chunk in chunks) {
            if (chunk.telegramMessageId == null) continue

            val newFileId = refreshChunkFileId(token, chatId, chunk)
            if (newFileId != null) {
                recovered++
            }

            // Small delay between forwards to avoid rate limiting
            if (chunks.size > 1) delay(500)
        }

        Log.i(TAG, "Recovered $recovered/${chunks.size} chunks for file $fileId")
        return recovered
    }

    /**
     * Try to recover a stale file_id using all available bots.
     * Falls back through bots if the primary one fails.
     *
     * @return The new file_id, or null if all bots fail.
     */
    suspend fun refreshChunkFileIdWithFallback(
        chunk: ChunkEntity,
    ): String? {
        val chatId = metadataDao.getValue(MetadataKeys.CHAT_ID) ?: return null

        // Try with active bots first (they have proper chat access)
        val activeBots = botRepository.getActiveBotsWithTokens()
        for ((_, token) in activeBots) {
            val result = refreshChunkFileId(token, chatId, chunk)
            if (result != null) return result
            delay(300) // Brief delay before trying next bot
        }

        // Try with primary bot token from metadata
        val encryptedToken = metadataDao.getValue(MetadataKeys.BOT_TOKEN)
        if (encryptedToken != null) {
            val token = try {
                com.tgstorage.common.security.CryptoManager.decrypt(
                    android.util.Base64.decode(encryptedToken, android.util.Base64.NO_WRAP)
                ).decodeToString()
            } catch (_: Exception) { null }

            if (token != null) {
                val result = refreshChunkFileId(token, chatId, chunk)
                if (result != null) return result
            }
        }

        Log.e(TAG, "All bots failed to recover chunk ${chunk.id}")
        return null
    }

    /**
     * Attempt to download using getFile, with automatic file_id recovery
     * if the first attempt fails with a stale file_id error.
     *
     * @return A valid file_path string for downloading the chunk.
     */
    suspend fun getFilePathWithRecovery(
        token: String,
        chatId: String,
        chunk: ChunkEntity,
    ): String {
        val fileId = chunk.telegramFileId
            ?: throw IllegalStateException("Chunk ${chunk.chunkIndex} has no Telegram file_id")

        // First try: use the cached/stored file_id
        val firstAttempt = api.getFilePathCached(token, fileId)
        if (firstAttempt.isSuccess) {
            return firstAttempt.getOrThrow()
        }

        // Check if it's a recoverable error
        val error = firstAttempt.exceptionOrNull()
            ?: throw IllegalStateException("Unknown error getting file")

        if (!isStaleFileIdError(error)) {
            throw error // Not a file_id issue, rethrow
        }

        Log.w(TAG, "Stale file_id detected for chunk ${chunk.chunkIndex}, attempting recovery…")

        // Recovery: forward message to get fresh file_id
        var newFileId: String? = null
        repeat(MAX_RECOVERY_RETRIES) { attempt ->
            newFileId = refreshChunkFileId(token, chatId, chunk)
            if (newFileId != null) return@repeat
            delay(1000L * (attempt + 1)) // backoff between retries
        }

        if (newFileId == null) {
            // Try with other bots as fallback
            newFileId = refreshChunkFileIdWithFallback(chunk)
        }

        if (newFileId == null) {
            throw IllegalStateException(
                "Cannot recover file_id for chunk ${chunk.chunkIndex}. " +
                "The message may have been deleted from the channel. " +
                "Try restoring from your latest backup."
            )
        }

        // Retry getFile with the fresh file_id
        return api.getFilePathCached(token, newFileId!!).getOrThrow()
    }

    /**
     * Try to delete a message (best-effort, don't fail on error).
     */
    private suspend fun tryDeleteMessage(token: String, chatId: String, messageId: Long) {
        try {
            api.deleteMessage(token, chatId, messageId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete forwarded message $messageId: ${e.message}")
        }
    }
}
