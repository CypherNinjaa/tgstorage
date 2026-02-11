package com.tgstorage.data.transfer

import android.content.Context
import com.tgstorage.common.security.CryptoManager
import com.tgstorage.data.local.dao.ChunkDao
import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.dao.SyncStateDao
import com.tgstorage.data.local.entity.ChunkEntity
import com.tgstorage.data.local.entity.MetadataKeys
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
    val mimeType: String,
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
    private val context: Context,
    private val api: TelegramApiService,
    private val chunkDao: ChunkDao,
    private val syncStateDao: SyncStateDao,
    private val fileDao: FileDao,
    private val metadataDao: MetadataDao,
) {
    companion object {
        // REDUCED TO 10MB: For safer uploads and downloads within Telegram limits.
        // Telegram getFile API supports up to 20MB downloads, but keeping chunks at 10MB
        // provides extra margin for network stability and API changes.
        const val DEFAULT_CHUNK_SIZE = 10L * 1024 * 1024          // 10 MB per chunk
        // Files under 10MB are uploaded as single document (fast path)
        private const val SMALL_FILE_THRESHOLD = 10L * 1024 * 1024 // 10 MB
        private const val TELEGRAM_DOWNLOAD_LIMIT = 20L * 1024 * 1024 // 20 MB - Telegram Bot API getFile limit
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
        val encrypt = shouldEncryptUpload()

        progressFlow.value = progressFlow.value.copy(
            totalChunks = 1,
            totalBytes = fileSize,
            status = TransferStatus.IN_PROGRESS,
        )

        val checksum = computeSha256(localFile)
        val uploadFile = if (encrypt) createEncryptedTempFile(localFile, fileId) else localFile
        val uploadName = if (encrypt) "${fileName}.enc" else fileName
        try {
            val uploadTotal = uploadFile.length().coerceAtLeast(1L)
            val message = retryWithBackoff(MAX_RETRIES) {
                api.sendDocument(
                    token = token, chatId = chatId, file = uploadFile, fileName = uploadName,
                    onProgress = { bytesWritten, totalBytes ->
                        val scaled = if (encrypt) {
                            ((bytesWritten.toDouble() / uploadTotal) * fileSize).toLong()
                        } else {
                            bytesWritten
                        }
                        progressFlow.value = progressFlow.value.copy(
                            currentChunk = 0,
                            bytesTransferred = scaled,
                            totalBytes = fileSize,
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
                    checksum = checksum,
                    size = fileSize,
                )
            )
        } finally {
            if (uploadFile != localFile) secureDelete(uploadFile)
        }

        // Mark as uploaded
        markUploaded(fileId, encrypt)

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
        val encrypt = shouldEncryptUpload()
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
                val plainBytes = chunkFile.readBytes()
                val chunkChecksum = computeSha256(plainBytes)
                val uploadFile = if (encrypt) {
                    val encryptedBytes = CryptoManager.encrypt(plainBytes)
                    val encryptedFile = File(tempDir, "chunk_$chunkIndex.enc")
                    encryptedFile.writeBytes(encryptedBytes)
                    secureDelete(chunkFile)
                    encryptedFile
                } else {
                    chunkFile
                }

                val uploadTotal = uploadFile.length().coerceAtLeast(1L)
                val chunkName = if (encrypt) "${fileName}.part${chunkIndex + 1}of$totalChunks.enc"
                    else "${fileName}.part${chunkIndex + 1}of$totalChunks"
                val message = retryWithBackoff(MAX_RETRIES) {
                    api.sendDocument(
                        token = token, chatId = chatId, file = uploadFile,
                        fileName = chunkName,
                        onProgress = { bytesWritten, _ ->
                            val scaled = if (encrypt) {
                                ((bytesWritten.toDouble() / uploadTotal) * thisChunkSize).toLong()
                            } else {
                                bytesWritten
                            }
                            progressFlow.value = progressFlow.value.copy(
                                bytesTransferred = bytesUploaded + scaled,
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

                if (uploadFile != chunkFile) secureDelete(uploadFile)
                if (chunkFile.exists()) chunkFile.delete()
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

        markUploaded(fileId, encrypt)
        progressFlow.value = progressFlow.value.copy(status = TransferStatus.COMPLETED)
    }

    private suspend fun markUploaded(fileId: Long, encrypted: Boolean) {
        val file = fileDao.getFileById(fileId)
        if (file != null && encrypted && !file.encryptionFlag) {
            fileDao.updateFile(file.copy(encryptionFlag = true))
        }
        val existing = syncStateDao.getSyncState(fileId)
        syncStateDao.insertOrUpdate(
            (existing ?: SyncStateEntity(fileId = fileId)).copy(
                status = SyncStatus.UPLOADED,
                lastAttempt = System.currentTimeMillis(),
            )
        )

        // Generate thumbnail before deleting local file, then clean up
        val entity = file ?: fileDao.getFileById(fileId)
        entity?.localUri?.let { localPath ->
            ThumbnailManager.generateThumbnail(
                context, fileDao, fileId, localPath, entity.mimeType,
            )
            // Delete the full-size local copy to free storage
            val localFile = File(localPath)
            if (localFile.exists()) localFile.delete()
            fileDao.clearLocalUri(fileId)
        }
    }

    /**
     * Downloads a file by fetching all chunks from Telegram,
     * verifying checksums, and reassembling into the output file.
     * 
     * Note: Telegram Bot API getFile only supports files up to 20MB.
     * Files uploaded as a single document >20MB cannot be downloaded.
     */
    suspend fun downloadFile(
        token: String,
        fileId: Long,
        fileName: String,
        outputFile: File,
        progressFlow: MutableStateFlow<TransferProgress>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val decrypt = shouldDecryptDownload(fileId)
            val chunks = chunkDao.getChunksForFileSync(fileId)
            if (chunks.isEmpty()) throw IllegalStateException("No chunks found for file $fileId. The file may not have been uploaded through this app.")

            // Check if any chunk exceeds Telegram's 20MB download limit
            // This happens for files that were uploaded as single documents (20-50MB)
            // before we fixed the chunking threshold
            val oversizedChunks = chunks.filter { it.size > TELEGRAM_DOWNLOAD_LIMIT }
            if (oversizedChunks.isNotEmpty()) {
                val sizeInMB = oversizedChunks.first().size / (1024 * 1024)
                throw IllegalStateException(
                    "This file (${sizeInMB}MB) exceeds Telegram's 20MB download limit. " +
                    "It was uploaded as a single document before the fix. " +
                    "Please delete it from the cloud and re-upload to enable downloading."
                )
            }

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

                    // Get file path (cached — avoids redundant API calls)
                    val filePath = retryWithBackoff(MAX_RETRIES) {
                        api.getFilePathCached(token, tgFileId).getOrThrow()
                    }

                    // Stream download to temp file then read bytes for decrypt/verify
                    val tempChunkFile = File(context.cacheDir, "dl_chunk_${fileId}_${chunk.chunkIndex}")
                    try {
                        retryWithBackoff(MAX_RETRIES) {
                            FileOutputStream(tempChunkFile).use { tempOut ->
                                api.downloadFileStreaming(token, filePath, tempOut).getOrThrow()
                            }
                        }
                        val chunkBytes = tempChunkFile.readBytes()
                        val plainBytes = if (decrypt) CryptoManager.decrypt(chunkBytes) else chunkBytes

                        // Verify checksum
                        if (chunk.checksum.isNotBlank()) {
                            val downloadedChecksum = computeSha256(plainBytes)
                            if (downloadedChecksum != chunk.checksum) {
                                throw SecurityException("Checksum mismatch for chunk ${chunk.chunkIndex}")
                            }
                        }

                        output.write(plainBytes)
                        bytesDownloaded += plainBytes.size
                    } finally {
                        tempChunkFile.delete()
                    }

                    progressFlow.value = progressFlow.value.copy(
                        currentChunk = index + 1,
                        bytesTransferred = bytesDownloaded,
                    )
                }
            }

            // Phase 9: Verify full file hash after download reassembly
            val file = fileDao.getFileById(fileId)
            if (file != null && file.sha256.isNotBlank()) {
                val downloadedHash = computeSha256(outputFile)
                if (downloadedHash != file.sha256) {
                    outputFile.delete()
                    throw SecurityException(
                        "File integrity check failed: hash mismatch after download. " +
                        "Expected ${file.sha256.take(16)}… got ${downloadedHash.take(16)}…"
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

    private suspend fun shouldEncryptUpload(): Boolean {
        return metadataDao.getValue(MetadataKeys.ENCRYPTION_ENABLED)?.toBoolean() ?: true
    }

    private suspend fun shouldDecryptDownload(fileId: Long): Boolean {
        val file = fileDao.getFileById(fileId)
        return file?.encryptionFlag == true
    }

    private fun createEncryptedTempFile(source: File, fileId: Long): File {
        val encryptedBytes = CryptoManager.encrypt(source.readBytes())
        val tempFile = File(source.parentFile ?: source.absoluteFile.parentFile, "enc_${fileId}_${System.currentTimeMillis()}")
        tempFile.writeBytes(encryptedBytes)
        return tempFile
    }

    private fun secureDelete(file: File) {
        runCatching {
            if (!file.exists()) return
            RandomAccessFile(file, "rw").use { raf ->
                val size = raf.length()
                raf.seek(0)
                val buffer = ByteArray(8192)
                var remaining = size
                while (remaining > 0) {
                    val toWrite = minOf(buffer.size.toLong(), remaining).toInt()
                    raf.write(buffer, 0, toWrite)
                    remaining -= toWrite
                }
            }
        }
        file.delete()
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
