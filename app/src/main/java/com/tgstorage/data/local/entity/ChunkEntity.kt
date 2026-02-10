package com.tgstorage.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["file_id"])],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "file_id")
    val fileId: Long,

    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int,

    @ColumnInfo(name = "telegram_message_id")
    val telegramMessageId: Long? = null,

    @ColumnInfo(name = "checksum")
    val checksum: String = "",

    @ColumnInfo(name = "size")
    val size: Long = 0,
)
