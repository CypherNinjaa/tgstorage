package com.tgstorage

import android.app.Application
import androidx.room.Room
import com.tgstorage.common.CrashHandler
import com.tgstorage.data.local.TgStorageDatabase
import com.tgstorage.data.local.entity.BackupFrequency
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.sync.BackupWorker
import com.tgstorage.data.sync.CleanupWorker
import com.tgstorage.data.sync.SyncWorker
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
            )
            .build()

        // Create notification channels
        SyncWorker.createNotificationChannel(this)
        BackupWorker.createNotificationChannel(this)

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
    }

    companion object {
        lateinit var instance: TgStorageApp
            private set
    }
}
