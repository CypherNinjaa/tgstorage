package com.tgstorage.data.transfer

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.local.entity.BotEntity
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.remote.TokenValidator
import com.tgstorage.data.repository.BotRepository
import com.tgstorage.data.repository.TelegramRepository
import com.tgstorage.data.sync.UploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

/**
 * Singleton that coordinates all active uploads/downloads.
 * Exposes a queue of [TransferProgress] items observable by the UI.
 *
 * SMART BATCH UPLOAD (Pentaract-inspired):
 * ─────────────────────────────────────────
 * 1. Files are sorted SMALL FIRST — small files upload quickly, freeing bots sooner.
 * 2. Max concurrent uploads = number of active bots (e.g. 4 bots → max 4 parallel).
 * 3. Each SMALL file gets exactly 1 bot (file-level parallelism).
 * 4. Each LARGE file gets ALL bots (chunk-level parallelism across bots).
 * 5. After each file completes → temp/cache cleanup → take next from queue.
 * 6. For downloads: each chunk knows which bot uploaded it (bot_id in chunks table).
 *    We use THAT bot first, then fall back to others.
 */
object TransferManager {

    private const val TAG = "TransferManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<Long, Job>() // fileId → job

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

    // ─── Upload Queue (sorted small-first) ─────────────
    private val uploadQueue = mutableListOf<FileEntity>()
    private val queueMutex = Mutex()

    // ─── Bot Pool ──────────────────────────────────────
    @Volatile
    private var cachedActiveBots: List<Pair<BotEntity, String>> = emptyList()
    @Volatile
    private var lastBotRefresh = 0L
    private const val BOT_CACHE_TTL = 5_000L

    // Track which file each bot is currently handling
    private val botAssignments = mutableMapOf<Long, MutableSet<Long>>()
    private val assignmentMutex = Mutex()

    // Prevent overlapping processNextBatch calls
    private val batchMutex = Mutex()

    // Small file threshold (matches Telegram getFile download limit safety margin)
    private const val SMALL_FILE_THRESHOLD = 10L * 1024 * 1024 // 10 MB

    // ─── Singletons ────────────────────────────────────

    private fun getChunkManager(): ChunkManager {
        val app = TgStorageApp.instance
        val db = app.database
        return ChunkManager(
            context = app,
            api = TelegramApiService(),
            chunkDao = db.chunkDao(),
            syncStateDao = db.syncStateDao(),
            fileDao = db.fileDao(),
            metadataDao = db.metadataDao(),
            botRepository = getBotRepository(),
        )
    }

    private fun getTelegramRepository(): TelegramRepository {
        val db = TgStorageApp.instance.database
        return TelegramRepository(
            api = TelegramApiService(),
            metadataDao = db.metadataDao(),
        )
    }

    private fun getBotRepository(): BotRepository {
        val app = TgStorageApp.instance
        val db = app.database
        return BotRepository(
            botDao = db.botDao(),
            metadataDao = db.metadataDao(),
            api = TelegramApiService(),
        )
    }

    private fun getMultiBotUploadManager(): MultiBotUploadManager {
        val app = TgStorageApp.instance
        val db = app.database
        return MultiBotUploadManager(
            context = app,
            botRepository = getBotRepository(),
            api = TelegramApiService(),
            chunkDao = db.chunkDao(),
            syncStateDao = db.syncStateDao(),
            fileDao = db.fileDao(),
            metadataDao = db.metadataDao(),
        )
    }

    // ─── Bot Pool Management ───────────────────────────

    private suspend fun refreshActiveBots() {
        val now = System.currentTimeMillis()
        if (now - lastBotRefresh > BOT_CACHE_TTL || cachedActiveBots.isEmpty()) {
            cachedActiveBots = getBotRepository().getActiveBotsWithTokens()
            lastBotRefresh = now
        }
    }

    /**
     * Max concurrent uploads = number of active bots.
     * If no multi-bot setup, falls back to 1.
     */
    private fun getMaxConcurrentUploads(): Int =
        cachedActiveBots.size.coerceAtLeast(1)

    /**
     * Get the bot with fewest active assignments.
     */
    private suspend fun getLeastLoadedBot(): Pair<BotEntity, String>? {
        if (cachedActiveBots.isEmpty()) return null
        return assignmentMutex.withLock {
            cachedActiveBots.minByOrNull { (bot, _) ->
                botAssignments[bot.id]?.size ?: 0
            }
        }
    }

    private suspend fun assignFileToBot(fileId: Long, botId: Long) {
        assignmentMutex.withLock {
            botAssignments.getOrPut(botId) { mutableSetOf() }.add(fileId)
        }
    }

    private suspend fun unassignFileFromBot(fileId: Long, botId: Long) {
        assignmentMutex.withLock {
            botAssignments[botId]?.remove(fileId)
        }
    }

    // ─── Upload: Enqueue ───────────────────────────────

