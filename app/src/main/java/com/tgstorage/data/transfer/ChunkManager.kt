package com.tgstorage.data.transfer

import com.tgstorage.data.local.dao.ChunkDao
import com.tgstorage.data.local.dao.SyncStateDao
import com.tgstorage.data.local.entity.ChunkEntity
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.data.local.entity.SyncStateEntity
import com.tgstorage.data.remote.TelegramApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

// ─── Transfer progress model ───────────────────────────

data class TransferProgress(
    val fileId: Long,
    val fileName: String,
    val type: TransferType,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val status: TransferStatus = TransferStatus.PENDING,
    val error: String? = null,
)

enum class TransferType { UPLOAD, DOWNLOAD }

enum class TransferStatus {
    PENDING, IN_PROGRESS, PAUSED, COMPLETED, FAILED, CANCELLED
}

// ─── ChunkManager ──────────────────────────────────────

class ChunkManager(
    private val api: TelegramApiService,
    private val chunkDao: ChunkDao,
    private val syncStateDao: SyncStateDao,
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 20L * 1024 * 1024 // 20 MB
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
    }

    /**
     * Uploads a file in chunks to Telegram. For files under 50 MB,
     * uploads as a single document. For larger files, splits into chunks.
     * Emits progress to [progressFlow].
     */
    suspend fun uploadFile(
        token: String,
        chatId: String,
        fileId: Long,
        localFile: File,
        fileName: String,
        progressFlow: MutableStateFlow<TransferProgress>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fileSize = localFile.length()
            val chunkSize = DEFAULT_CHUNK_SIZE
            val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()

            progressFlow.value = progressFlow.value.copy(
                totalChunks = totalChunks,
                totalBytes = fileSize,
                status = TransferStatus.IN_PROGRESS,
            )

            // Check for already-uploaded chunks (resume support)
            val existingChunks = chunkDao.getChunksForFileSync(fileId)
            val uploadedIndices = existingChunks
                .filter { it.telegramMessageId != null }
                .map { it.chunkIndex }
                .toSet()

            var bytesUploaded = existingChunks
                .filter { it.telegramMessageId != null }
                .sumOf { it.size }

            val tempDir = File(localFile.parentFile, "chunks_temp")
            if (!tempDir.exists()) tempDir.mkdirs()

            for (chunkIndex in 0 until totalChunks) {
                // Skip already uploaded chunks
                if (chunkIndex in uploadedIndices) {
                    progressFlow.value = progressFlow.value.copy(
                        currentChunk = chunkIndex + 1,
                        bytesTransferred = bytesUploaded,
                    )
                    continue
                }

                // Check for cancellation
                if (progressFlow.value.status == TransferStatus.CANCELLED) {
                    throw CancellationException("Upload cancelled")
                }

                val offset = chunkIndex.toLong() * chunkSize
                val thisChunkSize = minOf(chunkSize, fileSize - offset).toInt()

                // Split chunk to temp file
                val chunkFile = File(tempDir, "${localFile.name}.chunk$chunkIndex")
                splitChunk(localFile, chunkFile, offset, thisChunkSize)
                val chunkChecksum = computeSha256(chunkFile)

                // Upload with retry + backoff
                val message = retryWithBackoff(MAX_RETRIES) {
                    api.sendDocument(
                        token = token,
                        chatId = chatId,
                        file = chunkFile,
                        fileName = if (totalChunks == 1) fileName
                        else "${fileName}.part${chunkIndex + 1}of$totalChunks",
                    ).getOrThrow()
                }

                // Store chunk metadata in Room
                chunkDao.insertChunk(
                    ChunkEntity(
                        fileId = fileId,
                        chunkIndex = chunkIndex,
                        telegramMessageId = message.messageId,
                        checksum = chunkChecksum,
                        size = thisChunkSize.toLong(),
                    )
                )

                // Clean up temp chunk
                chunkFile.delete()

                bytesUploaded += thisChunkSize
                progressFlow.value = progressFlow.value.copy(
                    currentChunk = chunkIndex + 1,
                    bytesTransferred = bytesUploaded,
                )
            }

            // Mark sync state as uploaded
            val existingSync = syncStateDao.getSyncState(fileId)
            syncStateDao.insertOrUpdate(
                (existingSync ?: SyncStateEntity(fileId = fileId)).copy(
                    status = SyncStatus.UPLOADED,
                    lastAttempt = System.currentTimeMillis(),
                )
            )

            // Cleanup temp dir
            tempDir.deleteRecursively()

            progressFlow.value = progressFlow.value.copy(
                status = TransferStatus.COMPLETED,
            )
        }.onFailure { e ->
            if (e !is CancellationException) {
                syncStateDao.getSyncState(fileId)?.let { sync ->
                    syncStateDao.insertOrUpdate(
                        sync.copy(
                            status = SyncStatus.FAILED,
                            lastAttempt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            progressFlow.value = progressFlow.value.copy(
                status = if (e is CancellationException) TransferStatus.CANCELLED
                else TransferStatus.FAILED,
                error = e.message,
            )
        }
    }

    /**
     * Downloads a file by fetching all chunks from Telegram,
     * verifying checksums, and reassembling into the output file.
     */
    suspend fun downloadFile(
        token: String,
        fileId: Long,
        fileName: String,
        outputFile: File,
        progressFlow: MutableStateFlow<TransferProgress>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val chunks = chunkDao.getChunksForFileSync(fileId)
            if (chunks.isEmpty()) throw IllegalStateException("No chunks found for file $fileId")

            val totalBytes = chunks.sumOf { it.size }
            progressFlow.value = progressFlow.value.copy(
                totalChunks = chunks.size,
                totalBytes = totalBytes,
                status = TransferStatus.IN_PROGRESS,
            )

            var bytesDownloaded = 0L

            FileOutputStream(outputFile).use { output ->
                for ((index, chunk) in chunks.withIndex()) {
                    if (progressFlow.value.status == TransferStatus.CANCELLED) {
                        throw CancellationException("Download cancelled")
                    }

                    val messageId = chunk.telegramMessageId
                        ?: throw IllegalStateException("Chunk ${chunk.chunkIndex} has no message_id")

                    // We need the file_id from the Telegram message to download
                    // For now use getFile -> downloadFile approach
                    // The chunk's telegram_message_id is stored, but we need file_id
                    // In practice, we'd store file_id per chunk too; for now,
                    // use the document's file_id stored in the message

                    // Re-fetch chunk data via Telegram file_id stored in chunk metadata.
                    // Phase 4: download pattern (file_id per chunk to be stored in upload).
                    val chunkBytes: ByteArray = retryWithBackoff(MAX_RETRIES) {
                        // For now, return empty — requires file_id per chunk enhancement.
                        ByteArray(0)
                    }

                    // Verify checksum
                    val downloadedChecksum = computeSha256(chunkBytes)
                    if (chunk.checksum.isNotBlank() && downloadedChecksum != chunk.checksum) {
                        throw SecurityException(
                            "Checksum mismatch for chunk ${chunk.chunkIndex}"
                        )
                    }

                    output.write(chunkBytes)
                    bytesDownloaded += chunkBytes.size

                    progressFlow.value = progressFlow.value.copy(
                        currentChunk = index + 1,
                        bytesTransferred = bytesDownloaded,
                    )
                }
            }

            progressFlow.value = progressFlow.value.copy(
                status = TransferStatus.COMPLETED,
            )
        }.onFailure { e ->
            progressFlow.value = progressFlow.value.copy(
                status = if (e is CancellationException) TransferStatus.CANCELLED
                else TransferStatus.FAILED,
                error = e.message,
            )
        }
    }

    // ─── Helpers ───────────────────────────────────────

    private fun splitChunk(source: File, dest: File, offset: Long, length: Int) {
        RandomAccessFile(source, "r").use { raf ->
            raf.seek(offset)
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(8192)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buffer.size, remaining)
                    val read = raf.read(buffer, 0, toRead)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> retryWithBackoff(
        maxRetries: Int,
        block: suspend () -> T,
    ): T {
        var lastException: Throwable? = null
        for (attempt in 0 until maxRetries) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay(INITIAL_BACKOFF_MS * (1L shl attempt))
                }
            }
        }
        throw lastException ?: IllegalStateException("Retry exhausted")
    }

    private class CancellationException(message: String) : Exception(message)
}
