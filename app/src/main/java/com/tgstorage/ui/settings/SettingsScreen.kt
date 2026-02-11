package com.tgstorage.ui.settings

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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgstorage.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToSecurity: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToStats: () -> Unit,
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
                    icon = Icons.Outlined.Sync,
                    title = "Sync Dashboard",
                    subtitle = "View sync status and trigger manual sync",
                    onClick = onNavigateToSync,
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