    /**
     * Add a file to the upload queue. Files are sorted by size (small first).
     * The batch processor picks it up automatically.
     */
    fun enqueueUpload(file: FileEntity) {
        if (activeJobs.containsKey(file.id)) return
        // Reject ghost/invalid files
        if (file.size <= 0L || file.name.isBlank() || file.name.equals("unknown", ignoreCase = true)) {
            Log.w(TAG, "Rejected ghost file from upload queue: '${file.name}' (${file.size} bytes)")
            return
        }

        scope.launch {
            val alreadyQueued = queueMutex.withLock { uploadQueue.any { it.id == file.id } }
            if (alreadyQueued) return@launch

            // Add to queue, sorted ascending by size (small first)
            queueMutex.withLock {
                uploadQueue.add(file)
                uploadQueue.sortBy { it.size }
            }

            // Show PENDING in transfer list
            _transfers.update { current ->
                if (current.none { it.fileId == file.id && it.type == TransferType.UPLOAD }) {
                    current + TransferProgress(
                        fileId = file.id,
                        fileName = file.name,
                        mimeType = file.mimeType,
                        type = TransferType.UPLOAD,
                        totalBytes = file.size,
                        status = TransferStatus.PENDING,
                    )
                } else current
            }

            // Start foreground service to keep uploads alive in background
            try { UploadService.start(TgStorageApp.instance) } catch (_: Exception) {}

            // Trigger batch processing
            processNextBatch()
        }
    }

    // ─── Upload: Batch Processor ───────────────────────

    /**
     * Takes up to N files (N = bot count) from the queue and starts uploads.
     * Each small file → 1 bot. Large files → all bots for chunks.
     * Automatically triggered when any upload completes.
     */
    private fun processNextBatch() {
        scope.launch {
            batchMutex.withLock {
                refreshActiveBots()
                val maxConcurrent = getMaxConcurrentUploads()

                val activeCount = _transfers.value.count {
                    it.type == TransferType.UPLOAD && it.status == TransferStatus.IN_PROGRESS
                }
                val availableSlots = (maxConcurrent - activeCount).coerceAtLeast(0)
                if (availableSlots == 0) return@withLock

                val filesToStart = queueMutex.withLock {
                    val count = minOf(availableSlots, uploadQueue.size)
                    if (count == 0) return@withLock emptyList()
                    val batch = uploadQueue.take(count)
                    repeat(count) { uploadQueue.removeAt(0) }
                    batch
                }

                if (filesToStart.isEmpty()) return@withLock

                Log.i(TAG, "Batch: starting ${filesToStart.size} files " +
                        "(${cachedActiveBots.size} bots, ${availableSlots} slots)")

                for (file in filesToStart) {
                    startUploadJob(file)
                }
            }
        }
    }

    // ─── Upload: Single File Job ───────────────────────

