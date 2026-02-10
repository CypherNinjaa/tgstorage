package com.tgstorage.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tgstorage.data.local.dao.ChunkDao
import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.dao.SyncStateDao
import com.tgstorage.data.local.entity.ChunkEntity
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.SyncStateEntity

@Database(
    entities = [
        FileEntity::class,
        ChunkEntity::class,
        SyncStateEntity::class,
        MetadataEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TgStorageDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun chunkDao(): ChunkDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun metadataDao(): MetadataDao

    companion object {
        const val DATABASE_NAME = "tgstorage.db"
    }
}
