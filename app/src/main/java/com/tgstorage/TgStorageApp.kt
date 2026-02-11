package com.tgstorage

import android.app.Application
import androidx.room.Room
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

        database = Room.databaseBuilder(
            applicationContext,
            TgStorageDatabase::class.java,
            TgStorageDatabase.DATABASE_NAME,
        )
            .addMigrations(TgStorageDatabase.MIGRATION_1_2, TgStorageDatabase.MIGRATION_2_3)
            .build()

        // Create notification channels
        SyncWorker.createNotificationChannel(this)
        BackupWorker.createNotificationChannel(this)

        // Schedule periodic background workers
        SyncWorker.schedule(this)
        CleanupWorker.schedule(this)

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
