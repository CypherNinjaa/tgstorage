package com.tgstorage.ui.transfers

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tgstorage.data.local.dao.UploadedFileInfo
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.transfer.TransferProgress
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import com.tgstorage.ui.components.EmptyState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferQueueScreen(
    viewModel: TransferQueueViewModel = viewModel(factory = TransferQueueViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                actions = {
                    if (state.selectedTab == 0 && state.transfers.any { !viewModel.isActive(it) }) {
                        IconButton(onClick = viewModel::clearFinished) {
                            Icon(Icons.Filled.DeleteSweep, "Clear finished")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // ── Tab bar ─────────────────────────────────
            TabRow(selectedTabIndex = state.selectedTab) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = {
                        val count = state.transfers.size
                        Text(if (count > 0) "Transfers ($count)" else "Transfers")
                    },
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = {
                        val count = state.uploadedFiles.size
                        Text(if (count > 0) "Uploaded ($count)" else "Uploaded")
                    },
                )
            }

            // ── Tab content ─────────────────────────────
            when (state.selectedTab) {
                0 -> TransfersTab(
                    transfers = state.transfers,
                    isActive = viewModel::isActive,
                    onCancel = { viewModel.cancelTransfer(it.fileId, it.type) },
                )
                1 -> UploadedTab(
                    files = state.uploadedFiles,
                    downloadingIds = state.downloadingIds,
                    onDownload = viewModel::enqueueDownload,
                )
            }
        }
    }
}

// ─── Transfers tab ─────────────────────────────────────

