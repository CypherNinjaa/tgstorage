package com.tgstorage.ui.home

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tgstorage.data.scanner.DeviceFile
import com.tgstorage.ui.components.ErrorState
import com.tgstorage.ui.components.LoadingState
import com.tgstorage.ui.viewer.MediaViewerScreen
import com.tgstorage.util.StoragePermissionHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToUpload: () -> Unit,
    onNavigateToFileDetail: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var infoFile by remember { mutableStateOf<DeviceFile?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Media viewer state
    var mediaViewerFiles by remember { mutableStateOf<List<DeviceFile>>(emptyList()) }
    var mediaViewerIndex by remember { mutableStateOf(0) }
    var showMediaViewer by remember { mutableStateOf(false) }

    // Move-to-folder dialog state
    var showMoveToFolderDialog by remember { mutableStateOf(false) }

    // ── Permission handling (using modern StoragePermissionHelper) ─────────────
    val permissionsToRequest = StoragePermissionHelper.getMediaPermissions()
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }

    // Phase 9: Toast for "no app found" when opening a file
    var noAppToast by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.any { it }) {
            // At least partial access granted (Android 14+ partial photo access)
            viewModel.onPermissionGranted()
        } else {
            // Check if permanently denied
            val activity = context as? Activity
            if (activity != null && StoragePermissionHelper.isPermanentlyDenied(activity, results)) {
                showPermissionDeniedDialog = true
            }
            viewModel.onPermissionDenied()
        }
    }

    LaunchedEffect(Unit) {
        if (StoragePermissionHelper.hasMediaPermissions(context)) {
            viewModel.onPermissionGranted()
        } else if (StoragePermissionHelper.hasAnyMediaPermission(context)) {
            // Partial access (good enough for now)
            viewModel.onPermissionGranted()
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    // Phase 9: Show toast when no app can open a file
    LaunchedEffect(noAppToast) {
        if (noAppToast) {
            snackbarHostState.showSnackbar("No app found to open this file")
            noAppToast = false
        }
    }

    // Permission permanently denied dialog
    if (showPermissionDeniedDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text("Permission Required") },
            text = { 
                Text("Storage permission was denied. Please enable it in Settings to browse and backup your files.") 
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDeniedDialog = false
                    (context as? Activity)?.let { StoragePermissionHelper.openAppSettings(it) }
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDeniedDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    title = { Text("${state.selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Filled.SelectAll, "Select all")
                        }
                        IconButton(onClick = viewModel::uploadSelected) {
                            Icon(Icons.Filled.CloudUpload, "Upload selected",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {
                            shareMultipleFiles(context, state.deviceFiles.filter { it.id in state.selectedIds })
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Filled.Share, "Share selected")
                        }
                        IconButton(onClick = { showMoveToFolderDialog = true }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move to folder")
                        }
                    },
                )
            }
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        ) {
            // ── Permission not granted ──────────────────
            if (!state.hasPermission && !state.isLoading) {
                PermissionScreen(onGrant = { permissionLauncher.launch(permissionsToRequest) })
                return@Scaffold
            }

            // ── Banners ─────────────────────────────────
            AnimatedVisibility(visible = !state.isOnline) {
                StatusBanner(
                    icon = Icons.Outlined.CloudOff,
                    text = "You're offline",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    accessibilityLabel = "Device is offline",
                )
            }
            AnimatedVisibility(visible = state.activeUploads > 0) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${state.activeUploads} upload(s) active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            AnimatedVisibility(visible = state.isImporting) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Importing…", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }

            // ── Batch progress banner ───────────────────
            state.batchProgress?.let { bp ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        // Top row: remaining count + batch info
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${bp.remainingFiles} remaining",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                "Batch ${bp.currentBatch}/${bp.totalBatches}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // Progress bar
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { bp.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        // Bottom stats row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${bp.uploadedFiles} uploaded",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (bp.failedFiles > 0) {
                                Text(
                                    "${bp.failedFiles} failed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                "${bp.batchSize} files/batch",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }

            // ── Auto-upload toggle ──────────────────────
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = "Auto upload", Modifier.size(18.dp),
                    MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Auto Upload", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                Switch(checked = state.autoUpload, onCheckedChange = { viewModel.toggleAutoUpload() })
            }

            // ── Search bar ──────────────────────────────
            if (!state.selectionMode) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = state.searchQuery,
                            onQueryChange = viewModel::onSearchQueryChange,
                            onSearch = { searchActive = false },
                            expanded = searchActive,
                            onExpandedChange = { searchActive = it },
                            placeholder = { Text("Search files…") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        viewModel.onSearchQueryChange(""); searchActive = false
                                    }) { Icon(Icons.Filled.Close, "Clear") }
                                }
                            },
                        )
                    },
                    expanded = searchActive,
                    onExpandedChange = { searchActive = it },
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = if (searchActive) 0.dp else 16.dp),
                ) {}
            }

            Spacer(Modifier.height(8.dp))

            // ── Filter chips + view toggle ──────────────
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        if (state.viewMode == ViewMode.GRID) Icons.AutoMirrored.Filled.ViewList
                        else Icons.Filled.GridView, "Toggle view",
                    )
                }
            }

            if (state.deviceFiles.isNotEmpty()) {
                val countText = if (state.hasMoreFiles) {
                    "${state.deviceFiles.size} of ${state.allFilesCount} files"
                } else {
                    "${state.deviceFiles.size} files on device"
                }
                Text(countText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            // ── Content ─────────────────────────────────
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isLoading && state.deviceFiles.isEmpty() -> LoadingState()

                    state.error != null && state.deviceFiles.isEmpty() -> ErrorState(
                        message = state.error ?: "Something went wrong",
                        onRetry = {
                            if (!state.hasPermission) permissionLauncher.launch(permissionsToRequest)
                            else viewModel.refresh()
                        },
                    )

                    state.deviceFiles.isEmpty() -> EmptyFilesState()

                    state.viewMode == ViewMode.GRID -> DeviceFileGrid(
                        files = state.deviceFiles,
                        selectedIds = state.selectedIds,
                        selectionMode = state.selectionMode,
                        uploadedNames = state.uploadedNames,
                        hasMoreFiles = state.hasMoreFiles,
                        isLoadingMore = state.isLoadingMore,
                        onTap = { file ->
                            if (state.selectionMode) {
                                viewModel.toggleSelection(file.id)
                            } else if (file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/")) {
                                // Open media viewer for images & videos
                                val mediaFiles = state.deviceFiles.filter {
                                    it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")
                                }
                                mediaViewerFiles = mediaFiles
                                mediaViewerIndex = mediaFiles.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                                showMediaViewer = true
                            } else {
                                infoFile = file
                            }
                        },
                        onLongPress = { viewModel.toggleSelection(it.id) },
                        onLoadMore = viewModel::loadMoreFiles,
                    )

                    else -> DeviceFileList(
                        files = state.deviceFiles,
                        selectedIds = state.selectedIds,
                        selectionMode = state.selectionMode,
                        uploadedNames = state.uploadedNames,
                        hasMoreFiles = state.hasMoreFiles,
                        isLoadingMore = state.isLoadingMore,
                        onTap = { file ->
                            if (state.selectionMode) {
                                viewModel.toggleSelection(file.id)
                            } else if (file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/")) {
                                val mediaFiles = state.deviceFiles.filter {
                                    it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")
                                }
                                mediaViewerFiles = mediaFiles
                                mediaViewerIndex = mediaFiles.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                                showMediaViewer = true
                            } else {
                                infoFile = file
                            }
                        },
                        onLongPress = { viewModel.toggleSelection(it.id) },
                        onLoadMore = viewModel::loadMoreFiles,
                    )
                }
            }
        }
    }

    if (infoFile != null) {
        ModalBottomSheet(
            onDismissRequest = { infoFile = null },
            sheetState = sheetState,
        ) {
            DeviceFileInfoSheet(
                file = infoFile!!,
                onOpen = { openDeviceFile(context, infoFile!!) { noAppToast = true }; infoFile = null },
                onShare = {
                    shareFile(context, infoFile!!)
                    infoFile = null
                },
                onClose = { infoFile = null },
            )
        }
    }

    // ── Media viewer overlay ────────────────────────────
    if (showMediaViewer && mediaViewerFiles.isNotEmpty()) {
        MediaViewerScreen(
            files = mediaViewerFiles,
            initialIndex = mediaViewerIndex,
            onBack = { showMediaViewer = false },
            onShare = { file -> shareFile(context, file) },
        )
    }

    // ── Move to folder dialog ───────────────────────────
    if (showMoveToFolderDialog) {
        MoveToFolderDialog(
            viewModel = viewModel,
            onDismiss = { showMoveToFolderDialog = false },
            onMoved = {
                showMoveToFolderDialog = false
                viewModel.clearSelection()
            },
        )
    }
}

