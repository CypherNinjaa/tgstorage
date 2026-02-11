package com.tgstorage.data.transfer

import android.net.Uri
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.remote.TelegramApiService
import com.tgstorage.data.remote.TokenValidator
import com.tgstorage.data.repository.BotRepository
import com.tgstorage.data.repository.TelegramRepository
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
import java.io.File
import java.io.FileOutputStream

/**
 * Singleton that coordinates all active uploads/downloads.
 * Exposes a queue of [TransferProgress] items observable by the UI.
 * 
 * UPLOAD LIMITING: Only 10 uploads run concurrently at a time.
 * When one completes, the next pending upload is automatically started.
 * 
 * MULTI-BOT SUPPORT: When multiple bots are configured, uploads are
 * distributed across bots for faster parallel transfers.
 */
object TransferManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<Long, Job>() // fileId → job

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

    // Pending files waiting to be uploaded (queue)
    private val pendingUploadQueue = mutableListOf<FileEntity>()
    private val queueLock = Any()

    // Maximum concurrent uploads (total across all bots)
    private const val MAX_CONCURRENT_UPLOADS = 20
    
    // Track bot assignments: botId -> set of fileIds currently being uploaded by that bot
    private val botFileAssignments = mutableMapOf<Long, MutableSet<Long>>()
    private val assignmentLock = Any()
    
    // Cache of active bots (refreshed periodically)
    @Volatile private var cachedActiveBots: List<Pair<com.tgstorage.data.local.entity.BotEntity, String>> = emptyList()
    @Volatile private var lastBotRefresh = 0L
    private const val BOT_CACHE_TTL = 5000L // 5 seconds

    private fun getChunkManager(): ChunkManager {
        val app = TgStorageApp.instance
        val db = app.database
        return ChunkManager(
            context = app,
            api = com.tgstorage.data.remote.TelegramApiService(),
            chunkDao = db.chunkDao(),
            syncStateDao = db.syncStateDao(),
            fileDao = db.fileDao(),
            metadataDao = db.metadataDao(),
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

    // ─── Bot Assignment for File Distribution ──────────

    /**
     * Refresh the cached list of active bots.
     */
    private suspend fun refreshActiveBots() {
        val now = System.currentTimeMillis()
        if (now - lastBotRefresh > BOT_CACHE_TTL || cachedActiveBots.isEmpty()) {
            cachedActiveBots = getBotRepository().getActiveBotsWithTokens()
            lastBotRefresh = now
        }
    }

    /**
     * Find the bot with the fewest active file assignments (load balancing).
     * Returns the bot and token, or null if no bots available.
     */
    private fun getLeastLoadedBot(): Pair<com.tgstorage.data.local.entity.BotEntity, String>? {
        if (cachedActiveBots.isEmpty()) return null
        
        synchronized(assignmentLock) {
            return cachedActiveBots.minByOrNull { (bot, _) ->
                botFileAssignments[bot.id]?.size ?: 0
            }
        }
    }

    /**
     * Assign a file to a bot for upload.
     */
    private fun assignFileToBot(fileId: Long, botId: Long) {
        synchronized(assignmentLock) {
            botFileAssignments.getOrPut(botId) { mutableSetOf() }.add(fileId)
        }
    }

    /**
     * Remove a file assignment from a bot (when upload completes).
     */
    private fun unassignFileFromBot(fileId: Long, botId: Long) {
        synchronized(assignmentLock) {
            botFileAssignments[botId]?.remove(fileId)
        }
    }

    /**
     * Get current upload count for a specific bot.
     */
    private fun getBotUploadCount(botId: Long): Int {
        synchronized(assignmentLock) {
            return botFileAssignments[botId]?.size ?: 0
        }
    }

    // ─── Upload ────────────────────────────────────────

    /**
     * Count of currently running upload jobs (IN_PROGRESS status)
     */
    private fun getActiveUploadCount(): Int {
        return _transfers.value.count { 
            it.type == TransferType.UPLOAD && it.status == TransferStatus.IN_PROGRESS 
        }
    }

    /**
     * Enqueue a file for upload. If we're already at max concurrent uploads,
     * the file is added to a pending queue and will start automatically 
     * when another upload completes.
     */
    fun enqueueUpload(file: FileEntity) {
        if (activeJobs.containsKey(file.id)) return // already in queue
        
        // Check if already in pending queue
        synchronized(queueLock) {
            if (pendingUploadQueue.any { it.id == file.id }) return
        }

        // Check if we're at the concurrent upload limit
        if (getActiveUploadCount() >= MAX_CONCURRENT_UPLOADS) {
            // Add to pending queue instead of starting immediately
            synchronized(queueLock) {
                if (!pendingUploadQueue.any { it.id == file.id }) {
                    pendingUploadQueue.add(file)
                }
            }
            // Add to transfer list as PENDING (queued)
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
            return
        }

        // Start the upload immediately
        startUploadJob(file)
    }

    /**
     * Actually start an upload job for a file.
     * Files are distributed across bots:
     * - Small files: assigned to least-loaded bot for file-level parallelism
     * - Large files: chunks distributed across all bots for chunk-level parallelism
     */
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

        // Add or update transfer list entry
        _transfers.update { current ->
            val existing = current.find { it.fileId == file.id && it.type == TransferType.UPLOAD }
            if (existing != null) {
                current.map { if (it.fileId == file.id && it.type == TransferType.UPLOAD) progressFlow.value else it }
            } else {
                current + progressFlow.value
            }
        }

        // Track which bot this file is assigned to (for cleanup)
        var assignedBotId: Long? = null

        // Observe individual progress and update the list
        val job = scope.launch {
            launch {
                progressFlow.collect { progress ->
                    _transfers.update { list ->
                        list.map { if (it.fileId == file.id && it.type == TransferType.UPLOAD) progress else it }
                    }
                }
            }

            // Refresh active bots and get assignment
            refreshActiveBots()
            val activeBots = cachedActiveBots

            // Resolve the file to upload - handle both content:// URIs and file paths
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
                val isLargeFile = fileSize > 10L * 1024 * 1024 // > 10 MB
                
                when {
                    activeBots.size >= 2 && isLargeFile -> {
                        // Large file with multiple bots: distribute chunks across ALL bots
                        getMultiBotUploadManager().uploadFileWithMultipleBots(
                            file = file,
                            localFile = uploadFile,
                            progressFlow = progressFlow,
                        )
                    }
                    activeBots.size >= 2 -> {
                        // Small file with multiple bots: assign to LEAST LOADED bot (file-level parallelism)
                        val assignedBot = getLeastLoadedBot()
                        if (assignedBot != null) {
                            val (bot, token) = assignedBot
                            assignedBotId = bot.id
                            assignFileToBot(file.id, bot.id)
                            
                            getMultiBotUploadManager().uploadFileWithSpecificBot(
                                bot = bot,
                                token = token,
                                file = file,
                                localFile = uploadFile,
                                progressFlow = progressFlow,
                            )
                        } else {
                            // Fallback to first bot
                            val (bot, token) = activeBots.first()
                            assignedBotId = bot.id
                            assignFileToBot(file.id, bot.id)
                            
                            getMultiBotUploadManager().uploadFileWithSpecificBot(
                                bot = bot,
                                token = token,
                                file = file,
                                localFile = uploadFile,
                                progressFlow = progressFlow,
                            )
                        }
                    }
                    activeBots.size == 1 -> {
                        // Single bot: use it directly
                        val (bot, token) = activeBots.first()
                        assignedBotId = bot.id
                        assignFileToBot(file.id, bot.id)
                        
                        getMultiBotUploadManager().uploadFileWithSpecificBot(
                            bot = bot,
                            token = token,
                            file = file,
                            localFile = uploadFile,
                            progressFlow = progressFlow,
                        )
                    }
                    else -> {
                        // No bots from new system, fall back to legacy single-bot
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

                        // Validate token before upload
                        val db = TgStorageApp.instance.database
                        val tokenValid = TokenValidator.validateToken(
                            api = TelegramApiService(),
                            token = token,
                            metadataDao = db.metadataDao(),
                        )
                        if (!tokenValid) {
                            val errorMsg = when (TokenValidator.tokenStatus.value) {
                                is TokenValidator.TokenStatus.Revoked ->
                                    "Bot token has been revoked. Please update it in Settings."
                                is TokenValidator.TokenStatus.RateLimited ->
                                    "Telegram rate limit reached. Please try again later."
                                is TokenValidator.TokenStatus.NetworkError ->
                                    "No internet connection."
                                else -> "Token validation failed."
                            }
                            progressFlow.value = progressFlow.value.copy(
                                status = TransferStatus.FAILED,
                                error = errorMsg,
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
                // CRITICAL: Always clean up temp file after upload (success or failure)
                tempFile?.delete()
            }
        }

        activeJobs[file.id] = job

        job.invokeOnCompletion {
            activeJobs.remove(file.id)
            
            // Unassign file from bot
            assignedBotId?.let { botId ->
                unassignFileFromBot(file.id, botId)
            }
            
            // When an upload completes, start MULTIPLE pending uploads (one per available bot slot)
            processNextPendingUploads()
            
            // Auto-remove completed uploads after brief delay to free memory
            val finalStatus = progressFlow.value.status
            if (finalStatus == TransferStatus.COMPLETED) {
                scope.launch {
                    delay(2000) // Let user see "Done" status briefly
                    removeTransfer(file.id, TransferType.UPLOAD)
                }
            }
        }
    }
    
    /**
     * Remove a single transfer from the list (used for auto-cleanup of completed uploads)
     */
    private fun removeTransfer(fileId: Long, type: TransferType) {
        _transfers.update { list ->
            list.filter { !(it.fileId == fileId && it.type == type) }
        }
    }
    
    /**
     * Resolves a FileEntity's localUri to an actual File for upload.
     * - If it's a file path and exists, returns it directly (no temp file)
     * - If it's a content:// URI, creates a temp copy and returns it
     * 
     * @return Pair of (fileToUpload, tempFileToDelete) - tempFile is null if no cleanup needed
     */
    private fun resolveFileForUpload(file: FileEntity): Pair<File?, File?> {
        val uriString = file.localUri ?: return Pair(null, null)
        
        // Check if it's a direct file path (not a content:// URI)
        if (!uriString.startsWith("content://")) {
            val directFile = File(uriString)
            return if (directFile.exists()) Pair(directFile, null) else Pair(null, null)
        }
        
        // It's a content:// URI - create a temp copy
        val app = TgStorageApp.instance
        val uri = Uri.parse(uriString)
        
        val tempDir = File(app.cacheDir, "upload_temp")
        if (!tempDir.exists()) tempDir.mkdirs()
        
        val tempFile = File(tempDir, "${file.id}_${System.currentTimeMillis()}_${file.name}")
        
        return try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                Pair(tempFile, tempFile)
            } else {
                tempFile.delete()
                Pair(null, null)
            }
        } catch (e: Exception) {
            tempFile.delete()
            Pair(null, null)
        }
    }

    /**
     * Process multiple pending uploads from the queue.
     * Starts one file per available bot slot for maximum parallelism.
     */
    private fun processNextPendingUploads() {
        // Calculate how many slots are available
        val availableSlots = MAX_CONCURRENT_UPLOADS - getActiveUploadCount()
        if (availableSlots <= 0) return
        
        // Get files to start (up to available slots)
        val filesToStart: List<FileEntity> = synchronized(queueLock) {
            val count = minOf(availableSlots, pendingUploadQueue.size)
            if (count == 0) return@synchronized emptyList()
            
            val files = pendingUploadQueue.take(count)
            repeat(count) { pendingUploadQueue.removeAt(0) }
            files
        }
        
        // Start all files in parallel
        filesToStart.forEach { file ->
            scope.launch {
                startUploadJob(file)
            }
        }
    }

    // ─── Download ──────────────────────────────────────

    // Track bot assignments for downloads (file-level distribution)
    private val downloadBotAssignments = mutableMapOf<Long, MutableSet<Long>>()
    private val downloadAssignmentLock = Any()

    private fun assignDownloadToBot(fileId: Long, botId: Long) {
        synchronized(downloadAssignmentLock) {
            downloadBotAssignments.getOrPut(botId) { mutableSetOf() }.add(fileId)
        }
    }

    private fun unassignDownloadFromBot(fileId: Long, botId: Long) {
        synchronized(downloadAssignmentLock) {
            downloadBotAssignments[botId]?.remove(fileId)
        }
    }

    private fun getLeastLoadedBotForDownload(): Pair<com.tgstorage.data.local.entity.BotEntity, String>? {
        val bots = cachedActiveBots
        if (bots.isEmpty()) return null
        
        synchronized(downloadAssignmentLock) {
            return bots.minByOrNull { (bot, _) ->
                downloadBotAssignments[bot.id]?.size ?: 0
            }
        }
    }

    fun enqueueDownload(file: FileEntity, outputFile: File) {
        val downloadKey = -file.id // negative key to distinguish from upload
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

        // Track which bot this download is assigned to
        var assignedBotId: Long? = null

        val job = scope.launch {
            launch {
                progressFlow.collect { progress ->
                    _transfers.update { list ->
                        list.map {
                            if (it.fileId == file.id && it.type == TransferType.DOWNLOAD) progress else it
                        }
                    }
                }
            }

            // Refresh bot cache and check for multi-bot config
            refreshActiveBots()
            val activeBots = cachedActiveBots

            // Get chunks to determine if file has multiple chunks
            val db = TgStorageApp.instance.database
            val chunks = db.chunkDao().getChunksForFileSync(file.id)
            val hasMultipleChunks = chunks.size > 1

            when {
                activeBots.size >= 2 && hasMultipleChunks -> {
                    // Multiple chunks + multiple bots: distribute chunks across ALL bots for speed
                    getMultiBotUploadManager().downloadFileWithMultipleBots(
                        fileId = file.id,
                        fileName = file.name,
                        outputFile = outputFile,
                        progressFlow = progressFlow,
                    )
                }
                activeBots.size >= 2 -> {
                    // Single chunk + multiple bots: assign to least-loaded bot (file-level parallelism)
                    val assignedBot = getLeastLoadedBotForDownload()
                    if (assignedBot != null) {
                        val (bot, token) = assignedBot
                        assignedBotId = bot.id
                        assignDownloadToBot(file.id, bot.id)
                        
                        getMultiBotUploadManager().downloadFileWithSpecificBot(
                            bot = bot,
                            token = token,
                            fileId = file.id,
                            fileName = file.name,
                            outputFile = outputFile,
                            progressFlow = progressFlow,
                        )
                    } else {
                        // Fallback to first bot
                        val (bot, token) = activeBots.first()
                        assignedBotId = bot.id
                        assignDownloadToBot(file.id, bot.id)
                        
                        getMultiBotUploadManager().downloadFileWithSpecificBot(
                            bot = bot,
                            token = token,
                            fileId = file.id,
                            fileName = file.name,
                            outputFile = outputFile,
                            progressFlow = progressFlow,
                        )
                    }
                }
                activeBots.size == 1 -> {
                    // Single bot from new system
                    val (bot, token) = activeBots.first()
                    assignedBotId = bot.id
                    assignDownloadToBot(file.id, bot.id)
                    
                    getMultiBotUploadManager().downloadFileWithSpecificBot(
                        bot = bot,
                        token = token,
                        fileId = file.id,
                        fileName = file.name,
                        outputFile = outputFile,
                        progressFlow = progressFlow,
                    )
                }
                else -> {
                    // Fallback to legacy single-bot download
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
        }

        activeJobs[downloadKey] = job
        job.invokeOnCompletion { 
            activeJobs.remove(downloadKey)
            
            // Unassign download from bot
            assignedBotId?.let { botId ->
                unassignDownloadFromBot(file.id, botId)
            }
            
            // Auto-remove completed downloads after brief delay to free memory
            val finalStatus = progressFlow.value.status
            if (finalStatus == TransferStatus.COMPLETED) {
                scope.launch {
                    delay(2000) // Let user see "Done" status briefly
                    removeTransfer(file.id, TransferType.DOWNLOAD)
                }
            }
        }
    }

    // ─── Cancel ────────────────────────────────────────

    fun cancelTransfer(fileId: Long, type: TransferType) {
        val key = if (type == TransferType.DOWNLOAD) -fileId else fileId
        activeJobs[key]?.cancel()
        activeJobs.remove(key)

        _transfers.update { list ->
            list.map {
                if (it.fileId == fileId && it.type == type) {
                    it.copy(status = TransferStatus.CANCELLED)
                } else it
            }
        }
    }

    // ─── Pause ─────────────────────────────────────────

    fun pauseTransfer(fileId: Long, type: TransferType) {
        val key = if (type == TransferType.DOWNLOAD) -fileId else fileId
        activeJobs[key]?.cancel()
        activeJobs.remove(key)

        _transfers.update { list ->
            list.map {
                if (it.fileId == fileId && it.type == type &&
                    (it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.PENDING)
                ) {
                    it.copy(status = TransferStatus.PAUSED)
                } else it
            }
        }
    }

    // ─── Resume ────────────────────────────────────────

    fun resumeTransfer(fileId: Long, type: TransferType) {
        if (type == TransferType.UPLOAD) {
            // Re-enqueue the upload
            val transfer = _transfers.value.find { it.fileId == fileId && it.type == TransferType.UPLOAD }
                ?: return
            // Remove old entry so it can be re-added
            _transfers.update { list -> list.filter { !(it.fileId == fileId && it.type == TransferType.UPLOAD) } }

            val db = TgStorageApp.instance.database
            scope.launch {
                val fileEntity = db.fileDao().getFileById(fileId) ?: return@launch
                enqueueUpload(fileEntity)
            }
        }
    }

    // ─── Retry failed ──────────────────────────────────

    fun retryTransfer(fileId: Long, type: TransferType) {
        // Remove old failed entry
        _transfers.update { list -> list.filter { !(it.fileId == fileId && it.type == type) } }

        if (type == TransferType.UPLOAD) {
            val db = TgStorageApp.instance.database
            scope.launch {
                val fileEntity = db.fileDao().getFileById(fileId) ?: return@launch
                enqueueUpload(fileEntity)
            }
        }
    }

    // ─── Auto retry all failed ─────────────────────────

    fun retryAllFailed() {
        val failed = _transfers.value.filter { it.status == TransferStatus.FAILED }
        for (transfer in failed) {
            retryTransfer(transfer.fileId, transfer.type)
        }
    }

    // ─── Pause all active transfers ────────────────────

    fun pauseAll() {
        val active = _transfers.value.filter {
            it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.PENDING
        }
        for (transfer in active) {
            pauseTransfer(transfer.fileId, transfer.type)
        }
        // Also clear pending queue (they will become PAUSED in transfer list)
        synchronized(queueLock) {
            pendingUploadQueue.clear()
        }
    }

    // ─── Resume all paused transfers ───────────────────

    fun resumeAll() {
        val paused = _transfers.value.filter { it.status == TransferStatus.PAUSED }
        for (transfer in paused) {
            resumeTransfer(transfer.fileId, transfer.type)
        }
    }

    // ─── Check if any transfers can be paused ──────────

    fun hasActiveToPause(): Boolean =
        _transfers.value.any {
            it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.PENDING
        }

    // ─── Check if any transfers can be resumed ─────────

    fun hasPausedToResume(): Boolean =
        _transfers.value.any { it.status == TransferStatus.PAUSED }

    // ─── Clear completed/failed from queue ─────────────

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
}
