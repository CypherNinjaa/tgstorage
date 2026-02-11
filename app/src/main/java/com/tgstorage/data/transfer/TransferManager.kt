package com.tgstorage.data.transfer

import android.net.Uri
import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.remote.TokenValidator
import com.tgstorage.data.repository.TelegramRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 */
object TransferManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<Long, Job>() // fileId → job

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

    // Pending files waiting to be uploaded (queue)
    private val pendingUploadQueue = mutableListOf<FileEntity>()
    private val queueLock = Any()

    // Maximum concurrent uploads
    private const val MAX_CONCURRENT_UPLOADS = 10

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
            api = com.tgstorage.data.remote.TelegramApiService(),
            metadataDao = db.metadataDao(),
        )
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

        // Observe individual progress and update the list
        val job = scope.launch {
            launch {
                progressFlow.collect { progress ->
                    _transfers.update { list ->
                        list.map { if (it.fileId == file.id && it.type == TransferType.UPLOAD) progress else it }
                    }
                }
            }

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

            // Phase 9: Validate token before upload
            val db = TgStorageApp.instance.database
            val tokenValid = TokenValidator.validateToken(
                api = com.tgstorage.data.remote.TelegramApiService(),
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
                getChunkManager().uploadFile(
                    token = token,
                    chatId = chatId,
                    fileId = file.id,
                    localFile = uploadFile,
                    fileName = file.name,
                    progressFlow = progressFlow,
                )
            } finally {
                // CRITICAL: Always clean up temp file after upload (success or failure)
                tempFile?.delete()
            }
        }

        activeJobs[file.id] = job

        job.invokeOnCompletion {
            activeJobs.remove(file.id)
            // When an upload completes, start the next pending upload
            processNextPendingUpload()
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
     * Process the next pending upload from the queue if we're below the limit.
     */
    private fun processNextPendingUpload() {
        val nextFile: FileEntity? = synchronized(queueLock) {
            if (pendingUploadQueue.isNotEmpty() && getActiveUploadCount() < MAX_CONCURRENT_UPLOADS) {
                pendingUploadQueue.removeAt(0)
            } else null
        }
        
        nextFile?.let { file ->
            scope.launch {
                startUploadJob(file)
            }
        }
    }

    // ─── Download ──────────────────────────────────────

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

        activeJobs[downloadKey] = job
        job.invokeOnCompletion { activeJobs.remove(downloadKey) }
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
