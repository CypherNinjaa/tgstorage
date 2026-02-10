package com.tgstorage.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_state",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["file_id"], unique = true)],
)
data class SyncStateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "file_id")
    val fileId: Long,

    @ColumnInfo(name = "status")
    val status: String = SyncStatus.PENDING_UPLOAD,

    @ColumnInfo(name = "last_attempt")
    val lastAttempt: Long? = null,
)

object SyncStatus {
    const val PENDING_UPLOAD = "pending_upload"
    const val UPLOADED = "uploaded"
    const val FAILED = "failed"
    const val DELETED = "deleted"
}
