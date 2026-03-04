package com.tgstorage.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "files",
    indices = [Index(value = ["folder_id"])],
)
data class FileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "size")
    val size: Long,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "sha256")
    val sha256: String = "",

    @ColumnInfo(name = "encryption_flag")
    val encryptionFlag: Boolean = false,

    @ColumnInfo(name = "local_uri")
    val localUri: String? = null,

    @ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String? = null,

    /** Folder this file belongs to. null = root (unfiled). */
    @ColumnInfo(name = "folder_id")
    val folderId: Long? = null,

    /** Soft-delete: non-null means in trash (epoch ms when trashed). */
    @ColumnInfo(name = "trashed_at")
    val trashedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
