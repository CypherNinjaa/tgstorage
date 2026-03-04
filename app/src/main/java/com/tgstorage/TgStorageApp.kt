package com.tgstorage

import android.app.Application
import androidx.room.Room
import com.tgstorage.common.CrashHandler
import com.tgstorage.data.local.TgStorageDatabase
import com.tgstorage.data.local.entity.BackupFrequency
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.repository.BotRepository
import com.tgstorage.data.sync.AutoBackupWorker
import com.tgstorage.data.sync.AutoRetryWorker
import com.tgstorage.data.sync.BackupWorker
import com.tgstorage.data.sync.CleanupWorker
import com.tgstorage.data.sync.NewMediaWorker
import com.tgstorage.data.sync.SyncWorker
import com.tgstorage.data.sync.TrashCleanupWorker
import com.tgstorage.data.sync.UploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TgStorageApp : Application() {

    lateinit var database: TgStorageDatabase
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Phase 9: Install global crash handler for crash logging & temp cleanup
        CrashHandler.install(this)

        database = Room.databaseBuilder(
            applicationContext,
            TgStorageDatabase::class.java,
            TgStorageDatabase.DATABASE_NAME,
        )
            .addMigrations(
                TgStorageDatabase.MIGRATION_1_2,
                TgStorageDatabase.MIGRATION_2_3,
                TgStorageDatabase.MIGRATION_3_4,
                TgStorageDatabase.MIGRATION_4_5,
                TgStorageDatabase.MIGRATION_5_6,
                TgStorageDatabase.MIGRATION_6_7,
                TgStorageDatabase.MIGRATION_7_8,
            )
            .build()

        // Create notification channels
        SyncWorker.createNotificationChannel(this)
        BackupWorker.createNotificationChannel(this)
        UploadService.createNotificationChannel(this)
        NewMediaWorker.createNotificationChannel(this)

        // Migrate legacy single-bot to multi-bot table
        appScope.launch {
            val botRepo = BotRepository(
                botDao = database.botDao(),
                metadataDao = database.metadataDao(),
                api = TelegramApiService(),
            )
            botRepo.migrateLegacyBot()
        }

        // Ensure encryption is enabled by default in DB
        appScope.launch {
            val dao = database.metadataDao()
            if (dao.getValue(MetadataKeys.ENCRYPTION_ENABLED) == null) {
                dao.setValue(
                    com.tgstorage.data.local.entity.MetadataEntity(
                        MetadataKeys.ENCRYPTION_ENABLED, "true"
                    )
                )
            }
        }

        // Schedule periodic background workers
        CleanupWorker.schedule(this)
        appScope.launch {
            val autoSync = database.metadataDao()
                .getValue(MetadataKeys.AUTO_SYNC_ENABLED)?.toBoolean() ?: true
            val wifiOnly = database.metadataDao()
                .getValue(MetadataKeys.SYNC_WIFI_ONLY)?.toBoolean() ?: false
            if (autoSync) SyncWorker.schedule(this@TgStorageApp, wifiOnly)
            else SyncWorker.cancel(this@TgStorageApp)
        }

        // Schedule auto-backup based on saved frequency
        appScope.launch {
            val freq = database.metadataDao()
                .getValue(MetadataKeys.AUTO_BACKUP_FREQUENCY)
                ?: BackupFrequency.OFF
            if (freq != BackupFrequency.OFF) {
                BackupWorker.schedule(this@TgStorageApp, freq)
            }
        }

        // Schedule auto-retry worker to automatically retry failed uploads
        AutoRetryWorker.schedule(this)

        // Schedule new-media detection worker (checks for new photos/videos/docs every 15 min)
        NewMediaWorker.schedule(this)

        // Schedule daily trash cleanup (purge files trashed > 30 days ago)
        TrashCleanupWorker.schedule(this)

        // Restore auto-upload worker if it was enabled
        appScope.launch {
            val autoUploadEnabled = database.metadataDao()
                .getValue(MetadataKeys.AUTO_UPLOAD)?.toBoolean() ?: false
            if (autoUploadEnabled) {
                AutoBackupWorker.schedule(this@TgStorageApp)
            }
        }
    }

    companion object {
        lateinit var instance: TgStorageApp
            private set
    }
}
