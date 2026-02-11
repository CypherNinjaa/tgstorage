package com.tgstorage.data.repository

import com.tgstorage.common.security.CryptoManager
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.remote.DetectedChannel
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.remote.TelegramMessage
import com.tgstorage.data.remote.TelegramUser
import java.io.File

/**
 * Repository for all Telegram Bot API interactions + config persistence.
 * Bot token is always stored encrypted via CryptoManager.
 */
class TelegramRepository(
    private val api: TelegramApiService,
    private val metadataDao: MetadataDao,
) {

    // ─── Token management ──────────────────────────────

    suspend fun saveToken(token: String) {
        val encrypted = CryptoManager.encryptString(token)
        metadataDao.setValue(MetadataEntity(key = MetadataKeys.BOT_TOKEN, value = encrypted))
    }

    suspend fun getToken(): String? {
        val encrypted = metadataDao.getValue(MetadataKeys.BOT_TOKEN) ?: return null
        return try {
            CryptoManager.decryptString(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun clearToken() {
        metadataDao.deleteValue(MetadataKeys.BOT_TOKEN)
    }

    // ─── Chat ID management ────────────────────────────

    suspend fun saveChatId(chatId: String) {
        metadataDao.setValue(MetadataEntity(key = MetadataKeys.CHAT_ID, value = chatId))
    }

    suspend fun getChatId(): String? = metadataDao.getValue(MetadataKeys.CHAT_ID)

    // ─── Onboarding state ──────────────────────────────

    suspend fun setOnboardingCompleted(completed: Boolean) {
        metadataDao.setValue(
            MetadataEntity(
                key = MetadataKeys.ONBOARDING_COMPLETED,
                value = completed.toString(),
            )
        )
    }

    suspend fun isOnboardingCompleted(): Boolean {
        return metadataDao.getValue(MetadataKeys.ONBOARDING_COMPLETED)?.toBoolean() == true
    }

    // ─── Bot API calls ─────────────────────────────────

    suspend fun validateToken(token: String): Result<TelegramUser> = api.getMe(token)

    suspend fun verifyChannel(
        token: String,
        chatId: String,
    ): Result<TelegramMessage> {
        val sendResult = api.sendMessage(
            token = token,
            chatId = chatId,
            text = "\u2705 TgStorage connected successfully! This message will be deleted.",
        )
        // Try to delete the verification message (best-effort)
        sendResult.getOrNull()?.let { msg ->
            api.deleteMessage(token, chatId, msg.messageId)
        }
        return sendResult
    }

    suspend fun sendDocument(
        token: String,
        chatId: String,
        file: File,
        fileName: String = file.name,
    ): Result<TelegramMessage> = api.sendDocument(token, chatId, file, fileName)

    /**
     * Auto-detect channels/groups the bot has access to via getUpdates.
     * Extracts chats from ALL update types:
     *  - my_chat_member: bot was added/promoted (one-shot, expires 24h)
     *  - channel_post: any post in a channel where the bot is admin
     *  - message: any message in a group/supergroup the bot can see
     * This is much more reliable than relying on my_chat_member alone.
     */
    suspend fun detectChannels(token: String): List<DetectedChannel> {
        val updates = api.getUpdates(token).getOrNull() ?: return emptyList()
        val validTypes = setOf("channel", "supergroup", "group")
        val detectedChats = mutableMapOf<Long, DetectedChannel>()

        for (update in updates) {
            // 1. From my_chat_member — bot was added/promoted
            update.myChatMember?.let { member ->
                val chat = member.chat
                if (chat.type in validTypes &&
                    member.newChatMember.status in listOf("administrator", "member", "creator")
                ) {
                    detectedChats[chat.id] = DetectedChannel(
                        id = chat.id,
                        title = chat.title ?: "Unknown",
                        username = chat.username,
                        type = chat.type,
                    )
                }
            }

            // 2. From channel_post — bot receives posts from channels it's admin of
            update.channelPost?.let { msg ->
                val chat = msg.chat
                if (chat.type in validTypes) {
                    detectedChats.putIfAbsent(
                        chat.id,
                        DetectedChannel(
                            id = chat.id,
                            title = chat.title ?: "Unknown",
                            username = chat.username,
                            type = chat.type,
                        ),
                    )
                }
            }

            // 3. From message — bot receives messages in groups
            update.message?.let { msg ->
                val chat = msg.chat
                if (chat.type in validTypes) {
                    detectedChats.putIfAbsent(
                        chat.id,
                        DetectedChannel(
                            id = chat.id,
                            title = chat.title ?: "Unknown",
                            username = chat.username,
                            type = chat.type,
                        ),
                    )
                }
            }
        }

        return detectedChats.values.toList()
    }
}
