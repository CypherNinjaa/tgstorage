package com.tgstorage.data.transfer

import com.tgstorage.TgStorageApp
import com.tgstorage.data.local.entity.FileEntity
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

/**
 * Singleton that coordinates all active uploads/downloads.
 * Exposes a queue of [TransferProgress] items observable by the UI.
 */
object TransferManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<Long, Job>() // fileId → job

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

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

    fun enqueueUpload(file: FileEntity) {
        if (activeJobs.containsKey(file.id)) return // already in queue

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

        // Add to transfer list
        _transfers.update { current -> current + progressFlow.value }

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

            val localFile = file.localUri?.let { File(it) }
            if (localFile == null || !localFile.exists()) {
                progressFlow.value = progressFlow.value.copy(
                    status = TransferStatus.FAILED,
                    error = "Local file not found",
                )
                return@launch
            }

            getChunkManager().uploadFile(
                token = token,
                chatId = chatId,
                fileId = file.id,
                localFile = localFile,
                fileName = file.name,
                progressFlow = progressFlow,
            )
        }

        activeJobs[file.id] = job

        job.invokeOnCompletion {
            activeJobs.remove(file.id)
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
