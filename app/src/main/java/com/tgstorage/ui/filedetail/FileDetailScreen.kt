package com.tgstorage.ui.filedetail

import android.text.format.DateUtils
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.ui.components.ErrorState
import com.tgstorage.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailScreen(
    fileId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToDownload: () -> Unit,
    viewModel: FileDetailViewModel = viewModel(factory = FileDetailViewModel.Factory(fileId)),
) {
    val state by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val s = state) {
                            is FileDetailUiState.Loaded -> s.file.name
                            else -> "File Detail"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is FileDetailUiState.Loading -> {
                LoadingState(modifier = Modifier.padding(padding))
            }
            is FileDetailUiState.Error -> {
                ErrorState(
                    modifier = Modifier.padding(padding),
                    message = s.message,
                    onRetry = viewModel::loadFile,
                )
            }
            is FileDetailUiState.Loaded -> {
                FileDetailContent(
                    state = s,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onDownload = onNavigateToDownload,
                    onDelete = { showDeleteDialog = true },
                )
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete file?") },
            text = { Text("This will remove the file from your local storage. Telegram copies (if any) will remain.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteFile(onDeleted = onNavigateBack)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun FileDetailContent(
    state: FileDetailUiState.Loaded,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val file = state.file

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── File icon/preview ────────────────────────
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.size(120.dp),
        ) {
            Icon(
                imageVector = mimeIcon(file.mimeType),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── File name ────────────────────────────────
        Text(
            text = file.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Sync status chip ─────────────────────────
        state.syncStatus?.let { status ->
            val (label, chipColor) = when (status) {
                SyncStatus.UPLOADED -> "Uploaded" to MaterialTheme.colorScheme.primaryContainer
                SyncStatus.PENDING_UPLOAD -> "Pending upload" to MaterialTheme.colorScheme.tertiaryContainer
                SyncStatus.FAILED -> "Upload failed" to MaterialTheme.colorScheme.errorContainer
                SyncStatus.DELETED -> "Deleted" to MaterialTheme.colorScheme.errorContainer
                else -> status to MaterialTheme.colorScheme.surfaceVariant
            }
            AssistChip(
                onClick = {},
                label = { Text(label) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = chipColor,
                ),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Metadata card ────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MetadataRow("Size", formatFileSize(file.size))
                MetadataRow("Type", file.mimeType)
                MetadataRow(
                    "Created",
                    DateUtils.getRelativeTimeSpanString(
                        file.createdAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                )
                MetadataRow(
                    "Modified",
                    DateUtils.getRelativeTimeSpanString(
                        file.updatedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                )
                if (file.sha256.isNotBlank()) {
                    MetadataRow("SHA-256", file.sha256.take(16) + "…")
                }
                MetadataRow("Encrypted", if (file.encryptionFlag) "Yes" else "No")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Actions ──────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onDownload,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download")
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete")
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun mimeIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Filled.Image
    mimeType.startsWith("video/") -> Icons.Filled.VideoFile
    mimeType.startsWith("audio/") -> Icons.Filled.AudioFile
    mimeType.startsWith("application/pdf") || mimeType.startsWith("text/") -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
