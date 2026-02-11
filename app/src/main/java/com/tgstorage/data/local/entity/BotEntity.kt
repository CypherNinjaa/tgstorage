package com.tgstorage.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a Telegram bot configured for parallel uploads.
 * Each bot can upload independently, enabling parallel file transfers.
 */
@Entity(tableName = "bots")
data class BotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * User-friendly name for this bot (e.g., "Bot 1", "Fast Upload Bot")
     */
    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Bot token (encrypted via CryptoManager)
     */
    @ColumnInfo(name = "token_encrypted")
    val tokenEncrypted: String,

    /**
     * The private channel/group chat ID this bot sends to.
     * All bots should use the same channel for consolidated storage.
     */
    @ColumnInfo(name = "chat_id")
    val chatId: String,

    /**
     * Whether this bot is currently active for uploads
     */
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    /**
     * Whether this bot has been verified (can send messages)
     */
    @ColumnInfo(name = "is_verified")
    val isVerified: Boolean = false,

    /**
     * Timestamp when the bot was added
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Timestamp when the bot was last verified
     */
    @ColumnInfo(name = "verified_at")
    val verifiedAt: Long? = null,

    /**
     * Is this the primary/main bot (the original one from onboarding)
     */
    @ColumnInfo(name = "is_primary")
    val isPrimary: Boolean = false,
)
