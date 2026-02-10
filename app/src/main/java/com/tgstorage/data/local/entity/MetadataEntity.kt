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
}
