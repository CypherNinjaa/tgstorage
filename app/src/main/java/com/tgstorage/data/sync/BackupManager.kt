package com.tgstorage.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.tgstorage.TgStorageApp
import com.tgstorage.common.security.CryptoManager
import com.tgstorage.data.local.TgStorageDatabase
import com.tgstorage.data.local.entity.MetadataEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.repository.SyncRepository
import com.tgstorage.data.repository.TelegramRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles creating encrypted backups of the Room DB,
 * uploading them to Telegram, and restoring from Telegram.
 *
 * Backup encryption uses a key derived from the bot token via PBKDF2,
 * enabling cross-device restore (Android Keystore keys are lost on uninstall).
 * The latest backup is pinned in the channel so it can be discovered via getChat.
 */
class BackupManager(
    private val context: Context,
) {

    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_FILE_NAME = "tgstorage_backup.enc"
        private const val BACKUP_CAPTION = "\uD83D\uDCE6 TgStorage Backup"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PBKDF2_ITERATIONS = 100_000
        private const val BACKUP_SALT = "tgstorage_backup_v1"
    }

    // ─── Token-derived encryption for cross-device backup ─────

    /**
     * Derives an AES-256 key from the bot token using PBKDF2.
     * This allows decryption on any device where the same token is entered.
     */
    private fun deriveKeyFromToken(token: String): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val salt = BACKUP_SALT.toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(token.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }

    private fun encryptForBackup(plaintext: ByteArray, token: String): ByteArray {
        val key = deriveKeyFromToken(token)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    private fun decryptForBackup(data: ByteArray, token: String): ByteArray {
        require(data.size > GCM_IV_LENGTH) { "Data too short to contain IV + ciphertext" }
        val key = deriveKeyFromToken(token)
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    // ─── Backup creation ──────────────────────────────────────

    /**
     * Create an encrypted backup of the Room database and upload to Telegram.
     * Encrypts using a token-derived key (PBKDF2) so it can be restored on any device.
     * Pins the backup message in the channel for discovery via getChat.
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

            // 1. Copy the database files (Room keeps running)
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
                // Copy WAL and SHM if they exist
                val tempWal = File(context.cacheDir, "backup_temp.db-wal")
                val tempShm = File(context.cacheDir, "backup_temp.db-shm")
                if (walFile.exists()) walFile.copyTo(tempWal, overwrite = true)
                if (shmFile.exists()) shmFile.copyTo(tempShm, overwrite = true)

                // 2. Open the COPY with raw SQLite to force WAL checkpoint
                //    This merges all WAL data into the main db file
                val rawDb = SQLiteDatabase.openDatabase(
                    tempDbCopy.path, null, SQLiteDatabase.OPEN_READWRITE
                )
                // Use rawQuery for PRAGMA (execSQL doesn't support PRAGMA)
                rawDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                rawDb.close()
                // WAL is now fully merged — delete the temp WAL/SHM
                tempWal.delete()
                tempShm.delete()

                // 3. Encrypt the complete DB copy with token-derived key
                val plainBytes = tempDbCopy.readBytes()
                val encryptedBytes = encryptForBackup(plainBytes, token)

                val encryptedFile = File(context.cacheDir, BACKUP_FILE_NAME)
                encryptedFile.writeBytes(encryptedBytes)

                Log.d(TAG, "Backup created: ${encryptedFile.length()} bytes (encrypted)")

                // 4. Upload to Telegram with caption for identification
                val result = api.sendDocument(
                    token = token,
                    chatId = chatId,
                    file = encryptedFile,
                    fileName = BACKUP_FILE_NAME,
                    caption = BACKUP_CAPTION,
                )

                val message = result.getOrThrow()
                val messageId = message.messageId

                // 5. Pin the backup message for discovery (best-effort)
                try {
                    api.pinChatMessage(
                        token = token,
                        chatId = chatId,
                        messageId = messageId,
                        disableNotification = true,
                    )
                    Log.d(TAG, "Backup message pinned (message_id=$messageId)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to pin backup message: ${e.message}")
                }

                // 6. Save backup metadata (including file_id for restore)
                syncRepo.saveBackupInfo(
                    messageId = messageId,
                    sizeBytes = encryptedFile.length(),
                )
                val documentFileId = message.document?.fileId
                if (documentFileId != null) {
                    db.metadataDao().setValue(
                        MetadataEntity(
                            key = MetadataKeys.BACKUP_FILE_ID,
                            value = documentFileId,
                        )
                    )
                }

                Log.d(TAG, "Backup uploaded successfully (message_id=$messageId)")

                // 7. Clean up temp files
                secureDelete(tempDbCopy)
                secureDelete(encryptedFile)

                messageId
            } finally {
                secureDelete(tempDbCopy)
                secureDelete(File(context.cacheDir, "backup_temp.db-wal"))
                secureDelete(File(context.cacheDir, "backup_temp.db-shm"))
            }
        }
    }

    // ─── Backup discovery ─────────────────────────────────────

    /**
     * Discover the latest backup in the Telegram channel via the pinned message.
     * Returns [BackupInfo] if a valid backup document is pinned, null otherwise.
     */
    suspend fun findBackupInChannel(
        token: String,
        chatId: String,
    ): BackupInfo? = withContext(Dispatchers.IO) {
        try {
            val api = TelegramApiService()
            val chatInfo = api.getChat(token, chatId).getOrNull() ?: return@withContext null
            val pinnedMessage = chatInfo.pinnedMessage ?: return@withContext null
            val document = pinnedMessage.document ?: return@withContext null

            // Verify it's a TgStorage backup by filename or caption
            val isBackup = document.fileName == BACKUP_FILE_NAME ||
                    pinnedMessage.caption?.contains("TgStorage Backup") == true

            if (!isBackup) return@withContext null

            BackupInfo(
                fileId = document.fileId,
                fileName = document.fileName,
                fileSize = document.fileSize,
                messageId = pinnedMessage.messageId,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to search for backup: ${e.message}")
            null
        }
    }

    // ─── Cross-device restore ─────────────────────────────────

    /**
     * Download and restore a backup from Telegram using the file_id.
     * Decrypts using the provided bot token (PBKDF2-derived key).
     * After restore, updates the DB with the current token and chatId
     * so the user doesn't need to re-enter them.
     *
     * WARNING: Replaces the entire Room database. App must restart after this.
     *
     * @param token The bot token entered during onboarding (plaintext)
     * @param chatId The verified channel ID
     * @param fileId The Telegram file_id of the backup document
     */
    suspend fun restoreFromTelegram(
        token: String,
        chatId: String,
        fileId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val api = TelegramApiService()

            // 1. Download the backup file
            val telegramFile = api.getFile(token, fileId).getOrThrow()
            val filePath = telegramFile.filePath
                ?: throw IllegalStateException("No file path returned from Telegram")

            val encryptedBytes = api.downloadFile(token, filePath).getOrThrow()
            Log.d(TAG, "Downloaded backup: ${encryptedBytes.size} bytes")

            // 2. Decrypt with token-derived key
            val decryptedBytes = decryptForBackup(encryptedBytes, token)

            // 3. Close the current database
            val db = TgStorageApp.instance.database
            db.close()

            // 4. Replace the database file
            val dbFile = context.getDatabasePath(TgStorageDatabase.DATABASE_NAME)
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            dbFile.writeBytes(decryptedBytes)

            // 5. Update the restored DB with current onboarding credentials
            //    using raw SQLite (Room instance is closed)
            val encryptedToken = CryptoManager.encryptString(token)
            val sqliteDb = SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READWRITE
            )
            try {
                sqliteDb.execSQL(
                    "INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)",
                    arrayOf(MetadataKeys.BOT_TOKEN, encryptedToken),
                )
                sqliteDb.execSQL(
                    "INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)",
                    arrayOf(MetadataKeys.CHAT_ID, chatId),
                )
                sqliteDb.execSQL(
                    "INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)",
                    arrayOf(MetadataKeys.ONBOARDING_COMPLETED, "true"),
                )
            } finally {
                sqliteDb.close()
            }

            Log.d(TAG, "Database restored from backup with updated credentials")

            Unit
        }
    }

    // ─── Same-device restore (existing) ───────────────────────

    /**
     * Download and restore a backup from Telegram using locally stored metadata.
     * Only works on the same device (reads file_id from local metadata).
     * WARNING: Replaces the entire Room database. App must restart after this.
     */
    suspend fun downloadAndRestoreBackup(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val db = TgStorageApp.instance.database
            val api = TelegramApiService()
            val telegramRepo = TelegramRepository(api, db.metadataDao())
            val syncRepo = SyncRepository(db.syncStateDao(), db.fileDao(), db.metadataDao())

            val token = telegramRepo.getToken()
                ?: throw IllegalStateException("Bot token not configured")
            val chatId = telegramRepo.getChatId()
                ?: throw IllegalStateException("Channel not configured")

            // Try local metadata first, then channel discovery
            val backupFileId = db.metadataDao().getValue(MetadataKeys.BACKUP_FILE_ID)
                ?: findBackupInChannel(token, chatId)?.fileId
                ?: throw IllegalStateException("No backup found")

            // Delegate to cross-device restore (same logic)
            restoreFromTelegram(token, chatId, backupFileId).getOrThrow()
        }
    }

    // ─── Utils ────────────────────────────────────────────────

    private fun secureDelete(file: File) {
        if (!file.exists()) return
        runCatching {
            FileOutputStream(file).use { out ->
                val buffer = ByteArray(8192)
                var remaining = file.length()
                while (remaining > 0) {
                    val toWrite = minOf(buffer.size.toLong(), remaining).toInt()
                    out.write(buffer, 0, toWrite)
                    remaining -= toWrite
                }
                out.flush()
            }
        }
        file.delete()
    }
}

/**
 * Information about a backup found in the Telegram channel.
 */
data class BackupInfo(
    val fileId: String,
    val fileName: String?,
    val fileSize: Long?,
    val messageId: Long,
)
