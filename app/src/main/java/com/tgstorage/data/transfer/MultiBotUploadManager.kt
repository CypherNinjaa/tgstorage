package com.tgstorage.data.transfer

import android.content.Context
import com.tgstorage.TgStorageApp
import com.tgstorage.common.security.CryptoManager
import com.tgstorage.data.local.dao.ChunkDao
import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.MetadataDao
import com.tgstorage.data.local.dao.SyncStateDao
import com.tgstorage.data.local.entity.BotEntity
import com.tgstorage.data.local.entity.ChunkEntity
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.data.local.entity.SyncStateEntity
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.repository.BotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Manages parallel uploads and downloads using multiple Telegram bots.
 * 
 * Key features:
 * - Distributes files across bots for multi-file transfers
 * - Distributes chunks across bots for large file transfers
 * - Each bot operates independently with its own semaphore
 * - Graceful error handling - one bot failure doesn't affect others
 * - Database updates happen immediately after each chunk operation
 */
class MultiBotUploadManager(
    private val context: Context,
    private val botRepository: BotRepository,
    private val api: TelegramApiService,
    private val chunkDao: ChunkDao,
    private val syncStateDao: SyncStateDao,
    private val fileDao: FileDao,
    private val metadataDao: MetadataDao,
) {
    companion object {
        // Chunk size for splitting large files
        const val CHUNK_SIZE = 10L * 1024 * 1024 // 10 MB
        private const val SMALL_FILE_THRESHOLD = 10L * 1024 * 1024 // 10 MB
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        
        // Per-bot concurrency limit (Telegram rate limit is ~30 req/sec per bot)
        private const val MAX_CONCURRENT_PER_BOT = 2
        
        // Telegram download limit via getFile API
        private const val TELEGRAM_DOWNLOAD_LIMIT = 20L * 1024 * 1024 // 20 MB
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Mutex for thread-safe database operations
    private val dbMutex = Mutex()
    
    // Semaphores per bot to limit concurrent uploads per bot
    private val botSemaphores = mutableMapOf<Long, Semaphore>()
    private val semaphoreLock = Mutex()

    /**
     * Get or create a semaphore for a bot.
     */
    private suspend fun getBotSemaphore(botId: Long): Semaphore {
        return semaphoreLock.withLock {
            botSemaphores.getOrPut(botId) { Semaphore(MAX_CONCURRENT_PER_BOT) }
        }
    }

    /**
     * Upload a single file using multiple bots for chunk distribution.
     * If the file is small, uses a single bot. If large, distributes chunks across bots.
     */
    suspend fun uploadFileWithMultipleBots(
        file: FileEntity,
        localFile: File,
        progressFlow: MutableStateFlow<TransferProgress>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val activeBots = botRepository.getActiveBotsWithTokens()
            if (activeBots.isEmpty()) {
                throw IllegalStateException("No active bots configured")
            }

            val fileSize = localFile.length()
            
            if (fileSize <= SMALL_FILE_THRESHOLD) {
                // Small file: use primary bot (or first available)
                val (bot, token) = activeBots.first()
                uploadSmallFile(bot, token, file.id, localFile, file.name, fileSize, progressFlow)
            } else {
                // Large file: distribute chunks across bots
                uploadLargeFileWithBots(activeBots, file.id, localFile, file.name, fileSize, progressFlow)
            }
        }.onFailure { e ->
            if (e !is CancellationException) {
                syncStateDao.getSyncState(file.id)?.let { sync ->
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

    /**
     * Upload a file using a specific assigned bot (for file-level parallelism).
     * This is called when distributing different files across different bots.
     */
    suspend fun uploadFileWithSpecificBot(
        bot: BotEntity,
        token: String,
        file: FileEntity,
        localFile: File,
        progressFlow: MutableStateFlow<TransferProgress>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fileSize = localFile.length()
            uploadSmallFile(bot, token, file.id, localFile, file.name, fileSize, progressFlow)
        }.onFailure { e ->
            if (e !is CancellationException) {
                syncStateDao.getSyncState(file.id)?.let { sync ->
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

    /**
     * Upload a small file using a single bot.
     */
    private suspend fun uploadSmallFile(
        bot: BotEntity,
        token: String,
        fileId: Long,
        localFile: File,
        fileName: String,
        fileSize: Long,
        progressFlow: MutableStateFlow<TransferProgress>,
    ) {
        val semaphore = getBotSemaphore(bot.id)
        val encrypt = shouldEncryptUpload()

        progressFlow.value = progressFlow.value.copy(
            totalChunks = 1,
            totalBytes = fileSize,
            status = TransferStatus.IN_PROGRESS,
        )

        semaphore.withPermit {
            val checksum = computeSha256(localFile)
            val uploadFile = if (encrypt) createEncryptedTempFile(localFile, fileId) else localFile
            val uploadName = if (encrypt) "${fileName}.enc" else fileName
            
            try {
                val uploadTotal = uploadFile.length().coerceAtLeast(1L)
                val message = retryWithBackoff(MAX_RETRIES) {
                    api.sendDocument(
                        token = token,
                        chatId = bot.chatId,
                        file = uploadFile,
                        fileName = uploadName,
                        onProgress = { bytesWritten, _ ->
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

                // Store chunk record with bot ID
                dbMutex.withLock {
                    chunkDao.insertChunk(
                        ChunkEntity(
                            fileId = fileId,
                            chunkIndex = 0,
                            telegramMessageId = message.messageId,
                            telegramFileId = message.document?.fileId,
                            checksum = checksum,
                            size = fileSize,
                            botId = bot.id,
                        )
                    )
                }
            } finally {
                if (uploadFile != localFile) secureDelete(uploadFile)
            }
        }

        markUploaded(fileId, encrypt)
        progressFlow.value = progressFlow.value.copy(
            currentChunk = 1,
            bytesTransferred = fileSize,
            status = TransferStatus.COMPLETED,
        )
    }

    /**
     * Upload a large file by distributing chunks across multiple bots.
     * Each bot uploads its assigned chunks in parallel.
     */
    private suspend fun uploadLargeFileWithBots(
        bots: List<Pair<BotEntity, String>>,
        fileId: Long,
        localFile: File,
        fileName: String,
        fileSize: Long,
        progressFlow: MutableStateFlow<TransferProgress>,
    ) = coroutineScope {
        val encrypt = shouldEncryptUpload()
        val chunkSize = CHUNK_SIZE
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()

        progressFlow.value = progressFlow.value.copy(
            totalChunks = totalChunks,
            totalBytes = fileSize,
            status = TransferStatus.IN_PROGRESS,
        )

        // Check already-uploaded chunks for resume support
        val existingChunks = chunkDao.getChunksForFileSync(fileId)
        val uploadedIndices = existingChunks
            .filter { it.telegramMessageId != null }
            .map { it.chunkIndex }
            .toSet()
        
        // Track progress across all bots
        val progressMutex = Mutex()
        var totalBytesUploaded = existingChunks
            .filter { it.telegramMessageId != null }
            .sumOf { it.size }
        var chunksCompleted = uploadedIndices.size

        // Assign chunks to bots in round-robin fashion
        val chunkAssignments = (0 until totalChunks)
            .filter { it !in uploadedIndices }
            .mapIndexed { idx, chunkIndex ->
                val botIndex = idx % bots.size
                Triple(chunkIndex, bots[botIndex].first, bots[botIndex].second)
            }
            .groupBy { it.second.id }

        // Create temp directory for chunks
        val tempDir = File(context.cacheDir, "multibot_chunks_$fileId")
        if (!tempDir.exists()) tempDir.mkdirs()

        try {
            // Launch parallel uploads per bot
            val jobs = chunkAssignments.map { (botId, chunks) ->
                async {
                    val bot = chunks.first().second
                    val token = chunks.first().third
                    val semaphore = getBotSemaphore(botId)

                    for ((chunkIndex, _, _) in chunks) {
                        if (progressFlow.value.status == TransferStatus.CANCELLED) {
                            throw CancellationException("Upload cancelled")
                        }

                        semaphore.withPermit {
                            uploadSingleChunk(
                                bot = bot,
                                token = token,
                                fileId = fileId,
                                localFile = localFile,
                                fileName = fileName,
                                chunkIndex = chunkIndex,
                                totalChunks = totalChunks,
                                chunkSize = chunkSize,
                                fileSize = fileSize,
                                encrypt = encrypt,
                                tempDir = tempDir,
                            )
                        }

                        // Update progress
                        val offset = chunkIndex.toLong() * chunkSize
                        val thisChunkSize = minOf(chunkSize, fileSize - offset)
                        
                        progressMutex.withLock {
                            totalBytesUploaded += thisChunkSize
                            chunksCompleted++
                            progressFlow.value = progressFlow.value.copy(
                                currentChunk = chunksCompleted,
                                bytesTransferred = totalBytesUploaded,
                            )
                        }
                    }
                }
            }

            // Wait for all bots to complete
            jobs.awaitAll()

        } finally {
            // Cleanup temp directory
            tempDir.deleteRecursively()
        }

        markUploaded(fileId, encrypt)
        progressFlow.value = progressFlow.value.copy(status = TransferStatus.COMPLETED)
    }

    /**
     * Upload a single chunk using a specific bot.
     */
    private suspend fun uploadSingleChunk(
        bot: BotEntity,
        token: String,
        fileId: Long,
        localFile: File,
        fileName: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkSize: Long,
        fileSize: Long,
        encrypt: Boolean,
        tempDir: File,
    ) {
        val offset = chunkIndex.toLong() * chunkSize
        val thisChunkSize = minOf(chunkSize, fileSize - offset).toInt()

        // Create chunk file
        val chunkFile = File(tempDir, "chunk_${bot.id}_$chunkIndex")
        splitChunk(localFile, chunkFile, offset, thisChunkSize)
        
        val plainBytes = chunkFile.readBytes()
        val chunkChecksum = computeSha256(plainBytes)
        
        val uploadFile = if (encrypt) {
            val encryptedBytes = CryptoManager.encrypt(plainBytes)
            val encryptedFile = File(tempDir, "chunk_${bot.id}_$chunkIndex.enc")
            encryptedFile.writeBytes(encryptedBytes)
            secureDelete(chunkFile)
            encryptedFile
        } else {
            chunkFile
        }

        val chunkName = if (encrypt) "${fileName}.part${chunkIndex + 1}of$totalChunks.enc"
        else "${fileName}.part${chunkIndex + 1}of$totalChunks"

        try {
            val message = retryWithBackoff(MAX_RETRIES) {
                api.sendDocument(
                    token = token,
                    chatId = bot.chatId,
                    file = uploadFile,
                    fileName = chunkName,
                ) { _, _ -> } // No per-chunk progress tracking
                    .getOrThrow()
            }

            // Store chunk with bot ID
            dbMutex.withLock {
                chunkDao.insertChunk(
                    ChunkEntity(
                        fileId = fileId,
                        chunkIndex = chunkIndex,
                        telegramMessageId = message.messageId,
                        telegramFileId = message.document?.fileId,
                        checksum = chunkChecksum,
                        size = thisChunkSize.toLong(),
                        botId = bot.id,
                    )
                )
            }
        } finally {
            if (uploadFile != chunkFile) secureDelete(uploadFile)
            if (chunkFile.exists()) chunkFile.delete()
        }
    }

    // ─── Download Methods ──────────────────────────────

    /**
     * Download a file using multiple bots for parallel chunk downloads.
     * Chunks are distributed across bots for maximum speed.
     */
    suspend fun downloadFileWithMultipleBots(
        fileId: Long,
        fileName: String,
        outputFile: File,
        progressFlow: MutableStateFlow<TransferProgress>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val activeBots = botRepository.getActiveBotsWithTokens()
            if (activeBots.isEmpty()) {
                throw IllegalStateException("No active bots configured")
            }

            val decrypt = shouldDecryptDownload(fileId)
            val chunks = chunkDao.getChunksForFileSync(fileId)
            if (chunks.isEmpty()) {
                throw IllegalStateException("No chunks found for file $fileId")
            }

            // Check for oversized chunks (uploaded before fix)
            val oversizedChunks = chunks.filter { it.size > TELEGRAM_DOWNLOAD_LIMIT }
            if (oversizedChunks.isNotEmpty()) {
                val sizeInMB = oversizedChunks.first().size / (1024 * 1024)
                throw IllegalStateException(
                    "This file (${sizeInMB}MB) exceeds Telegram's 20MB download limit. " +
                    "Please delete and re-upload."
                )
            }

            val totalBytes = chunks.sumOf { it.size }
            progressFlow.value = progressFlow.value.copy(
                totalChunks = chunks.size,
                totalBytes = totalBytes,
                status = TransferStatus.IN_PROGRESS,
            )

            if (chunks.size == 1) {
                // Single chunk: use any bot
                val (bot, token) = activeBots.first()
                downloadSingleChunk(bot, token, chunks.first(), outputFile, decrypt, progressFlow, totalBytes)
            } else {
                // Multiple chunks: download in parallel across bots
                downloadChunksWithBots(activeBots, chunks, outputFile, decrypt, progressFlow, totalBytes)
            }

            // Verify full file hash
            val file = fileDao.getFileById(fileId)
            if (file != null && file.sha256.isNotBlank()) {
                val downloadedHash = computeSha256(outputFile)
                if (downloadedHash != file.sha256) {
                    outputFile.delete()
                    throw SecurityException("File integrity check failed: hash mismatch")
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

    /**
     * Download a file using a specific assigned bot (for file-level parallelism).
     */
    suspend fun downloadFileWithSpecificBot(
        bot: BotEntity,
        token: String,
        fileId: Long,
        fileName: String,
        outputFile: File,
        progressFlow: MutableStateFlow<TransferProgress>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val decrypt = shouldDecryptDownload(fileId)
            val chunks = chunkDao.getChunksForFileSync(fileId)
            if (chunks.isEmpty()) {
                throw IllegalStateException("No chunks found for file $fileId")
            }

            val totalBytes = chunks.sumOf { it.size }
            progressFlow.value = progressFlow.value.copy(
                totalChunks = chunks.size,
                totalBytes = totalBytes,
                status = TransferStatus.IN_PROGRESS,
            )

            // Download all chunks using this single bot
            downloadChunksSequentially(bot, token, chunks, outputFile, decrypt, progressFlow, totalBytes)

            // Verify full file hash
            val file = fileDao.getFileById(fileId)
            if (file != null && file.sha256.isNotBlank()) {
                val downloadedHash = computeSha256(outputFile)
                if (downloadedHash != file.sha256) {
                    outputFile.delete()
                    throw SecurityException("File integrity check failed: hash mismatch")
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

    /**
     * Download a single chunk file.
     */
    private suspend fun downloadSingleChunk(
        bot: BotEntity,
        token: String,
        chunk: ChunkEntity,
        outputFile: File,
        decrypt: Boolean,
        progressFlow: MutableStateFlow<TransferProgress>,
        totalBytes: Long,
    ) {
        val semaphore = getBotSemaphore(bot.id)
        
        semaphore.withPermit {
            val tgFileId = chunk.telegramFileId
                ?: throw IllegalStateException("Chunk has no Telegram file_id")

            // Use cached file path to avoid redundant API calls
            val filePath = retryWithBackoff(MAX_RETRIES) {
                api.getFilePathCached(token, tgFileId).getOrThrow()
            }

            // Stream download to temp file, then decrypt/verify
            val tempFile = File(context.cacheDir, "dl_single_${chunk.fileId}_${System.currentTimeMillis()}")
            try {
                retryWithBackoff(MAX_RETRIES) {
                    FileOutputStream(tempFile).use { tempOut ->
                        api.downloadFileStreaming(token, filePath, tempOut).getOrThrow()
                    }
                }
                val chunkBytes = tempFile.readBytes()
                val plainBytes = if (decrypt) CryptoManager.decrypt(chunkBytes) else chunkBytes

                // Verify checksum
                if (chunk.checksum.isNotBlank()) {
                    val downloadedChecksum = computeSha256(plainBytes)
                    if (downloadedChecksum != chunk.checksum) {
                        throw SecurityException("Checksum mismatch for chunk")
                    }
                }

                outputFile.writeBytes(plainBytes)
            } finally {
                tempFile.delete()
            }
            
            progressFlow.value = progressFlow.value.copy(
                currentChunk = 1,
                bytesTransferred = totalBytes,
            )
        }
    }

    /**
     * Download chunks sequentially using a single bot.
     */
    private suspend fun downloadChunksSequentially(
        bot: BotEntity,
        token: String,
        chunks: List<ChunkEntity>,
        outputFile: File,
        decrypt: Boolean,
        progressFlow: MutableStateFlow<TransferProgress>,
        totalBytes: Long,
    ) {
        val semaphore = getBotSemaphore(bot.id)
        var bytesDownloaded = 0L

        java.io.FileOutputStream(outputFile).use { output ->
            for ((index, chunk) in chunks.sortedBy { it.chunkIndex }.withIndex()) {
                if (progressFlow.value.status == TransferStatus.CANCELLED) {
                    throw CancellationException("Download cancelled")
                }

                semaphore.withPermit {
                    val tgFileId = chunk.telegramFileId
                        ?: throw IllegalStateException("Chunk ${chunk.chunkIndex} has no file_id")

                    // Use cached file path
                    val filePath = retryWithBackoff(MAX_RETRIES) {
                        api.getFilePathCached(token, tgFileId).getOrThrow()
                    }

                    // Stream download to temp file
                    val tempFile = File(context.cacheDir, "dl_seq_${chunks.first().fileId}_${chunk.chunkIndex}")
                    try {
                        retryWithBackoff(MAX_RETRIES) {
                            FileOutputStream(tempFile).use { tempOut ->
                                api.downloadFileStreaming(token, filePath, tempOut).getOrThrow()
                            }
                        }
                        val chunkBytes = tempFile.readBytes()
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
                        tempFile.delete()
                    }

                    progressFlow.value = progressFlow.value.copy(
                        currentChunk = index + 1,
                        bytesTransferred = bytesDownloaded,
                    )
                }
            }
        }
    }

    /**
     * Download multiple chunks in parallel across multiple bots.
     * Each bot downloads its assigned chunks concurrently.
     */
    private suspend fun downloadChunksWithBots(
        bots: List<Pair<BotEntity, String>>,
        chunks: List<ChunkEntity>,
        outputFile: File,
        decrypt: Boolean,
        progressFlow: MutableStateFlow<TransferProgress>,
        totalBytes: Long,
    ) = coroutineScope {
        val sortedChunks = chunks.sortedBy { it.chunkIndex }
        
        // Track progress across all parallel downloads
        val progressMutex = Mutex()
        var totalBytesDownloaded = 0L
        var chunksCompleted = 0

        // Create temp dir for parallel chunk downloads
        val tempDir = File(context.cacheDir, "multibot_download_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // Assign chunks to bots in round-robin
            val chunkAssignments = sortedChunks.mapIndexed { idx, chunk ->
                val botIndex = idx % bots.size
                Triple(chunk, bots[botIndex].first, bots[botIndex].second)
            }.groupBy { it.second.id }

            // Download chunks in parallel - each bot downloads its assigned chunks
            val downloadJobs = chunkAssignments.map { (botId, assignments) ->
                async {
                    val (_, bot, token) = assignments.first()
                    val semaphore = getBotSemaphore(botId)

                    assignments.forEach { (chunk, _, _) ->
                        if (progressFlow.value.status == TransferStatus.CANCELLED) {
                            throw CancellationException("Download cancelled")
                        }

                        semaphore.withPermit {
                            val tgFileId = chunk.telegramFileId
                                ?: throw IllegalStateException("Chunk ${chunk.chunkIndex} has no file_id")

                            // Use cached file path
                            val filePath = retryWithBackoff(MAX_RETRIES) {
                                api.getFilePathCached(token, tgFileId).getOrThrow()
                            }

                            // Stream download to temp file
                            val tempDlFile = File(tempDir, "dl_${chunk.chunkIndex}")
                            retryWithBackoff(MAX_RETRIES) {
                                FileOutputStream(tempDlFile).use { tempOut ->
                                    api.downloadFileStreaming(token, filePath, tempOut).getOrThrow()
                                }
                            }
                            val chunkBytes = tempDlFile.readBytes()
                            val plainBytes = if (decrypt) CryptoManager.decrypt(chunkBytes) else chunkBytes

                            // Verify checksum
                            if (chunk.checksum.isNotBlank()) {
                                val downloadedChecksum = computeSha256(plainBytes)
                                if (downloadedChecksum != chunk.checksum) {
                                    throw SecurityException("Checksum mismatch for chunk ${chunk.chunkIndex}")
                                }
                            }

                            // Write verified chunk to final temp file
                            val chunkFile = File(tempDir, "chunk_${chunk.chunkIndex}")
                            chunkFile.writeBytes(plainBytes)
                            tempDlFile.delete()

                            // Update progress
                            progressMutex.withLock {
                                totalBytesDownloaded += plainBytes.size
                                chunksCompleted++
                                progressFlow.value = progressFlow.value.copy(
                                    currentChunk = chunksCompleted,
                                    bytesTransferred = totalBytesDownloaded,
                                )
                            }
                        }
                    }
                }
            }

            // Wait for all downloads to complete
            downloadJobs.awaitAll()

            // Reassemble chunks in order
            java.io.FileOutputStream(outputFile).use { output ->
                for (chunk in sortedChunks) {
                    val chunkFile = File(tempDir, "chunk_${chunk.chunkIndex}")
                    if (chunkFile.exists()) {
                        output.write(chunkFile.readBytes())
                    } else {
                        throw IllegalStateException("Missing chunk file ${chunk.chunkIndex}")
                    }
                }
            }
        } finally {
            // Clean up temp files
            tempDir.listFiles()?.forEach { it.delete() }
            tempDir.delete()
        }
    }

    private suspend fun shouldDecryptDownload(fileId: Long): Boolean {
        val file = fileDao.getFileById(fileId) ?: return false
        return file.encryptionFlag
    }

    // ─── Helper functions ──────────────────────────────

    private suspend fun shouldEncryptUpload(): Boolean {
        return metadataDao.getValue(MetadataKeys.ENCRYPTION_ENABLED)?.toBoolean() != false
    }

    private suspend fun markUploaded(fileId: Long, encrypted: Boolean) {
        dbMutex.withLock {
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
        }

        // Generate thumbnail and clean up local file
        val entity = fileDao.getFileById(fileId)
        entity?.localUri?.let { localPath ->
            ThumbnailManager.generateThumbnail(
                context, fileDao, fileId, localPath, entity.mimeType,
            )
            val localFile = File(localPath)
            if (localFile.exists()) localFile.delete()
            fileDao.clearLocalUri(fileId)
        }
    }

    private fun createEncryptedTempFile(localFile: File, fileId: Long): File {
        val plainBytes = localFile.readBytes()
        val encryptedBytes = CryptoManager.encrypt(plainBytes)
        val tempFile = File(context.cacheDir, "encrypt_${fileId}_${System.currentTimeMillis()}.enc")
        tempFile.writeBytes(encryptedBytes)
        return tempFile
    }

    private fun splitChunk(source: File, dest: File, offset: Long, size: Int) {
        FileInputStream(source).use { input ->
            input.skip(offset)
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var remaining = size
                while (remaining > 0) {
                    val toRead = minOf(buffer.size, remaining)
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
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
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun secureDelete(file: File) {
        if (file.exists()) {
            try {
                val length = file.length()
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(0)
                    val zeros = ByteArray(8192)
                    var remaining = length
                    while (remaining > 0) {
                        val toWrite = minOf(zeros.size.toLong(), remaining).toInt()
                        raf.write(zeros, 0, toWrite)
                        remaining -= toWrite
                    }
                }
            } catch (_: Exception) { }
            file.delete()
        }
    }

    private suspend fun <T> retryWithBackoff(
        maxRetries: Int,
        block: suspend () -> T,
    ): T {
        var lastException: Exception? = null
        var backoff = INITIAL_BACKOFF_MS
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay(backoff)
                    backoff *= 2
                }
            }
        }
        throw lastException ?: Exception("Max retries exceeded")
    }
}
