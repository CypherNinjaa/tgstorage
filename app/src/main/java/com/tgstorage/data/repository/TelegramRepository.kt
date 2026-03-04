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
     * Auto-detect channels/groups the bot has access to via getUpdates with long-polling.
     * Extracts chats from ALL update types:
     *  - my_chat_member: bot was added/promoted (one-shot, expires 24h)
     *  - channel_post: any post in a channel where the bot is admin
     *  - message: any message in a group/supergroup the bot can see
     *
     * @param offset Pass the last known updateId + 1 to skip old updates and wait for new ones.
     * @return Pair of (detected channels, highest updateId seen) so caller can advance offset.
     */
    suspend fun detectChannels(
        token: String,
        offset: Long = 0,
    ): Pair<List<DetectedChannel>, Long> {
        val updates = api.getUpdates(token, offset = offset, timeout = 5)
            .getOrNull() ?: return Pair(emptyList(), offset)

        val validTypes = setOf("channel", "supergroup", "group")
        val detectedChats = mutableMapOf<Long, DetectedChannel>()
        var maxUpdateId = offset - 1

        for (update in updates) {
            if (update.updateId > maxUpdateId) maxUpdateId = update.updateId

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

        // Return maxUpdateId + 1 as next offset to acknowledge these updates
        val nextOffset = if (maxUpdateId >= offset) maxUpdateId + 1 else offset
        return Pair(detectedChats.values.toList(), nextOffset)
    }
}
