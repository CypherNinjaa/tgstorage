package com.tgstorage.data.repository

import com.tgstorage.common.security.CryptoManager
import com.tgstorage.data.local.dao.BotDao
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.entity.BotEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.remote.TelegramApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for managing multiple Telegram bots.
 * Handles bot CRUD, verification, and provides decrypted tokens for uploads.
 */
class BotRepository(
    private val botDao: BotDao,
    private val metadataDao: MetadataDao,
    private val api: TelegramApiService,
) {

    // ─── Bot listing ───────────────────────────────────

    fun getAllBots(): Flow<List<BotEntity>> = botDao.getAllBots()

    fun getActiveBots(): Flow<List<BotEntity>> = botDao.getActiveBotsFlow()

    suspend fun getActiveBotsOnce(): List<BotEntity> = botDao.getActiveBots()

    suspend fun getActiveBotCount(): Int = botDao.getActiveBotCount()

    fun getBotCount(): Flow<Int> = botDao.getBotCount()

    suspend fun getBotById(id: Long): BotEntity? = botDao.getById(id)

    suspend fun getPrimaryBot(): BotEntity? = botDao.getPrimaryBot()

    // ─── Add bot ───────────────────────────────────────

    /**
     * Add a new bot. Encrypts the token before storage.
     * @return The inserted bot's ID, or -1 if bot with same token exists
     */
    suspend fun addBot(
        name: String,
        token: String,
        chatId: String,
        isPrimary: Boolean = false,
    ): Long {
        val encryptedToken = CryptoManager.encryptString(token)
        
        // Check if bot with this token already exists
        if (botDao.countByToken(encryptedToken) > 0) {
            return -1L
        }

        // If this is primary, clear other primary flags
        if (isPrimary) {
            botDao.clearPrimaryFlag()
        }

        val bot = BotEntity(
            name = name,
            tokenEncrypted = encryptedToken,
            chatId = chatId,
            isPrimary = isPrimary,
            isVerified = false,
        )
        return botDao.insert(bot)
    }

    /**
     * Migrate the legacy single-bot setup to the multi-bot table.
     * Called on app upgrade to preserve existing configuration.
     */
    suspend fun migrateLegacyBot() {
        // Check if we already have bots
        val existingBots = botDao.getAllBotsOnce()
        if (existingBots.isNotEmpty()) return

        // Get legacy token and chat_id from metadata
        val encryptedToken = metadataDao.getValue(MetadataKeys.BOT_TOKEN) ?: return
        val chatId = metadataDao.getValue(MetadataKeys.CHAT_ID) ?: return

        // Insert as primary bot (already encrypted)
        val bot = BotEntity(
            name = "Primary Bot",
            tokenEncrypted = encryptedToken,
            chatId = chatId,
            isPrimary = true,
            isActive = true,
            isVerified = true, // Assume verified since it was working
            verifiedAt = System.currentTimeMillis(),
        )
        botDao.insert(bot)
    }

    // ─── Verify bot ────────────────────────────────────

    /**
     * Verify a bot by calling getMe and sending a test message.
     * @return Result with success message or error
     */
    suspend fun verifyBot(botId: Long): Result<String> {
        val bot = botDao.getById(botId) ?: return Result.failure(Exception("Bot not found"))
        
        val token = try {
            CryptoManager.decryptString(bot.tokenEncrypted)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to decrypt token"))
        }

        // Verify token with getMe
        val meResult = api.getMe(token)
        if (meResult.isFailure) {
            botDao.setVerified(botId, false, null)
            return Result.failure(Exception("Invalid token: ${meResult.exceptionOrNull()?.message}"))
        }

        // Try sending a test message
        val sendResult = api.sendMessage(
            token = token,
            chatId = bot.chatId,
            text = "✅ Bot '${bot.name}' verified for TgStorage multi-bot uploads!",
        )
        
        if (sendResult.isFailure) {
            botDao.setVerified(botId, false, null)
            return Result.failure(Exception("Bot cannot send to channel: ${sendResult.exceptionOrNull()?.message}"))
        }

        // Delete the test message
        sendResult.getOrNull()?.let { msg ->
            api.deleteMessage(token, bot.chatId, msg.messageId)
        }

        // Mark as verified
        botDao.setVerified(botId, true, System.currentTimeMillis())
        return Result.success("Bot '${bot.name}' verified successfully")
    }

    // ─── Update / Delete ───────────────────────────────

    suspend fun updateBot(bot: BotEntity) {
        botDao.update(bot)
    }

    suspend fun deleteBot(botId: Long) {
        // Don't allow deleting primary bot if it's the only one
        val bot = botDao.getById(botId) ?: return
        val botCount = botDao.getActiveBotCount()
        
        if (bot.isPrimary && botCount <= 1) {
            throw IllegalStateException("Cannot delete the only active bot")
        }

        botDao.deleteById(botId)
    }

    suspend fun setActive(botId: Long, isActive: Boolean) {
        botDao.setActive(botId, isActive)
    }

    suspend fun setPrimary(botId: Long) {
        botDao.clearPrimaryFlag()
        botDao.setPrimary(botId)
    }

    // ─── Get decrypted token ───────────────────────────

    /**
     * Get decrypted token for a bot. Used by upload managers.
     */
    suspend fun getDecryptedToken(botId: Long): String? {
        val bot = botDao.getById(botId) ?: return null
        return try {
            CryptoManager.decryptString(bot.tokenEncrypted)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get all active bots with decrypted tokens for parallel uploads.
     * @return List of pairs (BotEntity, decryptedToken)
     */
    suspend fun getActiveBotsWithTokens(): List<Pair<BotEntity, String>> {
        val bots = botDao.getActiveBots()
        return bots.mapNotNull { bot ->
            try {
                val token = CryptoManager.decryptString(bot.tokenEncrypted)
                bot to token
            } catch (e: Exception) {
                null
            }
        }
    }
}
