package com.tgstorage.ui.folders

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.data.local.entity.FolderEntity
import com.tgstorage.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(
    onNavigateBack: () -> Unit,
    viewModel: FolderViewModel = viewModel(factory = FolderViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    // Create folder dialog
    if (state.showCreateDialog) {
        FolderNameDialog(
            title = "New Folder",
            initialName = "",
            onConfirm = viewModel::createFolder,
            onDismiss = viewModel::hideCreateDialog,
        )
    }

    // Rename folder dialog
    state.showRenameDialog?.let { folder ->
        FolderNameDialog(
            title = "Rename Folder",
            initialName = folder.name,
            onConfirm = { newName -> viewModel.renameFolder(folder, newName) },
            onDismiss = viewModel::hideRenameDialog,
        )
    }

    // Move-to folder dialog
    if (state.showMoveDialog) {
        MoveFolderDialog(
            folders = state.allFolders,
            currentFolderId = state.currentFolder?.id,
            onSelect = viewModel::moveSelectedToFolder,
            onDismiss = viewModel::hideMoveDialog,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    title = { Text("${state.selectedFileIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::showMoveDialog) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = viewModel::trashSelected) {
                            Icon(Icons.Filled.Delete, "Delete",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(state.currentFolder?.name ?: "Folders")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!viewModel.navigateUp()) onNavigateBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                FloatingActionButton(onClick = viewModel::showCreateDialog) {
                    Icon(Icons.Filled.CreateNewFolder, "New folder")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Breadcrumbs
            if (state.breadcrumbs.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        AssistChip(
                            onClick = { viewModel.loadFolder(null) },
                            label = { Text("Home") },
                            leadingIcon = {
                                Icon(Icons.Filled.Home, "Root", Modifier.size(16.dp))
                            },
                        )
                    }
                    items(state.breadcrumbs) { folder ->
                        AssistChip(
                            onClick = { viewModel.loadFolder(folder) },
                            label = { Text(folder.name) },
                            leadingIcon = {
                                Icon(Icons.Filled.Folder, folder.name, Modifier.size(16.dp))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            when {
                state.isLoading -> LoadingState()

                state.subFolders.isEmpty() && state.files.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen, "Empty folder",
                            Modifier.size(80.dp),
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("This folder is empty", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Create subfolders or move files here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // Subfolders section
                        if (state.subFolders.isNotEmpty()) {
                            item {
                                Text(
                                    "Folders",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(state.subFolders, key = { "folder_${it.id}" }) { folder ->
                                FolderItem(
                                    folder = folder,
                                    onClick = { viewModel.loadFolder(folder) },
                                    onRename = { viewModel.showRenameDialog(folder) },
                                    onDelete = { viewModel.deleteFolder(folder) },
                                )
                            }
                        }

                        // Files section
                        if (state.files.isNotEmpty()) {
                            item {
                                Text(
                                    "Files (${state.files.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(state.files, key = { "file_${it.id}" }) { file ->
                                val isSelected = file.id in state.selectedFileIds
                                FileInFolderItem(
                                    file = file,
                                    isSelected = isSelected,
                                    selectionMode = state.selectionMode,
                                    onTap = {
                                        if (state.selectionMode) {
                                            viewModel.toggleFileSelection(file.id)
                                        }
                                    },
                                    onLongPress = { viewModel.toggleFileSelection(file.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItem(
    folder: FolderEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { showMenu = true },
        ),
        headlineContent = {
            Text(folder.name, fontWeight = FontWeight.Medium)
        },
        leadingContent = {
            Icon(
                Icons.Filled.Folder, folder.name,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, "Rename", Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "Delete", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileInFolderItem(
    file: FileEntity,
    isSelected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .semantics { selected = isSelected },
        headlineContent = {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(formatFileSize(file.size), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            if (selectionMode) {
                Icon(
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    if (isSelected) "Selected" else "Not selected",
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

// ─── Dialogs ───────────────────────────────────────────

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun MoveFolderDialog(
    folders: List<FolderEntity>,
    currentFolderId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        modifier = Modifier.clickable(onClick = { onSelect(null) }),
                        headlineContent = { Text("Root (no folder)", fontWeight = FontWeight.Medium) },
                        leadingContent = {
                            Icon(Icons.Filled.Home, "Root", Modifier.size(24.dp))
                        },
                    )
                }
                items(folders.filter { it.id != currentFolderId }) { folder ->
                    ListItem(
                        modifier = Modifier.clickable(onClick = { onSelect(folder.id) }),
                        headlineContent = { Text(folder.name) },
                        leadingContent = {
                            Icon(Icons.Filled.Folder, folder.name, Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
