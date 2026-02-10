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
        const val DEFAULT_CHUNK_SIZE = 20L * 1024 * 1024          // 20 MB
        private const val SMALL_FILE_THRESHOLD = 49L * 1024 * 1024 // 49 MB — under Bot API 50 MB limit
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
    }

    /**
     * Uploads a file to Telegram.
     * - Small files (< 49 MB): upload directly in one shot — no chunk splitting.
     * - Large files: split into 20 MB chunks with resume support.
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

            if (fileSize <= SMALL_FILE_THRESHOLD) {
                uploadSmallFile(token, chatId, fileId, localFile, fileName, fileSize, progressFlow)
            } else {
                uploadLargeFile(token, chatId, fileId, localFile, fileName, fileSize, progressFlow)
            }
        }.onFailure { e ->
            if (e !is CancellationException) {
                syncStateDao.getSyncState(fileId)?.let { sync ->
                    syncStateDao.insertOrUpdate(
                        sync.copy(status = SyncStatus.FAILED, lastAttempt = System.currentTimeMillis())
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

    /** Direct single-shot upload — no temp files, no chunking. */
    private suspend fun uploadSmallFile(
        token: String,
        chatId: String,
        fileId: Long,
        localFile: File,
        fileName: String,
        fileSize: Long,
        progressFlow: MutableStateFlow<TransferProgress>,
    ) {
        progressFlow.value = progressFlow.value.copy(
            totalChunks = 1,
            totalBytes = fileSize,
            status = TransferStatus.IN_PROGRESS,
        )

        // Upload directly — no copy needed
        val message = retryWithBackoff(MAX_RETRIES) {
            api.sendDocument(
                token = token, chatId = chatId, file = localFile, fileName = fileName,
                onProgress = { bytesWritten, totalBytes ->
                    progressFlow.value = progressFlow.value.copy(
                        currentChunk = 0,
                        bytesTransferred = bytesWritten,
                        totalBytes = if (totalBytes > 0) totalBytes else fileSize,
                    )
                },
            ).getOrThrow()
        }

        // Store single chunk record with Telegram file_id for downloads
        val telegramFileId = message.document?.fileId
        chunkDao.insertChunk(
            ChunkEntity(
                fileId = fileId,
                chunkIndex = 0,
                telegramMessageId = message.messageId,
                telegramFileId = telegramFileId,
                checksum = computeSha256(localFile),
                size = fileSize,
            )
        )

        // Mark as uploaded
        markUploaded(fileId)

        progressFlow.value = progressFlow.value.copy(
            currentChunk = 1,
            bytesTransferred = fileSize,
            status = TransferStatus.COMPLETED,
        )
    }

    /** Chunked upload for large files with resume support. */
    private suspend fun uploadLargeFile(
        token: String,
        chatId: String,
        fileId: Long,
        localFile: File,
        fileName: String,
        fileSize: Long,
        progressFlow: MutableStateFlow<TransferProgress>,
    ) {
        val chunkSize = DEFAULT_CHUNK_SIZE
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()

        progressFlow.value = progressFlow.value.copy(
            totalChunks = totalChunks,
            totalBytes = fileSize,
            status = TransferStatus.IN_PROGRESS,
        )

        // Resume: check already-uploaded chunks
        val existingChunks = chunkDao.getChunksForFileSync(fileId)
        val uploadedIndices = existingChunks
            .filter { it.telegramMessageId != null }
            .map { it.chunkIndex }
            .toSet()
        var bytesUploaded = existingChunks
            .filter { it.telegramMessageId != null }
            .sumOf { it.size }

        // Use unique temp dir per file to avoid race conditions
        val tempDir = File(localFile.parentFile, "chunks_${fileId}")
        if (!tempDir.exists()) tempDir.mkdirs()

        try {
            for (chunkIndex in 0 until totalChunks) {
                if (chunkIndex in uploadedIndices) {
                    progressFlow.value = progressFlow.value.copy(
                        currentChunk = chunkIndex + 1,
                        bytesTransferred = bytesUploaded,
                    )
                    continue
                }

                if (progressFlow.value.status == TransferStatus.CANCELLED) {
                    throw CancellationException("Upload cancelled")
                }

                val offset = chunkIndex.toLong() * chunkSize
                val thisChunkSize = minOf(chunkSize, fileSize - offset).toInt()

                val chunkFile = File(tempDir, "chunk_$chunkIndex")
                splitChunk(localFile, chunkFile, offset, thisChunkSize)
                val chunkChecksum = computeSha256(chunkFile)

                val message = retryWithBackoff(MAX_RETRIES) {
                    api.sendDocument(
                        token = token, chatId = chatId, file = chunkFile,
                        fileName = "${fileName}.part${chunkIndex + 1}of$totalChunks",
                        onProgress = { bytesWritten, _ ->
                            progressFlow.value = progressFlow.value.copy(
                                bytesTransferred = bytesUploaded + bytesWritten,
                            )
                        },
                    ).getOrThrow()
                }

                chunkDao.insertChunk(
                    ChunkEntity(
                        fileId = fileId,
                        chunkIndex = chunkIndex,
                        telegramMessageId = message.messageId,
                        telegramFileId = message.document?.fileId,
                        checksum = chunkChecksum,
                        size = thisChunkSize.toLong(),
                    )
                )

                chunkFile.delete()
                bytesUploaded += thisChunkSize
                progressFlow.value = progressFlow.value.copy(
                    currentChunk = chunkIndex + 1,
                    bytesTransferred = bytesUploaded,
                )
            }
        } finally {
            // Always clean up this file's temp dir
            tempDir.deleteRecursively()
        }

        markUploaded(fileId)
        progressFlow.value = progressFlow.value.copy(status = TransferStatus.COMPLETED)
    }

    private suspend fun markUploaded(fileId: Long) {
        val existing = syncStateDao.getSyncState(fileId)
        syncStateDao.insertOrUpdate(
            (existing ?: SyncStateEntity(fileId = fileId)).copy(
                status = SyncStatus.UPLOADED,
                lastAttempt = System.currentTimeMillis(),
            )
        )
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

                    val tgFileId = chunk.telegramFileId
                        ?: throw IllegalStateException("Chunk ${chunk.chunkIndex} has no Telegram file_id")

                    // Get file path from Telegram
                    val tgFile = retryWithBackoff(MAX_RETRIES) {
                        api.getFile(token, tgFileId).getOrThrow()
                    }
                    val filePath = tgFile.filePath
                        ?: throw IllegalStateException("No file_path for chunk ${chunk.chunkIndex}")

                    // Download chunk bytes
                    val chunkBytes = retryWithBackoff(MAX_RETRIES) {
                        api.downloadFile(token, filePath).getOrThrow()
                    }

                    // Verify checksum
                    if (chunk.checksum.isNotBlank()) {
                        val downloadedChecksum = computeSha256(chunkBytes)
                        if (downloadedChecksum != chunk.checksum) {
                            throw SecurityException("Checksum mismatch for chunk ${chunk.chunkIndex}")
                        }
                    }

                    output.write(chunkBytes)
                    bytesDownloaded += chunkBytes.size

                    progressFlow.value = progressFlow.value.copy(
                        currentChunk = index + 1,
                        bytesTransferred = bytesDownloaded,
                    )
                }
            }

            progressFlow.value = progressFlow.value.copy(status = TransferStatus.COMPLETED)
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

    private suspend fun <T> retryWithBackoff(maxRetries: Int, block: suspend () -> T): T {
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
