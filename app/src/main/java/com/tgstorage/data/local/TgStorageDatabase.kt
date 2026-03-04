package com.tgstorage.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tgstorage.data.local.dao.BotDao
import com.tgstorage.data.local.dao.ChunkDao
import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.FolderDao
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.dao.SyncStateDao
import com.tgstorage.data.local.entity.BotEntity
import com.tgstorage.data.local.entity.ChunkEntity
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.local.entity.FolderEntity
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.SyncStateEntity

@Database(
    entities = [
        FileEntity::class,
        ChunkEntity::class,
        SyncStateEntity::class,
        MetadataEntity::class,
        BotEntity::class,
        FolderEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class TgStorageDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun chunkDao(): ChunkDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun metadataDao(): MetadataDao
    abstract fun botDao(): BotDao
    abstract fun folderDao(): FolderDao

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
                db.execSQL("ALTER TABLE chunks ADD COLUMN bot_id INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chunks_bot_id ON chunks(bot_id)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chunks ADD COLUMN telegram_file_unique_id TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create folders table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        parent_id INTEGER DEFAULT NULL,
                        color INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_parent_id ON folders(parent_id)")

                // Add folder_id and trashed_at columns to files
                db.execSQL("ALTER TABLE files ADD COLUMN folder_id INTEGER")
                db.execSQL("ALTER TABLE files ADD COLUMN trashed_at INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_files_folder_id ON files(folder_id)")
            }
        }

        /**
         * Corrective migration: recreate files table to fix DEFAULT NULL schema
         * from earlier migration 6→7 (ALTER TABLE ADD COLUMN ... DEFAULT NULL).
         * Room expects defaultValue='undefined', not 'NULL'.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Disable FK checks while we recreate the files table
                db.execSQL("PRAGMA foreign_keys = OFF")

                // Recreate files table without DEFAULT NULL on folder_id and trashed_at
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS files_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        mime_type TEXT NOT NULL,
                        sha256 TEXT NOT NULL,
                        encryption_flag INTEGER NOT NULL,
                        local_uri TEXT,
                        thumbnail_uri TEXT,
                        folder_id INTEGER,
                        trashed_at INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())

                // Copy existing data
                db.execSQL("""
                    INSERT INTO files_new (id, name, size, mime_type, sha256, encryption_flag,
                        local_uri, thumbnail_uri, folder_id, trashed_at, created_at, updated_at)
                    SELECT id, name, size, mime_type, sha256, encryption_flag,
                        local_uri, thumbnail_uri, folder_id, trashed_at, created_at, updated_at
                    FROM files
                """.trimIndent())

                // Drop old table and rename new
                db.execSQL("DROP TABLE files")
                db.execSQL("ALTER TABLE files_new RENAME TO files")

                // Recreate index
                db.execSQL("CREATE INDEX IF NOT EXISTS index_files_folder_id ON files(folder_id)")

                // Re-enable FK checks
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }
}
