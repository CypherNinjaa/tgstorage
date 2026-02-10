package com.tgstorage.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

    // ── Permission handling ─────────────────────────────
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
    }

    LaunchedEffect(Unit) {
        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) viewModel.onPermissionGranted()
        else permissionLauncher.launch(permissionsToRequest)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
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
                StatusBanner(Icons.Outlined.CloudOff, "You're offline",
                    MaterialTheme.colorScheme.onSurfaceVariant)
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

            // ── Auto-upload toggle ──────────────────────
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp),
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
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
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
                Text("${state.deviceFiles.size} files on device",
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
                        onTap = { if (state.selectionMode) viewModel.toggleSelection(it.id) },
                        onLongPress = { viewModel.toggleSelection(it.id) },
                    )

                    else -> DeviceFileList(
                        files = state.deviceFiles,
                        selectedIds = state.selectedIds,
                        selectionMode = state.selectionMode,
                        uploadedNames = state.uploadedNames,
                        onTap = { if (state.selectionMode) viewModel.toggleSelection(it.id) },
                        onLongPress = { viewModel.toggleSelection(it.id) },
                    )
                }
            }
        }
    }
}

// ─── Permission screen ─────────────────────────────────

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(80.dp),
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
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(80.dp),
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
private fun StatusBanner(icon: ImageVector, text: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

// ─── Thumbnail composable ──────────────────────────────

@Composable
private fun FileThumbnail(file: DeviceFile, modifier: Modifier = Modifier) {
    if (file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/")) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(file.contentUri)
                .crossfade(true)
                .size(256)
                .build(),
            contentDescription = file.name,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(mimeIcon(file.mimeType), null, Modifier.size(40.dp),
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
    onTap: (DeviceFile) -> Unit,
    onLongPress: (DeviceFile) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(files, key = { it.id }) { file ->
            val isSelected = file.id in selectedIds
            val isUploaded = file.name in uploadedNames
            DeviceFileGridItem(
                file = file, isSelected = isSelected, selectionMode = selectionMode,
                isUploaded = isUploaded,
                onTap = { onTap(file) }, onLongPress = { onLongPress(file) },
            )
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
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
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
                    null,
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
    onTap: (DeviceFile) -> Unit,
    onLongPress: (DeviceFile) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        items(files, key = { it.id }) { file ->
            val isSelected = file.id in selectedIds
            val isUploaded = file.name in uploadedNames
            ListItem(
                modifier = Modifier.combinedClickable(
                    onClick = { onTap(file) }, onLongClick = { onLongPress(file) },
                ),
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
                            null,
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
}

// ─── Helpers ───────────────────────────────────────────

private fun mimeIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Filled.CheckCircle // fallback, shouldn't reach here
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
