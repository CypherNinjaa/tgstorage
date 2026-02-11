package com.tgstorage.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tgstorage.data.local.dao.BotDao
import com.tgstorage.data.local.dao.ChunkDao
import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.dao.SyncStateDao
import com.tgstorage.data.local.entity.BotEntity
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
        BotEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class TgStorageDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun chunkDao(): ChunkDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun metadataDao(): MetadataDao
    abstract fun botDao(): BotDao

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create bots table for multi-bot support
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        token_encrypted TEXT NOT NULL,
                        chat_id TEXT NOT NULL,
                        is_active INTEGER NOT NULL DEFAULT 1,
                        is_verified INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        verified_at INTEGER DEFAULT NULL,
                        is_primary INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Add bot_id column to chunks table
                db.execSQL("ALTER TABLE chunks ADD COLUMN bot_id INTEGER DEFAULT NULL")
                
                // Create index on bot_id for faster lookups
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chunks_bot_id ON chunks(bot_id)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add file_unique_id column for recovery/future-proofing.
                // file_unique_id is permanent (unlike file_id which can go stale).
                db.execSQL("ALTER TABLE chunks ADD COLUMN telegram_file_unique_id TEXT DEFAULT NULL")
            }
        }
    }
}
