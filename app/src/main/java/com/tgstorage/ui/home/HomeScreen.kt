package com.tgstorage.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgstorage.data.local.entity.SyncStatus
import com.tgstorage.ui.components.EmptyState
import com.tgstorage.ui.components.ErrorState
import com.tgstorage.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToUpload: () -> Unit,
    onNavigateToFileDetail: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    var searchActive by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToUpload) {
                Icon(Icons.Filled.Add, contentDescription = "Upload file")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Offline banner ──────────────────────────
            AnimatedVisibility(visible = !state.isOnline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "You're offline",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Search bar ──────────────────────────────
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = state.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onSearch = { searchActive = false },
                        expanded = searchActive,
                        onExpandedChange = { searchActive = it },
                        placeholder = { Text("Search files…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    viewModel.onSearchQueryChange("")
                                    searchActive = false
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        },
                    )
                },
                expanded = searchActive,
                onExpandedChange = { searchActive = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (searchActive) 0.dp else 16.dp),
            ) {
                // Search suggestions could go here
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Filter chips + view toggle ──────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(FileFilter.entries.toList()) { filter ->
                        FilterChip(
                            selected = state.activeFilter == filter,
                            onClick = { viewModel.onFilterChange(filter) },
                            label = { Text(filter.label) },
                        )
                    }
                }
                IconButton(onClick = viewModel::onViewModeToggle) {
                    Icon(
                        imageVector = if (state.viewMode == ViewMode.GRID)
                            Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                        contentDescription = "Toggle view",
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Content area ────────────────────────────
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isLoading && state.files.isEmpty() -> {
                        LoadingState()
                    }
                    state.error != null && state.files.isEmpty() -> {
                        ErrorState(
                            message = state.error ?: "Something went wrong",
                            onRetry = viewModel::refresh,
                        )
                    }
                    state.files.isEmpty() -> {
                        EmptyState(
                            icon = Icons.Outlined.Folder,
                            title = "No files yet",
                            subtitle = "Upload your first file to get started",
                        )
                    }
                    state.viewMode == ViewMode.GRID -> {
                        FileGrid(
                            files = state.files,
                            onFileClick = { onNavigateToFileDetail(it.file.id) },
                        )
                    }
                    else -> {
                        FileList(
                            files = state.files,
                            onFileClick = { onNavigateToFileDetail(it.file.id) },
                        )
                    }
                }
            }
        }
    }
}

// ─── Grid view ─────────────────────────────────────────

@Composable
private fun FileGrid(
    files: List<FileWithSync>,
    onFileClick: (FileWithSync) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(files, key = { it.file.id }) { item ->
            FileGridItem(item = item, onClick = { onFileClick(item) })
        }
    }
}

@Composable
private fun FileGridItem(
    item: FileWithSync,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = mimeIcon(item.file.mimeType),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.file.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = formatFileSize(item.file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SyncBadge(status = item.syncStatus)
            }
        }
    }
}

// ─── List view ─────────────────────────────────────────

@Composable
private fun FileList(
    files: List<FileWithSync>,
    onFileClick: (FileWithSync) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(files, key = { it.file.id }) { item ->
            FileListItem(item = item, onClick = { onFileClick(item) })
        }
    }
}

@Composable
private fun FileListItem(
    item: FileWithSync,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = item.file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = formatFileSize(item.file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SyncBadge(status = item.syncStatus)
            }
        },
        leadingContent = {
            Icon(
                imageVector = mimeIcon(item.file.mimeType),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

// ─── Sync badge ────────────────────────────────────────

@Composable
private fun SyncBadge(status: String?) {
    val (label, color) = when (status) {
        SyncStatus.UPLOADED -> "Uploaded" to MaterialTheme.colorScheme.primary
        SyncStatus.PENDING_UPLOAD -> "Pending" to MaterialTheme.colorScheme.tertiary
        SyncStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        else -> return
    }
    Text(
        text = "• $label",
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

// ─── Helpers ───────────────────────────────────────────

private fun mimeIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Filled.Image
    mimeType.startsWith("video/") -> Icons.Filled.VideoFile
    mimeType.startsWith("audio/") -> Icons.Filled.AudioFile
    mimeType.startsWith("application/pdf") ||
    mimeType.startsWith("text/") -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
