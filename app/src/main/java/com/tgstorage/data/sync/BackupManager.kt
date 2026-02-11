package com.tgstorage.data.sync

import android.content.Context
import android.util.Log
import com.tgstorage.TgStorageApp
import com.tgstorage.common.security.CryptoManager
import com.tgstorage.data.local.TgStorageDatabase
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.repository.SyncRepository
import com.tgstorage.data.repository.TelegramRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Handles creating encrypted backups of the Room DB,
 * uploading them to Telegram, and restoring from Telegram.
 */
class BackupManager(
    private val context: Context,
) {

    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_FILE_NAME = "tgstorage_backup.enc"
    }

    /**
     * Create an encrypted backup of the Room database and upload to Telegram.
     * Returns the message_id of the backup document on success.
     */
    suspend fun createAndUploadBackup(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val db = TgStorageApp.instance.database
            val api = TelegramApiService()
            val telegramRepo = TelegramRepository(api, db.metadataDao())
            val syncRepo = SyncRepository(db.syncStateDao(), db.fileDao(), db.metadataDao())

            val token = telegramRepo.getToken()
                ?: throw IllegalStateException("Bot token not configured")
            val chatId = telegramRepo.getChatId()
                ?: throw IllegalStateException("Channel not configured")

            // 1. Force WAL checkpoint via Room's query interface
            db.query("PRAGMA wal_checkpoint(FULL)", null)

            // 2. Copy the database file
            val dbFile = context.getDatabasePath(TgStorageDatabase.DATABASE_NAME)
            val tempDbCopy = File(context.cacheDir, "backup_temp.db")
            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")

            try {
                // Copy main DB file
                dbFile.inputStream().use { input ->
                    tempDbCopy.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Copy WAL if it exists (ensures consistency)
                val tempWal = File(context.cacheDir, "backup_temp.db-wal")
                val tempShm = File(context.cacheDir, "backup_temp.db-shm")
                if (walFile.exists()) walFile.copyTo(tempWal, overwrite = true)
                if (shmFile.exists()) shmFile.copyTo(tempShm, overwrite = true)

                // 3. Encrypt the DB copy
                val plainBytes = tempDbCopy.readBytes()
                val encryptedBytes = CryptoManager.encrypt(plainBytes)

                val encryptedFile = File(context.cacheDir, BACKUP_FILE_NAME)
                encryptedFile.writeBytes(encryptedBytes)

                Log.d(TAG, "Backup created: ${encryptedFile.length()} bytes (encrypted)")

                // 4. Upload to Telegram
                val result = api.sendDocument(
                    token = token,
                    chatId = chatId,
                    file = encryptedFile,
                    fileName = BACKUP_FILE_NAME,
                )

                val message = result.getOrThrow()
                val messageId = message.messageId

                // 5. Save backup metadata (including file_id for restore)
                syncRepo.saveBackupInfo(
                    messageId = messageId,
                    sizeBytes = encryptedFile.length(),
                )
                // Store the document file_id for later retrieval during restore
                val documentFileId = message.document?.fileId
                if (documentFileId != null) {
                    db.metadataDao().setValue(
                        com.tgstorage.data.local.entity.MetadataEntity(
                            key = "backup_file_id",
                            value = documentFileId,
                        )
                    )
                }

                Log.d(TAG, "Backup uploaded successfully (message_id=$messageId)")

                // 6. Clean up temp files
                tempDbCopy.delete()
                tempWal.delete()
                tempShm.delete()
                encryptedFile.delete()

                messageId
            } finally {
                tempDbCopy.delete()
                File(context.cacheDir, "backup_temp.db-wal").delete()
                File(context.cacheDir, "backup_temp.db-shm").delete()
            }
        }
    }

    /**
     * Download and restore a backup from Telegram.
     * WARNING: This replaces the entire Room database and requires app restart.
     */
    suspend fun downloadAndRestoreBackup(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val db = TgStorageApp.instance.database
            val api = TelegramApiService()
            val telegramRepo = TelegramRepository(api, db.metadataDao())
            val syncRepo = SyncRepository(db.syncStateDao(), db.fileDao(), db.metadataDao())

            val token = telegramRepo.getToken()
                ?: throw IllegalStateException("Bot token not configured")
            val backupMessageId = syncRepo.getLastBackupMessageId()
                ?: throw IllegalStateException("No backup found")
            val chatId = telegramRepo.getChatId()
                ?: throw IllegalStateException("Channel not configured")

            // 1. Get the backup document's file_id by forwarding/getting message
            //    We need to use getFile from the document info stored at upload time.
            //    Since sendDocument returns the document.file_id, we should store it.
            //    For now, we use a workaround: re-fetch via forwardMessage or stored file_id.
            //    We'll store the document file_id in metadata during backup.
            val backupFileId = getBackupFileId(token, chatId, backupMessageId)
                ?: throw IllegalStateException("Could not retrieve backup file info")

            // 2. Download the file
            val telegramFile = api.getFile(token, backupFileId).getOrThrow()
            val filePath = telegramFile.filePath
                ?: throw IllegalStateException("No file path returned from Telegram")

            val encryptedBytes = api.downloadFile(token, filePath).getOrThrow()
            Log.d(TAG, "Downloaded backup: ${encryptedBytes.size} bytes")

            // 3. Decrypt
            val decryptedBytes = CryptoManager.decrypt(encryptedBytes)

            // 4. Close the database before replacing
            db.close()

            // 5. Write the decrypted DB file
            val dbFile = context.getDatabasePath(TgStorageDatabase.DATABASE_NAME)
            // Also delete the WAL and SHM files
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            dbFile.writeBytes(decryptedBytes)
            Log.d(TAG, "Database restored from backup")

            // Note: App needs to restart after this to reopen the database
            Unit
        }
    }

    /**
     * Attempt to get the file_id for the backup document from a Telegram message.
     * Uses copyMessage trick to retrieve the document info.
     */
    private suspend fun getBackupFileId(
        token: String,
        chatId: String,
        messageId: Long,
    ): String? {
        // Use the Telegram API to forward the message to ourselves to get the document
        // Actually, we can use the copyMessage endpoint to get document info.
        // Simpler approach: store file_id during backup and retrieve from metadata.
        val db = TgStorageApp.instance.database
        val stored = db.metadataDao().getValue("backup_file_id")
        return stored
    }
}
