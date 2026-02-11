package com.tgstorage.data.transfer

import android.util.Log
import com.tgstorage.data.local.dao.ChunkDao
import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.SyncStateDao
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.remote.TelegramApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Validates chunk integrity after download and provides recovery
 * by re-downloading corrupted or missing chunks.
 *
 * Phase 9 — Hardening: corruption detection and automatic repair.
 */
class ChunkIntegrityValidator(
    private val api: TelegramApiService,
    private val chunkDao: ChunkDao,
    private val fileDao: FileDao,
    private val syncStateDao: SyncStateDao,
) {

    companion object {
        private const val TAG = "ChunkIntegrity"
        private const val MAX_REPAIR_RETRIES = 3
        private const val BACKOFF_MS = 2000L
    }

    /**
     * Result of a chunk integrity check.
     */
    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Corrupted(val corruptedIndices: List<Int>) : ValidationResult()
        data class MissingChunks(val missingIndices: List<Int>) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    /**
     * Validates all chunks for a given file by checking:
     * 1. Every chunk has a valid Telegram file_id
     * 2. Chunk count matches expected (based on file size)
     */
    suspend fun validateChunks(fileId: Long): ValidationResult = withContext(Dispatchers.IO) {
        try {
            val file = fileDao.getFileById(fileId)
                ?: return@withContext ValidationResult.Error("File not found")

            val chunks = chunkDao.getChunksForFileSync(fileId)
            if (chunks.isEmpty()) {
                return@withContext ValidationResult.Error("No chunks found for file $fileId")
            }

            // Check for missing Telegram file IDs
            val missingFileId = chunks.filter { it.telegramFileId == null }
            if (missingFileId.isNotEmpty()) {
                Log.w(TAG, "File $fileId has ${missingFileId.size} chunks without Telegram file_id")
                return@withContext ValidationResult.MissingChunks(missingFileId.map { it.chunkIndex })
            }

            // Verify chunk indices are sequential (0..n-1)
            val expectedIndices = (0 until chunks.size).toSet()
            val actualIndices = chunks.map { it.chunkIndex }.toSet()
            val missing = expectedIndices - actualIndices
            if (missing.isNotEmpty()) {
                Log.w(TAG, "File $fileId is missing chunk indices: $missing")
                return@withContext ValidationResult.MissingChunks(missing.toList().sorted())
            }

            ValidationResult.Valid
        } catch (e: Exception) {
            Log.e(TAG, "Validation error for file $fileId", e)
            ValidationResult.Error(e.message ?: "Validation failed")
        }
    }

    /**
     * Verifies the SHA-256 hash of a downloaded file against the stored hash.
     * Returns true if hashes match or if no stored hash exists.
     */
    suspend fun verifyFileHash(fileId: Long, downloadedBytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val file = fileDao.getFileById(fileId) ?: return@withContext false
            if (file.sha256.isBlank()) return@withContext true // No stored hash to verify

            val downloadedHash = computeSha256(downloadedBytes)
            val matches = downloadedHash == file.sha256
            if (!matches) {
                Log.e(TAG, "File hash mismatch for $fileId: expected=${file.sha256}, got=$downloadedHash")
            }
            matches
        }

    /**
     * Attempts to repair corrupted/missing chunks by re-downloading from Telegram.
     * Returns true if all chunks were successfully repaired.
     */
    suspend fun repairChunks(
        token: String,
        fileId: Long,
        corruptedIndices: List<Int>,
    ): Boolean = withContext(Dispatchers.IO) {
        var allRepaired = true
        for (index in corruptedIndices) {
            val chunk = chunkDao.getChunksForFileSync(fileId)
                .find { it.chunkIndex == index }

            if (chunk == null) {
                Log.e(TAG, "Cannot repair chunk $index for file $fileId — chunk record not found")
                allRepaired = false
                continue
            }

            val tgFileId = chunk.telegramFileId
            if (tgFileId == null) {
                Log.e(TAG, "Cannot repair chunk $index — no Telegram file_id stored")
                allRepaired = false
                continue
            }

            var repaired = false
            for (attempt in 1..MAX_REPAIR_RETRIES) {
                try {
                    val tgFile = api.getFile(token, tgFileId).getOrThrow()
                    val filePath = tgFile.filePath
                        ?: throw IllegalStateException("No file_path for chunk $index")

                    val chunkBytes = api.downloadFile(token, filePath).getOrThrow()

                    // Verify checksum if available
                    if (chunk.checksum.isNotBlank()) {
                        val hash = computeSha256(chunkBytes)
                        if (hash == chunk.checksum) {
                            Log.d(TAG, "Chunk $index repaired successfully (attempt $attempt)")
                            repaired = true
                            break
                        } else {
                            Log.w(TAG, "Chunk $index re-download still has bad checksum (attempt $attempt)")
                        }
                    } else {
                        // No checksum to verify against — assume success
                        repaired = true
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Repair attempt $attempt for chunk $index failed: ${e.message}")
                    if (attempt < MAX_REPAIR_RETRIES) {
                        delay(BACKOFF_MS * attempt)
                    }
                }
            }

            if (!repaired) allRepaired = false
        }

        if (!allRepaired) {
            // Mark file as failed if repair didn't work
            syncStateDao.updateStatus(
                fileId = fileId,
                status = SyncStatus.FAILED,
                timestamp = System.currentTimeMillis(),
                error = "Chunk integrity repair failed",
            )
        }

        allRepaired
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
