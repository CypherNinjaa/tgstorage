package com.tgstorage.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Telegram Bot API response models.
 * See: https://core.telegram.org/bots/api
 */

@Serializable
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
)

@Serializable
data class TelegramUser(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean,
    @SerialName("first_name") val firstName: String,
    val username: String? = null,
)

@Serializable
data class TelegramChat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
)

@Serializable
data class TelegramMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TelegramChat,
    val document: TelegramDocument? = null,
    val text: String? = null,
    val caption: String? = null,
)

@Serializable
data class TelegramDocument(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)

@Serializable
data class TelegramFile(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("file_path") val filePath: String? = null,
)

/**
 * Full chat info returned by getChat, includes pinned_message field.
 * See: https://core.telegram.org/bots/api#chatfullinfo
 */
@Serializable
data class TelegramChatFullInfo(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
    @SerialName("pinned_message") val pinnedMessage: TelegramMessage? = null,
)

// ─── getUpdates models (for channel auto-detection) ────

@Serializable
data class TelegramUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TelegramMessage? = null,
    @SerialName("channel_post") val channelPost: TelegramMessage? = null,
    @SerialName("my_chat_member") val myChatMember: ChatMemberUpdated? = null,
)

@Serializable
data class ChatMemberUpdated(
    val chat: TelegramChat,
    @SerialName("new_chat_member") val newChatMember: ChatMemberInfo,
)

@Serializable
data class ChatMemberInfo(
    val status: String, // "administrator", "member", "left", etc.
    val user: TelegramUser,
)

/**
 * Lightweight model for a detected channel the bot has access to.
 */
data class DetectedChannel(
    val id: Long,
    val title: String,
    val username: String? = null,
    val type: String,
)
