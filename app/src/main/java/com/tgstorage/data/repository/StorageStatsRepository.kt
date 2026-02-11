package com.tgstorage.data.repository

import android.content.Context
import com.tgstorage.data.local.dao.ChunkDao
import com.tgstorage.data.local.dao.FileDao
import com.tgstorage.data.local.dao.FileTypeStats
import com.tgstorage.data.local.entity.FileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class StorageOverview(
    val totalFiles: Int,
    val totalChunks: Int,
    val totalSize: Long,
    val uploadedSize: Long,
    val localCacheSize: Long,
    val telegramChunkSize: Long,
    val typeBreakdown: List<FileTypeStats>,
    val largestFiles: List<FileEntity>,
)

class StorageStatsRepository(
    private val context: Context,
    private val fileDao: FileDao,
    private val chunkDao: ChunkDao,
) {
    fun observeTotalFiles(): Flow<Int> = fileDao.getFileCount()

    fun observeTotalChunks(): Flow<Int> = chunkDao.getTotalChunkCount()

    fun observeTotalSize(): Flow<Long> = fileDao.getTotalSize()

    fun observeUploadedSize(): Flow<Long> = fileDao.getUploadedTotalSize()

    fun observeChunkSize(): Flow<Long> = chunkDao.getTotalChunkSize()

    suspend fun getTypeBreakdown(): List<FileTypeStats> =
        fileDao.getFileTypeBreakdown()

    suspend fun getLargestFiles(): List<FileEntity> =
        fileDao.getLargestFiles()

    suspend fun getLocalCacheSize(): Long = withContext(Dispatchers.IO) {
        dirSize(context.cacheDir) + dirSize(File(context.filesDir, "imported"))
    }

    suspend fun getFullOverview(): StorageOverview = withContext(Dispatchers.IO) {
        StorageOverview(
            totalFiles = 0, // populated reactively
            totalChunks = 0,
            totalSize = 0,
            uploadedSize = 0,
            localCacheSize = getLocalCacheSize(),
            telegramChunkSize = 0,
            typeBreakdown = getTypeBreakdown(),
            largestFiles = getLargestFiles(),
        )
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}