@Composable
private fun TransfersTab(
    transfers: List<TransferProgress>,
    isActive: (TransferProgress) -> Boolean,
    onCancel: (TransferProgress) -> Unit,
) {
    if (transfers.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.SwapVert,
            title = "No transfers",
            subtitle = "Your uploads and downloads will appear here",
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        val active = transfers.filter { isActive(it) }
        val finished = transfers.filter { !isActive(it) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Global progress summary ────────────────
            if (active.isNotEmpty()) {
                item(key = "global_progress") {
                    GlobalProgressCard(activeTransfers = active)
                }
            }

            if (active.isNotEmpty()) {
                item {
                    Text("Active", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
                items(active, key = { "${it.fileId}_${it.type}" }) { transfer ->
                    TransferCard(progress = transfer, onCancel = { onCancel(transfer) })
                }
            }

            if (finished.isNotEmpty()) {
                item {
                    Text("Completed", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                }
                items(finished, key = { "${it.fileId}_${it.type}_done" }) { transfer ->
                    TransferCard(progress = transfer, onCancel = null)
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ─── Global progress card ──────────────────────────────

@Composable
private fun GlobalProgressCard(activeTransfers: List<TransferProgress>) {
    val totalBytes = activeTransfers.sumOf { it.totalBytes }
    val transferred = activeTransfers.sumOf { it.bytesTransferred }
    val fraction = if (totalBytes > 0) transferred.toFloat() / totalBytes else 0f
    val percent = (fraction * 100).toInt()
    val completedCount = activeTransfers.count { it.status == TransferStatus.IN_PROGRESS && it.bytesTransferred >= it.totalBytes && it.totalBytes > 0 }
    val uploadCount = activeTransfers.count { it.type == TransferType.UPLOAD }
    val downloadCount = activeTransfers.count { it.type == TransferType.DOWNLOAD }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CloudUpload, null, Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        buildString {
                            if (uploadCount > 0) append("Uploading $uploadCount file(s)")
                            if (downloadCount > 0) {
                                if (uploadCount > 0) append(" • ")
                                append("Downloading $downloadCount")
                            }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "${formatSize(transferred)} / ${formatSize(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                // Big percentage
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Progress bar
            Box(
                modifier = Modifier.fillMaxWidth().height(8.dp)
                    .background(
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                        RoundedCornerShape(4.dp),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(
                            MaterialTheme.colorScheme.onPrimaryContainer,
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
        }
    }
}

// ─── Uploaded tab ──────────────────────────────────────

@Composable
private fun UploadedTab(
    files: List<UploadedFileInfo>,
    downloadingIds: Set<Long>,
    onDownload: (UploadedFileInfo) -> Unit,
) {
    if (files.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.CloudDone,
            title = "No uploaded files",
            subtitle = "Files you upload to Telegram will appear here",
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // Group by date
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val grouped = files.groupBy { file ->
            val ts = file.uploadedAt ?: file.updatedAt
            dateFormat.format(Date(ts))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("${files.size} file(s) in Telegram cloud",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            grouped.forEach { (date, group) ->
                item {
                    Text(date, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
                items(group, key = { it.id }) { file ->
                    UploadedFileCard(
                        file = file,
                        isDownloading = file.id in downloadingIds,
                        onDownload = { onDownload(file) },
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun UploadedFileCard(
    file: UploadedFileInfo,
    isDownloading: Boolean,
    onDownload: () -> Unit,
) {
    val isImage = file.mimeType.startsWith("image/")
    val isVideo = file.mimeType.startsWith("video/")
    val hasLocalThumb = file.localUri != null && (isImage || isVideo)
    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val uploadTime = timeFormat.format(Date(file.uploadedAt ?: file.updatedAt))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail / icon
            Box(
                modifier = Modifier.size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (hasLocalThumb) {
                    AsyncImage(
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(File(file.localUri!!))
                            .size(128)
                            .crossfade(true)
                            .build(),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(
                        fileMimeIcon(file.mimeType), null, Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // File size
                    Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" • ", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Upload time
                    Text(uploadTime, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                // Mime type badge
                Text(
                    file.mimeType.substringAfter("/").uppercase().take(8),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Download button or loading indicator
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, "Download",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun fileMimeIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Filled.Image
    mimeType.startsWith("video/") -> Icons.Filled.VideoFile
    mimeType.startsWith("audio/") -> Icons.Filled.AudioFile
    mimeType.startsWith("application/pdf") || mimeType.startsWith("text/") -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

// ─── Transfer card ─────────────────────────────────────

@Composable
private fun TransferCard(
    progress: TransferProgress,
    onCancel: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (progress.type == TransferType.UPLOAD) Icons.Filled.CloudUpload
                    else Icons.Filled.CloudDownload,
                    null, Modifier.size(24.dp), MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(progress.fileName, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${progress.type.name.lowercase().replaceFirstChar { it.uppercase() }} • ${formatSize(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(progress.status)
            }

            if (progress.status == TransferStatus.IN_PROGRESS || progress.status == TransferStatus.PENDING) {
                Spacer(Modifier.height(12.dp))
                val fraction = if (progress.totalBytes > 0)
                    progress.bytesTransferred.toFloat() / progress.totalBytes else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Chunk ${progress.currentChunk}/${progress.totalChunks}",
                        style = MaterialTheme.typography.labelSmall)
                    Text("${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            if (progress.status == TransferStatus.FAILED && progress.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(progress.error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            if (onCancel != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel) {
                    Icon(Icons.Filled.Cancel, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: TransferStatus) {
    val (label, color) = when (status) {
        TransferStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.outline
        TransferStatus.IN_PROGRESS -> "In Progress" to MaterialTheme.colorScheme.primary
        TransferStatus.PAUSED -> "Paused" to MaterialTheme.colorScheme.tertiary
        TransferStatus.COMPLETED -> "Done" to MaterialTheme.colorScheme.primary
        TransferStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        TransferStatus.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.outline
    }
    val icon = when (status) {
        TransferStatus.COMPLETED -> Icons.Filled.CheckCircle
        TransferStatus.FAILED -> Icons.Filled.Error
        TransferStatus.CANCELLED -> Icons.Filled.Cancel
        else -> null
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(16.dp)) } },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = color, leadingIconContentColor = color,
        ),
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
