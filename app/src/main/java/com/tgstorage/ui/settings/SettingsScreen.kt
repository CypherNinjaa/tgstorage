package com.tgstorage.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgstorage.ui.components.LoadingState
import com.tgstorage.util.StoragePermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToSecurity: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToBotSettings: () -> Unit,
    onNavigateToFolders: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message, state.error) {
        state.message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
        state.error?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item { SectionHeader("Telegram") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Bot token",
                    subtitle = state.botToken?.let { maskToken(it, state.isTokenVisible) } ?: "Not set",
                    trailing = {
                        IconButton(onClick = viewModel::toggleTokenVisibility) {
                            Icon(
                                if (state.isTokenVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                "Toggle token visibility",
                            )
                        }
                    },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Channel ID",
                    subtitle = state.chatId ?: "Not set",
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = viewModel::reverifyChannel, enabled = !state.isReverifying) {
                        Text(if (state.isReverifying) "Verifying..." else "Re-verify")
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Manage Bots", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("Configure multiple bots for parallel uploads", style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = { Icon(imageVector = Icons.Outlined.SmartToy, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { onNavigateToBotSettings() },
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }

            item { SectionHeader("Storage") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Cached,
                    title = "Cache size",
                    subtitle = formatSize(state.cacheBytes),
                    trailing = {
                        TextButton(onClick = viewModel::clearCache, enabled = !state.isClearingCache) {
                            Text(if (state.isClearingCache) "Clearing..." else "Clear")
                        }
                    },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.CloudUpload,
                    title = "Pending uploads",
                    subtitle = if (state.pendingUploadBytes > 0) 
                        "${formatSize(state.pendingUploadBytes)} (files waiting to upload)"
                    else "No pending files",
                    trailing = {
                        if (state.pendingUploadBytes > 0) {
                            TextButton(
                                onClick = viewModel::clearPendingUploads, 
                                enabled = !state.isClearingPending
                            ) {
                                Text(if (state.isClearingPending) "Clearing..." else "Clear")
                            }
                        }
                    },
                )
            }
            // Full storage access for documents on Android 11+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                item {
                    FullStorageAccessRow()
                }
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }

            item { SectionHeader("Sync") }
            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.Sync,
                    title = "Auto Sync",
                    subtitle = "Upload pending files in the background",
                    checked = state.autoSyncEnabled,
                    onCheckedChange = viewModel::setAutoSync,
                )
            }
            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.Sync,
                    title = "Wi-Fi only",
                    subtitle = "Sync only on unmetered networks",
                    checked = state.syncWifiOnly,
                    onCheckedChange = viewModel::setSyncWifiOnly,
                )
            }
            item {
                val currentLabel = if (state.batchSizePerBot == 0) {
                    "Auto (${state.detectedBatchSize} files/bot)"
                } else {
                    "${state.batchSizePerBot} files/bot"
                }
                BatchSizeRow(
                    currentLabel = currentLabel,
                    selected = state.batchSizePerBot,
                    onSelect = viewModel::setBatchSizePerBot,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }

            item { SectionHeader("Security") }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Security,
                    title = "Security",
                    subtitle = "Encryption and passphrase settings",
                    onClick = onNavigateToSecurity,
                )
            }
            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.Lock,
                    title = "App Lock",
                    subtitle = if (state.appLockEnabled) "Passphrase required on launch" else "No lock on launch",
                    checked = state.appLockEnabled,
                    onCheckedChange = viewModel::setAppLockEnabled,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }

            item { SectionHeader("Appearance") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Brush,
                    title = "Theme",
                    subtitle = state.themeMode.label,
                )
            }
            item {
                ThemeModeSelector(
                    selected = state.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
            }
            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.Palette,
                    title = "Dynamic color",
                    subtitle = "Use wallpaper colors when supported",
                    checked = state.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }

            item { SectionHeader("More") }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Folder,
                    title = "Folders",
                    subtitle = "Organize files into folders",
                    onClick = onNavigateToFolders,
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Delete,
                    title = "Trash",
                    subtitle = "Deleted files (auto-purged after 30 days)",
                    onClick = onNavigateToTrash,
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Backup,
                    title = "Backup & Restore",
                    subtitle = "Create and restore database backups",
                    onClick = onNavigateToBackup,
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.PieChart,
                    title = "Storage Stats",
                    subtitle = "View storage usage and file breakdown",
                    onClick = onNavigateToStats,
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "About",
                    subtitle = "Version info and help",
                    onClick = onNavigateToAbout,
                )
            }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(text = title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(text = subtitle, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = trailing,
    )
}

@Composable
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(text = title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(text = subtitle, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(mode) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                RadioButton(selected = mode == selected, onClick = { onSelect(mode) })
            }
        }
    }
}

@Composable
private fun BatchSizeRow(
    currentLabel: String,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        0 to "Auto (device capability)",
        1 to "1 file per bot (low memory)",
        2 to "2 files per bot",
        3 to "3 files per bot",
        4 to "4 files per bot",
        5 to "5 files per bot",
        8 to "8 files per bot (high-end)",
        10 to "10 files per bot",
    )

    Column {
        ListItem(
            headlineContent = {
                Text("Batch Size", style = MaterialTheme.typography.bodyLarge)
            },
            supportingContent = {
                Text(currentLabel, style = MaterialTheme.typography.bodyMedium)
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Speed,
                    contentDescription = null,
                )
            },
            modifier = Modifier.clickable { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                    leadingIcon = {
                        if (value == selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun maskToken(token: String, visible: Boolean): String {
    if (visible) return token
    if (token.length <= 8) return "****"
    val head = token.take(4)
    val tail = token.takeLast(4)
    return "$head****$tail"
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = {
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
        },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null)
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Full Storage Access toggle for Android 11+ (MANAGE_EXTERNAL_STORAGE).
 * Required to access all documents, not just media files.
 */
@Composable
private fun FullStorageAccessRow() {
    val context = LocalContext.current
    var hasFullAccess by remember { mutableStateOf(StoragePermissionHelper.hasFullStorageAccess()) }
    
    // Refresh status when composable becomes visible
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { }
    }
    
    ListItem(
        headlineContent = { 
            Text(text = "Full Storage Access", style = MaterialTheme.typography.bodyLarge) 
        },
        supportingContent = { 
            Text(
                text = if (hasFullAccess) 
                    "Enabled — can access all files including documents" 
                else 
                    "Required to browse and backup documents",
                style = MaterialTheme.typography.bodyMedium,
            ) 
        },
        leadingContent = { 
            Icon(imageVector = Icons.Outlined.Folder, contentDescription = null) 
        },
        trailingContent = {
            if (hasFullAccess) {
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = "Enabled",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = {
                    (context as? Activity)?.let { activity ->
                        StoragePermissionHelper.requestFullStorageAccess(activity)
                    }
                }) {
                    Text("Enable")
                }
            }
        },
        modifier = Modifier.clickable {
            if (!hasFullAccess) {
                (context as? Activity)?.let { activity ->
                    StoragePermissionHelper.requestFullStorageAccess(activity)
                }
            }
        },
    )
}
