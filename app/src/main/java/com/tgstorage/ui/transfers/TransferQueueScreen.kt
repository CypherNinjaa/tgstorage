package com.tgstorage.ui.transfers

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgstorage.data.transfer.TransferProgress
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import com.tgstorage.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferQueueScreen(
    viewModel: TransferQueueViewModel = viewModel(),
) {
    val transfers by viewModel.transfers.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                actions = {
                    if (transfers.any { !viewModel.isActive(it) }) {
                        IconButton(onClick = viewModel::clearFinished) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = "Clear finished",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (transfers.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.SwapVert,
                title = "No transfers",
                subtitle = "Your uploads and downloads will appear here",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Active transfers section
                val active = transfers.filter { viewModel.isActive(it) }
                if (active.isNotEmpty()) {
                    item {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(active, key = { it.fileId }) { transfer ->
                        TransferCard(
                            progress = transfer,
                            onCancel = { viewModel.cancelTransfer(transfer.fileId, transfer.type) },
                        )
                    }
                }

                // Finished transfers section
                val finished = transfers.filter { !viewModel.isActive(it) }
                if (finished.isNotEmpty()) {
                    item {
                        Text(
                            text = "Completed",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(finished, key = { it.fileId }) { transfer ->
                        TransferCard(
                            progress = transfer,
                            onCancel = null,
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun TransferCard(
    progress: TransferProgress,
    onCancel: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (progress.type == TransferType.UPLOAD) {
                        Icons.Filled.CloudUpload
                    } else {
                        Icons.Filled.CloudDownload
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = progress.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${progress.type.name.lowercase().replaceFirstChar { it.uppercase() }} • ${formatSize(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                StatusChip(progress.status)
            }

            // Progress bar for active transfers
            if (progress.status == TransferStatus.IN_PROGRESS || progress.status == TransferStatus.PENDING) {
                Spacer(modifier = Modifier.height(12.dp))
                val fraction = if (progress.totalBytes > 0) {
                    progress.bytesTransferred.toFloat() / progress.totalBytes
                } else 0f

                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Chunk ${progress.currentChunk}/${progress.totalChunks}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Error message
            if (progress.status == TransferStatus.FAILED && progress.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Cancel button
            if (onCancel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancel) {
                    Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
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
        leadingIcon = icon?.let {
            {
                Icon(it, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = color,
            leadingIconContentColor = color,
        ),
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
