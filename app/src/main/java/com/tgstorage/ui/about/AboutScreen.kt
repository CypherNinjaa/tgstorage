package com.tgstorage.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── App Header ──
            item { AppHeader() }

            // ── How It Works ──
            item { SectionHeader("How It Works") }
            item { HowItWorksSection() }

            // ── App Info ──
            item { SectionHeader("App Info") }
            item { AppInfoSection() }

            // ── Open Source Licenses ──
            item { SectionHeader("Open Source") }
            item { LicensesSection() }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.CloudQueue,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "TgStorage",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Version 1.0.0 (Build 1)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Free cloud storage powered by Telegram",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HowItWorksSection() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            HowItWorksStep(
                step = 1,
                icon = Icons.Outlined.SmartToy,
                title = "Create a Telegram Bot",
                description = "Use @BotFather to create a bot. The bot token connects TgStorage to Telegram.",
            )
            StepConnector()
            HowItWorksStep(
                step = 2,
                icon = Icons.Outlined.Cloud,
                title = "Set Up a Private Channel",
                description = "Create a private Telegram channel and add your bot as an admin. This channel becomes your cloud drive.",
            )
            StepConnector()
            HowItWorksStep(
                step = 3,
                icon = Icons.Outlined.CloudUpload,
                title = "Upload Files",
                description = "Pick any file from your device. TgStorage encrypts it, splits large files into chunks, and uploads to your channel.",
            )
            StepConnector()
            HowItWorksStep(
                step = 4,
                icon = Icons.Outlined.Lock,
                title = "Encrypted at Rest",
                description = "All files are encrypted with AES-256-GCM before leaving your device. Only you can decrypt them.",
            )
            StepConnector()
            HowItWorksStep(
                step = 5,
                icon = Icons.Outlined.CloudDownload,
                title = "Download Anywhere",
                description = "Download and decrypt your files on any device with TgStorage and your bot token.",
            )
        }
    }
}

@Composable
private fun HowItWorksStep(
    step: Int,
    icon: ImageVector,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Step number circle
        Card(
            modifier = Modifier.size(36.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "$step",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepConnector() {
    Spacer(Modifier.height(4.dp))
    Row {
        Spacer(Modifier.width(17.dp)) // Center under the 36dp circle
        HorizontalDivider(
            modifier = Modifier
                .width(2.dp)
                .height(16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun AppInfoSection() {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        InfoRow(label = "Version", value = "1.0.0")
        InfoRow(label = "Build", value = "1")
        InfoRow(label = "Platform", value = "Android (API 26+)")
        InfoRow(label = "Language", value = "Kotlin")
        InfoRow(label = "UI Framework", value = "Jetpack Compose + Material 3")
        InfoRow(label = "Encryption", value = "AES-256-GCM")
        InfoRow(label = "Storage", value = "Telegram Bot API")
        InfoRow(label = "Local DB", value = "Room (SQLite)")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    ListItem(
        headlineContent = {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun LicensesSection() {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            ListItem(
                headlineContent = {
                    Text(
                        text = "Open Source Licenses",
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Gavel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                        )
                    }
                },
                modifier = Modifier.clickable { expanded = !expanded },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LicenseItem("Jetpack Compose", "Apache License 2.0")
                    LicenseItem("Material 3", "Apache License 2.0")
                    LicenseItem("Room Database", "Apache License 2.0")
                    LicenseItem("Navigation Compose", "Apache License 2.0")
                    LicenseItem("WorkManager", "Apache License 2.0")
                    LicenseItem("OkHttp", "Apache License 2.0")
                    LicenseItem("Kotlin Serialization", "Apache License 2.0")
                    LicenseItem("Coil", "Apache License 2.0")
                    LicenseItem("Material Icons Extended", "Apache License 2.0")
                }
            }
        }
    }

    // Source code link
    val context = LocalContext.current
    ListItem(
        headlineContent = {
            Text(
                text = "Source Code",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = "View on GitHub",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com"))
                context.startActivity(intent)
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun LicenseItem(name: String, license: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}