    private fun startUploadJob(file: FileEntity) {
        if (activeJobs.containsKey(file.id)) return

        val progressFlow = MutableStateFlow(
            TransferProgress(
                fileId = file.id,
                fileName = file.name,
                mimeType = file.mimeType,
                type = TransferType.UPLOAD,
                totalBytes = file.size,
                status = TransferStatus.PENDING,
            )
        )

        _transfers.update { current ->
            val existing = current.find { it.fileId == file.id && it.type == TransferType.UPLOAD }
            if (existing != null) {
                current.map {
                    if (it.fileId == file.id && it.type == TransferType.UPLOAD) progressFlow.value
                    else it
                }
            } else {
                current + progressFlow.value
            }
        }

        var assignedBotId: Long? = null

        val job = scope.launch {
            // Collect progress — save job so we can cancel it when upload finishes
            val collector = launch {
                progressFlow.collect { progress ->
                    _transfers.update { list ->
                        list.map {
                            if (it.fileId == file.id && it.type == TransferType.UPLOAD) progress
                            else it
                        }
                    }
                }
            }

            try {
                refreshActiveBots()
                val activeBots = cachedActiveBots

                val (uploadFile, tempFile) = resolveFileForUpload(file)
                if (uploadFile == null) {
                    progressFlow.value = progressFlow.value.copy(
                        status = TransferStatus.FAILED,
                        error = "Cannot access file - it may have been deleted or moved",
                    )
                    return@launch
                }

                try {
                    val fileSize = uploadFile.length()
                    val isLargeFile = fileSize > SMALL_FILE_THRESHOLD

                    when {
                        // LARGE FILE + multiple bots → distribute chunks across ALL bots
                        activeBots.size >= 2 && isLargeFile -> {
                            Log.i(TAG, "Large file (${fileSize / 1024 / 1024}MB) → " +
                                    "chunks across ${activeBots.size} bots")
                            getMultiBotUploadManager().uploadFileWithMultipleBots(
                                file = file,
                                localFile = uploadFile,
                                progressFlow = progressFlow,
                            )
                        }
                        // SMALL FILE + bots available → assign to 1 least-loaded bot
                        activeBots.isNotEmpty() -> {
                            val botPair = getLeastLoadedBot() ?: activeBots.first()
                            val (bot, token) = botPair
                            assignedBotId = bot.id
                            assignFileToBot(file.id, bot.id)
                            Log.i(TAG, "Small file '${file.name}' → bot '${bot.name}'")

                            getMultiBotUploadManager().uploadFileWithSpecificBot(
                                bot = bot,
                                token = token,
                                file = file,
                                localFile = uploadFile,
                                progressFlow = progressFlow,
                            )
                        }
                        // NO multi-bot → legacy single bot
                        else -> {
                            val repo = getTelegramRepository()
                            val token = repo.getToken()
                            val chatId = repo.getChatId()

                            if (token == null || chatId == null) {
                                progressFlow.value = progressFlow.value.copy(
                                    status = TransferStatus.FAILED,
                                    error = "Bot token or channel not configured",
                                )
                                return@launch
                            }

                            val db = TgStorageApp.instance.database
                            val tokenValid = TokenValidator.validateToken(
                                api = TelegramApiService(),
                                token = token,
                                metadataDao = db.metadataDao(),
                            )
                            if (!tokenValid) {
                                progressFlow.value = progressFlow.value.copy(
                                    status = TransferStatus.FAILED,
                                    error = when (TokenValidator.tokenStatus.value) {
                                        is TokenValidator.TokenStatus.Revoked ->
                                            "Bot token revoked. Update in Settings."
                                        is TokenValidator.TokenStatus.RateLimited ->
                                            "Rate limited. Try again later."
                                        is TokenValidator.TokenStatus.NetworkError ->
                                            "No internet connection."
                                        else -> "Token validation failed."
                                    },
                                )
                                return@launch
                            }

                            getChunkManager().uploadFile(
                                token = token,
                                chatId = chatId,
                                fileId = file.id,
                                localFile = uploadFile,
                                fileName = file.name,
                                progressFlow = progressFlow,
                            )
                        }
                    }
                } finally {
                    // CRITICAL: always clean up temp file
                    tempFile?.delete()
                }
            } finally {
                // Cancel the infinite progress collector so this job can complete
                collector.cancel()
            }
        }

        activeJobs[file.id] = job

        job.invokeOnCompletion {
            activeJobs.remove(file.id)

            // Unassign bot
            assignedBotId?.let { botId ->
                scope.launch { unassignFileFromBot(file.id, botId) }
            }

            // Clean cache for this file
            cleanUploadCache(file.id)

            // CRITICAL: Force-update _transfers with final status from progressFlow.
            // The collector coroutine is already cancelled at this point, so the last
            // status (COMPLETED/FAILED) may NOT have propagated to _transfers yet.
            // Without this, processNextBatch() sees stale IN_PROGRESS count and stalls.
            val finalStatus = progressFlow.value.status
            _transfers.update { list ->
                list.map {
                    if (it.fileId == file.id && it.type == TransferType.UPLOAD) progressFlow.value
                    else it
                }
            }

            Log.i(TAG, "Upload completed for '${file.name}' with status=$finalStatus, " +
                    "queue=${uploadQueue.size}, active=${activeJobs.size}")

            // Trigger next batch — now _transfers has correct state
            processNextBatch()

            // Auto-remove completed, trigger backup
            if (finalStatus == TransferStatus.COMPLETED) {
                scope.launch { checkAutoBackupAfterUpload() }
                scope.launch {
                    delay(2000)
                    removeTransfer(file.id, TransferType.UPLOAD)
                    // Safety net: trigger batch again after removal in case slots freed up
                    processNextBatch()
                }
            } else if (finalStatus == TransferStatus.FAILED) {
                // On failure, still try to start next files
                scope.launch {
                    delay(1000)
                    processNextBatch()
                }
            }
        }
    }

    // ─── Cache Cleanup ─────────────────────────────────

    /**
     * Clean temp/cache for a specific file upload.
     * Called after each file completes (success or failure).
     */
    private fun cleanUploadCache(fileId: Long) {
        val app = TgStorageApp.instance

        // Upload temp copy
        val uploadTemp = File(app.cacheDir, "upload_temp")
        if (uploadTemp.exists()) {
            uploadTemp.listFiles()
                ?.filter { it.name.startsWith("${fileId}_") }
                ?.forEach { it.delete() }
        }

        // Multibot chunk temps
        File(app.cacheDir, "multibot_chunks_$fileId").let {
            if (it.exists()) it.deleteRecursively()
        }

        // Legacy chunk temps
        File(app.cacheDir, "chunks_$fileId").let {
            if (it.exists()) it.deleteRecursively()
        }

        // Encryption temps
        app.cacheDir.listFiles()?.filter {
            it.name.startsWith("enc_${fileId}_") ||
                    it.name.startsWith("encrypt_${fileId}_")
        }?.forEach { it.delete() }

        Log.d(TAG, "Cleaned cache for file $fileId")
    }