// ─── Permission screen ─────────────────────────────────

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = "Storage permission needed", Modifier.size(80.dp),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))
        Text("Storage permission needed", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("TgStorage needs access to your files to display and upload them to Telegram.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Grant Permission") }
    }
}

@Composable
private fun EmptyFilesState() {
    Column(Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = "No files found", Modifier.size(80.dp),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))
        Text("No files found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("No files match your current filter.\nTry changing the filter or search.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatusBanner(icon: ImageVector, text: String, color: Color, accessibilityLabel: String = text) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        .semantics { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(16.dp), color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

// ─── Thumbnail composable ──────────────────────────────

@Composable
private fun FileThumbnail(file: DeviceFile, modifier: Modifier = Modifier) {
    if (file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/")) {
        val context = LocalContext.current
        val iconColor = MaterialTheme.colorScheme.primary
        Box(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file.contentUri)
                    .crossfade(150) // Faster crossfade
                    .size(128) // Smaller size for faster loading
                    .memoryCacheKey("thumb_${file.id}") // Better cache key
                    .build(),
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // Show placeholder while loading
                placeholder = null,
                error = null,
            )
            // Overlay icon for videos
            if (file.mimeType.startsWith("video/")) {
                Icon(
                    Icons.Filled.PlayCircleOutline,
                    contentDescription = "Video",
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    tint = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(mimeIcon(file.mimeType), contentDescription = file.name, Modifier.size(40.dp),
                MaterialTheme.colorScheme.primary)
        }
    }
}

// ─── Grid view ─────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceFileGrid(
    files: List<DeviceFile>,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    uploadedNames: Set<String>,
    hasMoreFiles: Boolean = false,
    isLoadingMore: Boolean = false,
    onTap: (DeviceFile) -> Unit,
    onLongPress: (DeviceFile) -> Unit,
    onLoadMore: () -> Unit = {},
) {
    // Cache grouped files to avoid regrouping on every recomposition
    val grouped = remember(files) { groupFilesByDate(files) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        grouped.forEach { (dateLabel, group) ->
            // Full-width date header
            item(span = { GridItemSpan(maxLineSpan) }, key = "header_$dateLabel") {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
                )
            }
            items(group, key = { it.id }) { file ->
                val isSelected = file.id in selectedIds
                val isUploaded = file.name in uploadedNames
                DeviceFileGridItem(
                    file = file, isSelected = isSelected, selectionMode = selectionMode,
                    isUploaded = isUploaded,
                    onTap = { onTap(file) }, onLongPress = { onLongPress(file) },
                )
            }
        }

        // Load more button at the bottom
        if (hasMoreFiles) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "load_more") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = onLoadMore) {
                            Text("Load more files")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceFileGridItem(
    file: DeviceFile,
    isSelected: Boolean,
    selectionMode: Boolean,
    isUploaded: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardDefaults.shape)
                else Modifier
            )
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
                onClickLabel = "View file details",
                onLongClickLabel = "Select file",
            )
            .semantics { selected = isSelected },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box {
            Column(
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Thumbnail
                FileThumbnail(
                    file = file,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                Spacer(Modifier.height(6.dp))
                Text(file.name, style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(formatFileSize(file.size), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Selection check
            if (selectionMode) {
                Icon(
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
                        .clip(CircleShape)
                        .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }

            // Green "uploaded" badge
            if (isUploaded) {
                Icon(
                    Icons.Filled.CloudDone, "Uploaded",
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp),
                    tint = Color(0xFF4CAF50), // Material Green
                )
            }
        }
    }
}

// ─── List view ─────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceFileList(
    files: List<DeviceFile>,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    uploadedNames: Set<String>,
    hasMoreFiles: Boolean = false,
    isLoadingMore: Boolean = false,
    onTap: (DeviceFile) -> Unit,
    onLongPress: (DeviceFile) -> Unit,
    onLoadMore: () -> Unit = {},
) {
    // Cache grouped files to avoid regrouping on every recomposition
    val grouped = remember(files) { groupFilesByDate(files) }

    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        grouped.forEach { (dateLabel, group) ->
            item(key = "list_header_$dateLabel") {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 16.dp),
                )
            }
            items(group, key = { it.id }) { file ->
                val isSelected = file.id in selectedIds
                val isUploaded = file.name in uploadedNames
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onClick = { onTap(file) },
                        onLongClick = { onLongPress(file) },
                        onClickLabel = "View file details",
                        onLongClickLabel = "Select file",
                    ).semantics { selected = isSelected },
                    headlineContent = {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(formatFileSize(file.size), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isUploaded) {
                                Icon(Icons.Filled.CloudDone, "Uploaded", Modifier.size(14.dp),
                                    tint = Color(0xFF4CAF50))
                                Text("Uploaded", style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4CAF50))
                            }
                        }
                    },
                    leadingContent = {
                        FileThumbnail(file = file, modifier = Modifier.size(48.dp))
                    },
                    trailingContent = {
                        if (selectionMode) {
                            Icon(
                                if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = if (isSelected) "Selected" else "Not selected",
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        }

        // Load more button at the bottom
        if (hasMoreFiles) {
            item(key = "list_load_more") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = onLoadMore) {
                            Text("Load more files")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceFileInfoSheet(
    file: DeviceFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                mimeIcon(file.mimeType),
                contentDescription = file.mimeType,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatFileSize(file.size), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(16.dp))

        InfoRow("MIME", file.mimeType)
        InfoRow(
            "Modified",
            DateUtils.getRelativeTimeSpanString(
                file.dateModified * 1000L,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString(),
        )

        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onOpen,
                modifier = Modifier.weight(1f),
            ) {
                Text("Open")
            }
            Button(
                onClick = onShare,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Share")
            }
            TextButton(
                onClick = onClose,
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

private fun openDeviceFile(context: android.content.Context, file: DeviceFile, onNoApp: () -> Unit = {}) {
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(file.contentUri, file.mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(intent, "Open with")
        context.startActivity(chooser)
    } catch (_: Exception) {
        // Phase 9: Surface error to user instead of silently swallowing
        onNoApp()
    }
}

// ─── Helpers ───────────────────────────────────────────

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

/**
 * Groups device files by date (Google Photos style).
 * Uses dateModified epoch seconds to produce labels like "Today", "Yesterday", "Feb 10, 2026".
 */
private fun groupFilesByDate(files: List<DeviceFile>): List<Pair<String, List<DeviceFile>>> {
    val cal = Calendar.getInstance()
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterdayStart = todayStart - 24 * 60 * 60 * 1000L
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    return files
        .sortedByDescending { it.dateModified }
        .groupBy { file ->
            val millis = file.dateModified * 1000L
            when {
                millis >= todayStart -> "Today"
                millis >= yesterdayStart -> "Yesterday"
                else -> dateFormat.format(Date(millis))
            }
        }
        .toList()
}

// ─── Share helpers ─────────────────────────────────────

private fun shareFile(context: android.content.Context, file: DeviceFile) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType
            putExtra(Intent.EXTRA_STREAM, file.contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    } catch (_: Exception) { /* no app to handle */ }
}

private fun shareMultipleFiles(context: android.content.Context, files: List<DeviceFile>) {
    if (files.isEmpty()) return
    if (files.size == 1) {
        shareFile(context, files.first())
        return
    }
    try {
        val uris = ArrayList(files.map { it.contentUri })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${files.size} files"))
    } catch (_: Exception) { /* no app to handle */ }
}

// ─── Move to folder dialog ─────────────────────────────

@Composable
private fun MoveToFolderDialog(
    viewModel: HomeViewModel,
    onDismiss: () -> Unit,
    onMoved: () -> Unit,
) {
    val folders by viewModel.folders.collectAsState()
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to Folder") },
        text = {
            if (folders.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No folders yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Create folders from Settings → Folders",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn {
                    items(folders) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            leadingContent = {
                                RadioButton(
                                    selected = selectedFolderId == folder.id,
                                    onClick = { selectedFolderId = folder.id },
                                )
                            },
                            modifier = Modifier.clickable { selectedFolderId = folder.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedFolderId?.let { folderId ->
                        viewModel.moveSelectedToFolder(folderId)
                        onMoved()
                    }
                },
                enabled = selectedFolderId != null,
            ) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
