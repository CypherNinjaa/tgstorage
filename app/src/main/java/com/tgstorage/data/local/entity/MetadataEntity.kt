package com.tgstorage.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "metadata")
data class MetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String,
)

object MetadataKeys {
    const val BOT_TOKEN = "bot_token"
    const val CHAT_ID = "chat_id"
    const val ONBOARDING_COMPLETED = "onboarding_completed"
    const val ENCRYPTION_ENABLED = "encryption_enabled"
    const val THEME_MODE = "theme_mode"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val AUTO_UPLOAD = "auto_upload"
    const val LAST_BACKUP_MESSAGE_ID = "last_backup_message_id"
    const val LAST_BACKUP_TIMESTAMP = "last_backup_timestamp"
    const val LAST_BACKUP_SIZE = "last_backup_size"
    const val AUTO_SYNC_ENABLED = "auto_sync_enabled"
    const val BACKUP_FILE_ID = "backup_file_id"
    const val AUTO_BACKUP_FREQUENCY = "auto_backup_frequency" // off, daily, weekly, monthly
}

/** Backup frequency options. */
object BackupFrequency {
    const val OFF = "off"
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"
}
