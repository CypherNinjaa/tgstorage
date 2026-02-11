package com.tgstorage.ui.transfers

import android.text.format.DateUtils
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.tgstorage.data.transfer.ThumbnailManager
import com.tgstorage.ui.components.EmptyState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferQueueScreen(
    onNavigateToFileDetail: (Long) -> Unit,
    viewModel: TransferQueueViewModel = viewModel(factory = TransferQueueViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    var infoSheet by remember { mutableStateOf<TransferInfoSheetData?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                        val count = state.uploadedTabCount
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
                    searchQuery = state.transferSearchQuery,
                    onSearchChange = viewModel::onTransferSearchChange,
                    filter = state.transferFilter,
                    onFilterChange = viewModel::onTransferFilterChange,
                    onShowInfo = { infoSheet = it },
                )
                1 -> UploadedTab(
                    files = state.uploadedFiles,
                    totalCount = state.uploadedTotalCount,
                    isLoading = state.isLoadingUploaded,
                    hasMore = state.hasMorePages,
                    downloadingIds = state.downloadingIds,
                    onDownload = viewModel::enqueueDownload,
                    searchQuery = state.uploadedSearchQuery,
                    onSearchChange = viewModel::onUploadedSearchChange,
                    filter = state.uploadedFilter,
                    onFilterChange = viewModel::onUploadedFilterChange,
                    viewMode = state.uploadedViewMode,
                    onToggleView = viewModel::toggleUploadedViewMode,
                    onLoadMore = viewModel::loadMoreUploaded,
                    onShowInfo = { infoSheet = it },
                )
            }
        }
    }

    if (infoSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { infoSheet = null },
            sheetState = sheetState,
        ) {
            TransferInfoSheet(
                info = infoSheet!!,
                onViewDetails = {
                    infoSheet?.let { data ->
                        if (data.fileId > 0) onNavigateToFileDetail(data.fileId)
                    }
                    infoSheet = null
                },
                onClose = { infoSheet = null },
            )
        }
    }
}

private data class TransferInfoSheetData(
    val fileId: Long,
    val name: String,
    val size: Long,
    val mimeType: String,
    val statusLabel: String? = null,
    val uploadedAt: Long? = null,
    val typeLabel: String? = null,
)

// ─── Transfers tab ─────────────────────────────────────

@Composable
private fun TransfersTab(
    transfers: List<TransferProgress>,
    isActive: (TransferProgress) -> Boolean,
    onCancel: (TransferProgress) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filter: TransferFileFilter,
    onFilterChange: (TransferFileFilter) -> Unit,
    onShowInfo: (TransferInfoSheetData) -> Unit,
) {
    val filtered = transfers
        .filter { matchesQuery(it.fileName, searchQuery) }
        .filter { matchesMimeFilter(it.mimeType, filter) }

    if (transfers.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SearchAndFilterRow(
                query = searchQuery,
                onQueryChange = onSearchChange,
                filter = filter,
                onFilterChange = onFilterChange,
                placeholder = "Search transfers...",
            )
            Spacer(Modifier.height(24.dp))
            EmptyState(
                icon = Icons.Outlined.SwapVert,
                title = "No transfers",
                subtitle = "Your uploads and downloads will appear here",
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else if (filtered.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SearchAndFilterRow(
                query = searchQuery,
                onQueryChange = onSearchChange,
                filter = filter,
                onFilterChange = onFilterChange,
                placeholder = "Search transfers...",
            )
            Spacer(Modifier.height(24.dp))
            EmptyState(
                icon = Icons.Outlined.SwapVert,
                title = "No matching transfers",
                subtitle = "Try a different search or filter",
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        val active = filtered.filter { isActive(it) }
        val finished = filtered.filter { !isActive(it) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "transfer_filters") {
                SearchAndFilterRow(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    filter = filter,
                    onFilterChange = onFilterChange,
                    placeholder = "Search transfers...",
                )
            }
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
                    TransferCard(
                        progress = transfer,
                        onCancel = { onCancel(transfer) },
                        onShowInfo = onShowInfo,
                    )
                }
            }

            if (finished.isNotEmpty()) {
                item {
                    Text("Completed", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                }
                items(finished, key = { "${it.fileId}_${it.type}_done" }) { transfer ->
                    TransferCard(progress = transfer, onCancel = null, onShowInfo = onShowInfo)
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

// ─── Search + filter row ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAndFilterRow(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: TransferFileFilter,
    onFilterChange: (TransferFileFilter) -> Unit,
    placeholder: String,
    viewMode: TransferViewMode? = null,
    onToggleView: (() -> Unit)? = null,
) {
    var searchActive by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSearch = { searchActive = false },
                    expanded = searchActive,
                    onExpandedChange = { searchActive = it },
                    placeholder = { Text(placeholder) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange(""); searchActive = false }) {
                                Icon(Icons.Filled.Close, "Clear")
                            }
                        }
                    },
                )
            },
            expanded = searchActive,
            onExpandedChange = { searchActive = it },
            modifier = Modifier.fillMaxWidth(),
        ) {}

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TransferFileFilter.entries.toList()) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onFilterChange(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            if (viewMode != null && onToggleView != null) {
                IconButton(onClick = onToggleView) {
                    Icon(
                        if (viewMode == TransferViewMode.GRID) Icons.AutoMirrored.Filled.ViewList
                        else Icons.Filled.GridView,
                        "Toggle view",
                    )
                }
            }
        }
    }
}