    /**
     * Global cache cleanup.
     */
    fun cleanAllUploadCache() {
        val app = TgStorageApp.instance
        val cacheDir = app.cacheDir

        File(cacheDir, "upload_temp").deleteRecursively()

        cacheDir.listFiles()?.filter {
            it.isDirectory && (it.name.startsWith("multibot_chunks_") ||
                    it.name.startsWith("chunks_") ||
                    it.name.startsWith("multibot_download_"))
        }?.forEach { it.deleteRecursively() }

        cacheDir.listFiles()?.filter {
            it.name.startsWith("enc_") || it.name.startsWith("encrypt_")
        }?.forEach { it.delete() }

        Log.i(TAG, "Cleaned all upload cache")
    }

    // ─── Transfer List Management ──────────────────────

    private fun removeTransfer(fileId: Long, type: TransferType) {
        _transfers.update { list -> list.filter { !(it.fileId == fileId && it.type == type) } }
    }

    // ─── File Resolution ───────────────────────────────

    private fun resolveFileForUpload(file: FileEntity): Pair<File?, File?> {
        val uriString = file.localUri ?: return Pair(null, null)

        if (!uriString.startsWith("content://")) {
            val directFile = File(uriString)
            return if (directFile.exists()) Pair(directFile, null) else Pair(null, null)
        }

        val app = TgStorageApp.instance
        val uri = Uri.parse(uriString)
        val tempDir = File(app.cacheDir, "upload_temp")
        if (!tempDir.exists()) tempDir.mkdirs()
        val tempFile = File(tempDir, "${file.id}_${System.currentTimeMillis()}_${file.name}")

        return try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output, bufferSize = 8192) }
            }
            if (tempFile.exists() && tempFile.length() > 0) Pair(tempFile, tempFile)
            else { tempFile.delete(); Pair(null, null) }
        } catch (e: Exception) {
            tempFile.delete()
            Pair(null, null)
        }
    }

    // ─── Download ──────────────────────────────────────

    /**
     * Download uses the bot that UPLOADED each chunk (bot_id in chunks table).
     * Falls back to round-robin only for legacy uploads without bot_id.
     */
    fun enqueueDownload(file: FileEntity, outputFile: File) {
        val downloadKey = -file.id
        if (activeJobs.containsKey(downloadKey)) return

        val progressFlow = MutableStateFlow(
            TransferProgress(
                fileId = file.id,
                fileName = file.name,
                mimeType = file.mimeType,
                type = TransferType.DOWNLOAD,
                totalBytes = file.size,
                status = TransferStatus.PENDING,
            )
        )
        _transfers.update { current -> current + progressFlow.value }

        val job = scope.launch {
            val collector = launch {
                progressFlow.collect { progress ->
                    _transfers.update { list ->
                        list.map {
                            if (it.fileId == file.id && it.type == TransferType.DOWNLOAD) progress
                            else it
                        }
                    }
                }
            }

            try {
                refreshActiveBots()
                val activeBots = cachedActiveBots
                val db = TgStorageApp.instance.database
                val chunks = db.chunkDao().getChunksForFileSync(file.id)
                val hasMultipleChunks = chunks.size > 1

                when {
                    // Multi-chunk + multi-bot → bot-aware parallel download
                    activeBots.size >= 2 && hasMultipleChunks -> {
                        getMultiBotUploadManager().downloadFileWithMultipleBots(
                            fileId = file.id,
                            fileName = file.name,
                            outputFile = outputFile,
                            progressFlow = progressFlow,
                        )
                    }
                    // Single chunk or single bot → use the uploading bot
                    activeBots.isNotEmpty() -> {
                        val uploadingBotId = chunks.firstOrNull()?.botId
                        val botPair = if (uploadingBotId != null) {
                            activeBots.find { it.first.id == uploadingBotId }
                        } else null

                        val (bot, token) = botPair ?: activeBots.first()
                        getMultiBotUploadManager().downloadFileWithSpecificBot(
                            bot = bot,
                            token = token,
                            fileId = file.id,
                            fileName = file.name,
                            outputFile = outputFile,
                            progressFlow = progressFlow,
                        )
                    }
                    // Legacy single-bot
                    else -> {
                        val repo = getTelegramRepository()
                        val token = repo.getToken()
                        if (token == null) {
                            progressFlow.value = progressFlow.value.copy(
                                status = TransferStatus.FAILED,
                                error = "Bot token not configured",
                            )
                            return@launch
                        }
                        getChunkManager().downloadFile(
                            token = token,
                            fileId = file.id,
                            fileName = file.name,
                            outputFile = outputFile,
                            progressFlow = progressFlow,
                        )
                    }
                }
            } finally {
                collector.cancel()
            }
        }

        activeJobs[downloadKey] = job
        job.invokeOnCompletion {
            activeJobs.remove(downloadKey)

            // Clean download temps
            val app = TgStorageApp.instance
            app.cacheDir.listFiles()?.filter {
                it.name.startsWith("dl_") ||
                        (it.isDirectory && it.name.startsWith("multibot_download_"))
            }?.forEach {
                if (it.isDirectory) it.deleteRecursively() else it.delete()
            }

            val finalStatus = progressFlow.value.status
            if (finalStatus == TransferStatus.COMPLETED) {
                scope.launch {
                    delay(2000)
                    removeTransfer(file.id, TransferType.DOWNLOAD)
                }
            }
        }
    }

    // ─── Delete from Telegram ──────────────────────────

    /**
     * Delete an uploaded file from Telegram by removing all its chunk messages
     * from the channel, then cleaning up local DB records.
     *
     * Each chunk knows which bot uploaded it (bot_id). We use that bot's token
     * to call deleteMessage. Falls back to primary bot for legacy chunks.
     *
     * @return Result.success with number of deleted messages, or failure.
     */
    suspend fun deleteFromTelegram(fileId: Long): Result<Int> {
        return try {
            val app = TgStorageApp.instance
            val db = app.database
            val chunkDao = db.chunkDao()
            val syncStateDao = db.syncStateDao()
            val fileDao = db.fileDao()
            val botRepository = getBotRepository()
            val api = TelegramApiService()

            val chunks = chunkDao.getChunksForFileSync(fileId)
            if (chunks.isEmpty()) {
                // No chunks — just clean up DB
                syncStateDao.updateStatus(fileId, com.tgstorage.data.local.entity.SyncStatus.DELETED)
                return Result.success(0)
            }

            val activeBots = botRepository.getActiveBotsWithTokens()
            val botTokenMap = activeBots.associate { (bot, token) -> bot.id to token }

            // Get primary token as fallback
            val metadataDao = db.metadataDao()
            val primaryToken = metadataDao.getValue(
                com.tgstorage.data.local.entity.MetadataKeys.BOT_TOKEN
            )?.let { encToken ->
                runCatching {
                    com.tgstorage.common.security.CryptoManager.decrypt(
                        android.util.Base64.decode(encToken, android.util.Base64.NO_WRAP)
                    ).decodeToString()
                }.getOrNull()
            }

            var deletedCount = 0
            var lastError: Exception? = null

            for (chunk in chunks) {
                val messageId = chunk.telegramMessageId ?: continue

                // Find the right token: uploading bot → primary bot → first available
                val token = chunk.botId?.let { botTokenMap[it] }
                    ?: primaryToken
                    ?: activeBots.firstOrNull()?.second

                if (token == null) {
                    lastError = IllegalStateException("No bot token available to delete chunk")
                    continue
                }

                // Get chatId for this bot
                val chatId = chunk.botId?.let { botId ->
                    activeBots.find { it.first.id == botId }?.first?.chatId
                } ?: metadataDao.getValue(com.tgstorage.data.local.entity.MetadataKeys.CHAT_ID)
                ?: continue

                try {
                    api.deleteMessage(token, chatId, messageId).getOrThrow()
                    deletedCount++
                } catch (e: Exception) {
                    // Message might already be deleted — continue with others
                    Log.w(TAG, "Failed to delete message $messageId: ${e.message}")
                    lastError = e
                    // Still count as "handled" if message was not found
                    if (e.message?.contains("message to delete not found", ignoreCase = true) == true ||
                        e.message?.contains("not found", ignoreCase = true) == true) {
                        deletedCount++
                    }
                }
            }

            // Clean up DB records
            chunkDao.deleteChunksForFile(fileId)
            syncStateDao.updateStatus(fileId, com.tgstorage.data.local.entity.SyncStatus.DELETED)

            // Also delete the file entity and its local file if present
            val fileEntity = fileDao.getFileById(fileId)
            if (fileEntity != null) {
                fileEntity.localUri?.let { path ->
                    val localFile = File(path)
                    if (localFile.exists()) localFile.delete()
                }
                fileDao.deleteFile(fileEntity)
            }

            Log.i(TAG, "Deleted file $fileId from Telegram: $deletedCount/${chunks.size} messages removed")
            Result.success(deletedCount)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file $fileId from Telegram", e)
            Result.failure(e)
        }
    }

    // ─── Cancel / Pause / Resume ───────────────────────

    fun cancelTransfer(fileId: Long, type: TransferType) {
        val key = if (type == TransferType.DOWNLOAD) -fileId else fileId
        activeJobs[key]?.cancel()
        activeJobs.remove(key)
        _transfers.update { list ->
            list.map {
                if (it.fileId == fileId && it.type == type) it.copy(status = TransferStatus.CANCELLED)
                else it
            }
        }
    }

    fun pauseTransfer(fileId: Long, type: TransferType) {
        val key = if (type == TransferType.DOWNLOAD) -fileId else fileId
        activeJobs[key]?.cancel()
        activeJobs.remove(key)
        _transfers.update { list ->
            list.map {
                if (it.fileId == fileId && it.type == type &&
                    (it.status == TransferStatus.IN_PROGRESS ||
                            it.status == TransferStatus.PENDING)
                ) it.copy(status = TransferStatus.PAUSED)
                else it
            }
        }
    }

    fun resumeTransfer(fileId: Long, type: TransferType) {
        if (type == TransferType.UPLOAD) {
            _transfers.update { list ->
                list.filter { !(it.fileId == fileId && it.type == TransferType.UPLOAD) }
            }
            val db = TgStorageApp.instance.database
            scope.launch {
                val fileEntity = db.fileDao().getFileById(fileId) ?: return@launch
                enqueueUpload(fileEntity)
            }
        }
    }

    fun retryTransfer(fileId: Long, type: TransferType) {
        _transfers.update { list -> list.filter { !(it.fileId == fileId && it.type == type) } }
        if (type == TransferType.UPLOAD) {
            val db = TgStorageApp.instance.database
            scope.launch {
                val fileEntity = db.fileDao().getFileById(fileId) ?: return@launch
                enqueueUpload(fileEntity)
            }
        }
    }

    fun retryAllFailed() {
        val failed = _transfers.value.filter { it.status == TransferStatus.FAILED }
        for (transfer in failed) retryTransfer(transfer.fileId, transfer.type)
    }

    fun pauseAll() {
        val active = _transfers.value.filter {
            it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.PENDING
        }
        for (transfer in active) pauseTransfer(transfer.fileId, transfer.type)
        scope.launch { queueMutex.withLock { uploadQueue.clear() } }
    }

    fun resumeAll() {
        val paused = _transfers.value.filter { it.status == TransferStatus.PAUSED }
        for (transfer in paused) resumeTransfer(transfer.fileId, transfer.type)
    }

    fun hasActiveToPause(): Boolean =
        _transfers.value.any {
            it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.PENDING
        }

    fun hasPausedToResume(): Boolean =
        _transfers.value.any { it.status == TransferStatus.PAUSED }

    fun clearFinished() {
        _transfers.update { list ->
            list.filter {
                it.status == TransferStatus.PENDING ||
                        it.status == TransferStatus.IN_PROGRESS ||
                        it.status == TransferStatus.PAUSED
            }
        }
    }

    fun hasActiveTransfers(): Boolean =
        _transfers.value.any {
            it.status == TransferStatus.PENDING || it.status == TransferStatus.IN_PROGRESS
        }

    // ─── Batched Upload API (memory-safe) ──────────────

    /**
     * Returns the number of active bots (for calculating batch size).
     */
    suspend fun getActiveBotCount(): Int {
        refreshActiveBots()
        return cachedActiveBots.size.coerceAtLeast(1)
    }

    /**
     * Calculate the optimal batch size (files per batch) based on:
     * 1. User setting (BATCH_SIZE_PER_BOT) — if set to > 0, use it directly
     * 2. Auto mode (value = 0 or not set) — detect device capability
     *
     * Device capability levels:
     * - Low RAM (< 3 GB):    1 file per bot
     * - Medium (3–6 GB):     2 files per bot
     * - High (6–8 GB):       3 files per bot
     * - Ultra (> 8 GB):      4 files per bot
     */
    suspend fun getOptimalBatchSize(): Int {
        val botCount = getActiveBotCount()
        val perBot = getUserBatchSizePerBot()
        return perBot * botCount
    }

    /**
     * Gets the user-configured files per bot, or auto-detects from device RAM.
     */
    suspend fun getUserBatchSizePerBot(): Int {
        val app = TgStorageApp.instance
        val metadataDao = app.database.metadataDao()
        val raw = metadataDao.getValue(MetadataKeys.BATCH_SIZE_PER_BOT)
        val userPref = raw?.toIntOrNull() ?: 0

        return if (userPref > 0) {
            userPref
        } else {
            detectDeviceBatchSize()
        }
    }

    /**
     * Auto-detect files per bot based on device RAM.
     */
    fun detectDeviceBatchSize(): Int {
        val app = TgStorageApp.instance
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

        return when {
            totalRamGb < 3.0 -> 1
            totalRamGb < 6.0 -> 2
            totalRamGb < 8.0 -> 3
            else -> 4
        }
    }

    /**
     * Suspends until ALL of the given file IDs have finished uploading
     * (COMPLETED, FAILED, or CANCELLED — anything that isn't active/pending).
     */
    suspend fun awaitBatchCompletion(fileIds: Set<Long>) {
        if (fileIds.isEmpty()) return
        // Poll _transfers until none of the fileIds are still in-progress/pending
        _transfers.collect { list ->
            val stillActive = list.any { tp ->
                tp.fileId in fileIds &&
                        tp.type == TransferType.UPLOAD &&
                        (tp.status == TransferStatus.IN_PROGRESS ||
                         tp.status == TransferStatus.PENDING)
            }
            if (!stillActive) {
                // All done — flow collect will stop via the caller's scope
                return@collect
            }
        }
    }

    /**
     * Force-remove all COMPLETED / FAILED / CANCELLED uploads from _transfers.
     * Frees memory for the next batch.
     */
    fun forceCleanCompletedTransfers() {
        _transfers.update { list ->
            list.filter {
                it.type == TransferType.DOWNLOAD ||
                        (it.status == TransferStatus.IN_PROGRESS ||
                         it.status == TransferStatus.PENDING ||
                         it.status == TransferStatus.PAUSED)
            }
        }
        // Also clear the upload queue defensively
        scope.launch {
            queueMutex.withLock {
                uploadQueue.clear()
            }
        }
        Log.i(TAG, "Force-cleaned completed transfers from memory")
    }

    /**
     * Remove ghost entries (0-byte / "unknown" name) from the in-memory transfer list.
     * Also removes them from the upload queue.
     */
    fun purgeGhostTransfers() {
        val removed = mutableListOf<String>()
        _transfers.update { list ->
            list.filter { transfer ->
                val isGhost = transfer.totalBytes <= 0L ||
                        transfer.fileName.isBlank() ||
                        transfer.fileName.equals("unknown", ignoreCase = true)
                if (isGhost) {
                    removed.add("'${transfer.fileName}' (${transfer.fileId})")
                    activeJobs[transfer.fileId]?.cancel()
                    activeJobs.remove(transfer.fileId)
                }
                !isGhost
            }
        }
        scope.launch {
            queueMutex.withLock {
                uploadQueue.removeAll { it.size <= 0L || it.name.isBlank() || it.name.equals("unknown", ignoreCase = true) }
            }
        }
        if (removed.isNotEmpty()) {
            Log.i(TAG, "Purged ${removed.size} ghost transfers: $removed")
        }
    }

    // ─── Stall Watchdog ────────────────────────────────

    /**
     * Tracks the last known transferred bytes for each active upload.
     * If bytes haven't changed for STALL_TIMEOUT_MS, the upload is considered stuck.
     */
    private val lastProgressSnapshot = mutableMapOf<Long, Pair<Long, Long>>() // fileId → (transferredBytes, timestampMs)
    private var watchdogJob: Job? = null
    private const val STALL_CHECK_INTERVAL_MS = 15_000L  // check every 15s
    private const val STALL_TIMEOUT_MS = 60_000L         // stuck if no progress for 60s
    private const val MAX_STALL_RETRIES = 3              // max retries before giving up
    private val stallRetryCount = mutableMapOf<Long, Int>() // fileId → retries

    /**
     * Start the stall watchdog. Call once at app startup.
     * Periodically checks all IN_PROGRESS uploads.
     * If any upload has made no byte progress for STALL_TIMEOUT_MS,
     * it cancels the job and re-enqueues the file.
     */
    fun startStallWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            Log.i(TAG, "Stall watchdog started")
            while (true) {
                delay(STALL_CHECK_INTERVAL_MS)
                checkForStalledUploads()
            }
        }
    }

    private suspend fun checkForStalledUploads() {
        val now = System.currentTimeMillis()
        val activeUploads = _transfers.value.filter {
            it.type == TransferType.UPLOAD && it.status == TransferStatus.IN_PROGRESS
        }

        for (transfer in activeUploads) {
            val fileId = transfer.fileId
            val currentBytes = transfer.bytesTransferred

            val existing = lastProgressSnapshot[fileId]
            if (existing == null) {
                // First time seeing this upload — record snapshot
                lastProgressSnapshot[fileId] = currentBytes to now
                continue
            }
            val (lastBytes, lastTime) = existing

            if (currentBytes > lastBytes) {
                // Progress was made — update snapshot, reset retry count
                lastProgressSnapshot[fileId] = currentBytes to now
                stallRetryCount.remove(fileId)
            } else if (now - lastTime > STALL_TIMEOUT_MS) {
                // No progress for STALL_TIMEOUT_MS — stalled!
                val retries = stallRetryCount.getOrDefault(fileId, 0)
                if (retries >= MAX_STALL_RETRIES) {
                    Log.e(TAG, "Upload '${transfer.fileName}' stalled $MAX_STALL_RETRIES times — marking FAILED")
                    activeJobs[fileId]?.cancel()
                    _transfers.update { list ->
                        list.map {
                            if (it.fileId == fileId && it.type == TransferType.UPLOAD) {
                                it.copy(
                                    status = TransferStatus.FAILED,
                                    error = "Upload stalled after $MAX_STALL_RETRIES retries",
                                )
                            } else it
                        }
                    }
                    lastProgressSnapshot.remove(fileId)
                    stallRetryCount.remove(fileId)
                } else {
                    Log.w(TAG, "Upload '${transfer.fileName}' stalled (${retries + 1}/$MAX_STALL_RETRIES) — restarting")
                    stallRetryCount[fileId] = retries + 1
                    restartStalledUpload(fileId)
                }
            }
        }

        // Clean up snapshots for uploads that are no longer active
        val activeIds = activeUploads.map { it.fileId }.toSet()
        lastProgressSnapshot.keys.removeAll { it !in activeIds }
        stallRetryCount.keys.removeAll { it !in activeIds }
    }

    private suspend fun restartStalledUpload(fileId: Long) {
        // Cancel the stuck job
        activeJobs[fileId]?.cancel()
        activeJobs.remove(fileId)

        // Clean up temp files from the failed attempt
        cleanUploadCache(fileId)

        // Get the file entity from DB to re-enqueue
        val db = TgStorageApp.instance.database
        val fileEntity = db.fileDao().getFileById(fileId)

        if (fileEntity != null) {
            // Remove old transfer entry
            _transfers.update { list ->
                list.filter { !(it.fileId == fileId && it.type == TransferType.UPLOAD) }
            }

            // Small delay before re-enqueue
            delay(1_000)

            // Re-enqueue — this creates a fresh transfer entry + triggers processNextBatch
            Log.i(TAG, "Re-enqueueing stalled upload: '${fileEntity.name}'")
            enqueueUpload(fileEntity)
        } else {
            Log.e(TAG, "Cannot restart stalled upload $fileId — file not found in DB")
            _transfers.update { list ->
                list.map {
                    if (it.fileId == fileId && it.type == TransferType.UPLOAD) {
                        it.copy(status = TransferStatus.FAILED, error = "File not found in database")
                    } else it
                }
            }
        }

        // Reset the progress snapshot for fresh tracking
        lastProgressSnapshot.remove(fileId)
    }

    // ─── Resume Pending Uploads ────────────────────────

    /**
     * Scans the DB for files with pending_upload sync state and re-enqueues them.
     * Call this at app startup and periodically to ensure nothing stays stuck.
     */
    fun resumePendingUploads() {
        scope.launch {
            try {
                val db = TgStorageApp.instance.database
                val pendingStates = db.syncStateDao().getByStatusSync(
                    com.tgstorage.data.local.entity.SyncStatus.PENDING_UPLOAD
                )

                if (pendingStates.isEmpty()) {
                    Log.d(TAG, "No pending uploads to resume")
                    return@launch
                }

                Log.i(TAG, "Resuming ${pendingStates.size} pending uploads from DB")

                for (syncState in pendingStates) {
                    val fileEntity = db.fileDao().getFileById(syncState.fileId) ?: continue
                    // Only enqueue if not already active/queued
                    val alreadyTracked = _transfers.value.any {
                        it.fileId == fileEntity.id && it.type == TransferType.UPLOAD &&
                                (it.status == TransferStatus.IN_PROGRESS ||
                                 it.status == TransferStatus.PENDING)
                    }
                    val alreadyQueued = queueMutex.withLock {
                        uploadQueue.any { it.id == fileEntity.id }
                    }
                    if (!alreadyTracked && !alreadyQueued && !activeJobs.containsKey(fileEntity.id)) {
                        enqueueUpload(fileEntity)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume pending uploads", e)
            }
        }
    }

    // ─── Auto-backup after N uploads ──────────────────

    private suspend fun checkAutoBackupAfterUpload() {
        try {
            val app = TgStorageApp.instance
            val db = app.database
            val metadataDao = db.metadataDao()

            val currentCount = (metadataDao.getValue(
                com.tgstorage.data.local.entity.MetadataKeys.UPLOADS_SINCE_BACKUP
            )?.toIntOrNull() ?: 0) + 1

            val threshold = metadataDao.getValue(
                com.tgstorage.data.local.entity.MetadataKeys.AUTO_BACKUP_THRESHOLD
            )?.toIntOrNull() ?: 10

            if (currentCount >= threshold) {
                metadataDao.setValue(
                    com.tgstorage.data.local.entity.MetadataEntity(
                        key = com.tgstorage.data.local.entity.MetadataKeys.UPLOADS_SINCE_BACKUP,
                        value = "0"
                    )
                )
                Log.i(TAG, "Auto-backup triggered after $currentCount uploads")
                val backupManager = com.tgstorage.data.sync.BackupManager(app)
                backupManager.createAndUploadBackup()
            } else {
                metadataDao.setValue(
                    com.tgstorage.data.local.entity.MetadataEntity(
                        key = com.tgstorage.data.local.entity.MetadataKeys.UPLOADS_SINCE_BACKUP,
                        value = currentCount.toString()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-backup check failed", e)
        }
    }
}
