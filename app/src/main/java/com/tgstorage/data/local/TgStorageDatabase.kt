package com.tgstorage.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 4,
    exportSchema = true,
)
abstract class TgStorageDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun chunkDao(): ChunkDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun metadataDao(): MetadataDao

    companion object {
        const val DATABASE_NAME = "tgstorage.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chunks ADD COLUMN telegram_file_id TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_state ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN error_message TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE files ADD COLUMN thumbnail_uri TEXT DEFAULT NULL")
            }
        }
    }
}
