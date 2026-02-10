package com.tgstorage.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToSecurity: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToStats: () -> Unit,
) {
    // Phase 6 will implement the full settings
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                SettingsItem(
                    icon = Icons.Outlined.Sync,
                    title = "Sync Dashboard",
                    subtitle = "View sync status and trigger manual sync",
                    onClick = onNavigateToSync,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Backup,
                    title = "Backup & Restore",
                    subtitle = "Create and restore database backups",
                    onClick = onNavigateToBackup,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Security,
                    title = "Security",
                    subtitle = "Encryption and passphrase settings",
                    onClick = onNavigateToSecurity,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
            item {
                SettingsItem(
                    icon = Icons.Outlined.PieChart,
                    title = "Storage Stats",
                    subtitle = "View storage usage and file breakdown",
                    onClick = onNavigateToStats,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "About",
                    subtitle = "Version info and help",
                    onClick = onNavigateToAbout,
                )
            }
        }
    }
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