// ─── Uploaded tab ──────────────────────────────────────

@Composable
private fun UploadedTab(
    files: List<UploadedFileInfo>,
    totalCount: Int,
    isLoading: Boolean,
    hasMore: Boolean,
    downloadingIds: Set<Long>,
    onDownload: (UploadedFileInfo) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filter: TransferFileFilter,
    onFilterChange: (TransferFileFilter) -> Unit,
    viewMode: TransferViewMode,
    onToggleView: () -> Unit,
    onLoadMore: () -> Unit,
    onShowInfo: (TransferInfoSheetData) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // ── Sticky search + filter (outside scroll) ────
        SearchAndFilterRow(
            query = searchQuery,
            onQueryChange = onSearchChange,
            filter = filter,
            onFilterChange = onFilterChange,
            placeholder = "Search uploads...",
            viewMode = viewMode,
            onToggleView = onToggleView,
        )

        if (isLoading && files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (files.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            EmptyState(
                icon = Icons.Outlined.CloudDone,
                title = if (searchQuery.isNotBlank() || filter != TransferFileFilter.ALL)
                    "No matching uploads" else "No uploaded files",
                subtitle = if (searchQuery.isNotBlank() || filter != TransferFileFilter.ALL)
                    "Try a different search or filter"
                else "Files you upload to Telegram will appear here",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "$totalCount file(s) in Telegram cloud",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )

            // Group by date for visual sections
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val grouped = files.groupBy { file ->
                val ts = file.uploadedAt ?: file.updatedAt
                dateFormat.format(Date(ts))
            }

            if (viewMode == TransferViewMode.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    grouped.forEach { (date, group) ->
                        item(key = "header_$date") {
                            Text(
                                date,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(group, key = { it.id }) { file ->
                            UploadedFileCard(
                                file = file,
                                isDownloading = file.id in downloadingIds,
                                onDownload = { onDownload(file) },
                                onShowInfo = {
                                    onShowInfo(
                                        TransferInfoSheetData(
                                            fileId = file.id,
                                            name = file.name,
                                            size = file.size,
                                            mimeType = file.mimeType,
                                            uploadedAt = file.uploadedAt ?: file.updatedAt,
                                            typeLabel = "Uploaded",
                                        )
                                    )
                                },
                            )
                        }
                    }
                    if (hasMore) {
                        item(key = "load_more") {
                            LoadMoreRow(files.size, totalCount, isLoading, onLoadMore)
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(files, key = { it.id }) { file ->
                        UploadedFileGridItem(
                            file = file,
                            isDownloading = file.id in downloadingIds,
                            onDownload = { onDownload(file) },
                            onShowInfo = {
                                onShowInfo(
                                    TransferInfoSheetData(
                                        fileId = file.id,
                                        name = file.name,
                                        size = file.size,
                                        mimeType = file.mimeType,
                                        uploadedAt = file.uploadedAt ?: file.updatedAt,
                                        typeLabel = "Uploaded",
                                    )
                                )
                            },
                        )
                    }
                    if (hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LoadMoreRow(files.size, totalCount, isLoading, onLoadMore)
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun UploadedFileCard(
    file: UploadedFileInfo,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onShowInfo: () -> Unit,
) {
    val thumbPath = ThumbnailManager.resolveThumbnailPath(file.localUri, file.thumbnailUri, file.mimeType)
    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val uploadTime = timeFormat.format(Date(file.uploadedAt ?: file.updatedAt))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onShowInfo,
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
                if (thumbPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(File(thumbPath))
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

@Composable
private fun UploadedFileGridItem(
    file: UploadedFileInfo,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onShowInfo: () -> Unit,
) {
    val isImage = file.mimeType.startsWith("image/")
    val isVideo = file.mimeType.startsWith("video/")
    val thumbPath = ThumbnailManager.resolveThumbnailPath(file.localUri, file.thumbnailUri, file.mimeType)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onShowInfo,
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(File(thumbPath))
                            .size(256)
                            .crossfade(true)
                            .build(),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(
                        fileMimeIcon(file.mimeType),
                        null,
                        Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                file.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatSize(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!isDownloading) {
                    IconButton(onClick = onDownload, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Download, "Download",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadMoreRow(visibleCount: Int, totalCount: Int, isLoading: Boolean, onLoadMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$visibleCount of $totalCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Button(onClick = onLoadMore) {
                Text("Load more")
            }
        }
    }
}

@Composable
private fun TransferInfoSheet(
    info: TransferInfoSheetData,
    onViewDetails: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                fileMimeIcon(info.mimeType),
                null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatSize(info.size), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(16.dp))

        info.typeLabel?.let {
            InfoRow("Type", it)
        }
        InfoRow("MIME", info.mimeType)
        info.statusLabel?.let {
            InfoRow("Status", it)
        }
        info.uploadedAt?.let { ts ->
            InfoRow(
                "Uploaded",
                DateUtils.getRelativeTimeSpanString(
                    ts,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onViewDetails,
                modifier = Modifier.weight(1f),
                enabled = info.fileId > 0,
            ) {
                Text("View details")
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) {
                Text("Close")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium)
    }
}

private fun fileMimeIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Filled.Image
    mimeType.startsWith("video/") -> Icons.Filled.VideoFile
    mimeType.startsWith("audio/") -> Icons.Filled.AudioFile
    mimeType.startsWith("application/pdf") || mimeType.startsWith("text/") -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun matchesQuery(name: String, query: String): Boolean {
    if (query.isBlank()) return true
    return name.contains(query, ignoreCase = true)
}

private fun matchesMimeFilter(mimeType: String, filter: TransferFileFilter): Boolean {
    return filter.mimePrefix == null || mimeType.startsWith(filter.mimePrefix)
}

// ─── Transfer card ─────────────────────────────────────

@Composable
private fun TransferCard(
    progress: TransferProgress,
    onCancel: (() -> Unit)?,
    onShowInfo: (TransferInfoSheetData) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = {
            onShowInfo(
                TransferInfoSheetData(
                    fileId = progress.fileId,
                    name = progress.fileName,
                    size = progress.totalBytes,
                    mimeType = progress.mimeType,
                    statusLabel = progress.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    typeLabel = if (progress.type == TransferType.UPLOAD) "Upload" else "Download",
                )
            )
        },
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
