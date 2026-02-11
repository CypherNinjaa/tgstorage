package com.tgstorage.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoFile
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgstorage.data.local.dao.FileTypeStats
import com.tgstorage.data.local.entity.FileEntity
import com.tgstorage.ui.components.EmptyState
import com.tgstorage.ui.components.ErrorState
import com.tgstorage.ui.components.LoadingState

private val chartColors = listOf(
    Color(0xFF4285F4), // Blue — Images
    Color(0xFFEA4335), // Red — Videos
    Color(0xFFFBBC04), // Yellow — Audio
    Color(0xFF34A853), // Green — Documents
    Color(0xFFFF6D01), // Orange — Archives
    Color(0xFF9C27B0), // Purple — Other
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageStatsScreen(
    onNavigateBack: () -> Unit,
    viewModel: StorageStatsViewModel = viewModel(factory = StorageStatsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage Stats") },
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
        when (val s = state) {
            is StatsUiState.Loading -> LoadingState(
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            is StatsUiState.Error -> ErrorState(
                modifier = Modifier.fillMaxSize().padding(padding),
                message = s.message,
                onRetry = viewModel::retry,
            )
            is StatsUiState.Loaded -> {
                if (s.totalFiles == 0) {
                    EmptyState(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        icon = Icons.Outlined.Storage,
                        title = "No files yet",
                        subtitle = "Upload files to see storage statistics",
                    )
                } else {
                    LoadedContent(
                        state = s,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadedContent(
    state: StatsUiState.Loaded,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // ── Donut Chart + Overview ──
        item {
            DonutChartSection(
                typeBreakdown = state.typeBreakdown,
                totalSize = state.totalSize,
            )
        }

        // ── Summary Cards ──
        item {
            SummaryCardsRow(state)
        }

        // ── File Type Breakdown ──
        item {
            SectionHeader("Storage by Type")
        }

        if (state.typeBreakdown.isEmpty()) {
            item {
                Text(
                    text = "No file type data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            itemsIndexed(state.typeBreakdown) { index, stats ->
                FileTypeRow(
                    stats = stats,
                    color = chartColors[index % chartColors.size],
                    totalSize = state.totalSize,
                )
            }
        }

        // ── Largest Files ──
        item {
            Spacer(Modifier.height(8.dp))
            SectionHeader("Largest Files")
        }

        if (state.largestFiles.isEmpty()) {
            item {
                Text(
                    text = "No files to display",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            itemsIndexed(state.largestFiles) { _, file ->
                LargestFileRow(file)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ── Donut Chart ──────────────────────────────────────

@Composable
private fun DonutChartSection(
    typeBreakdown: List<FileTypeStats>,
    totalSize: Long,
) {
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        animProgress.animateTo(1f, animationSpec = tween(durationMillis = 800))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(200.dp)) {
                val strokeWidth = 28.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset(
                    (size.width - diameter) / 2,
                    (size.height - diameter) / 2,
                )
                val arcSize = Size(diameter, diameter)

                if (typeBreakdown.isEmpty() || totalSize == 0L) {
                    // Empty ring
                    drawArc(
                        color = Color.LightGray.copy(alpha = 0.3f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                } else {
                    var startAngle = -90f
                    typeBreakdown.forEachIndexed { index, stats ->
                        val sweep = (stats.totalSize.toFloat() / totalSize) * 360f * animProgress.value
                        drawArc(
                            color = chartColors[index % chartColors.size],
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                        )
                        startAngle += sweep
                    }
                }
            }

            // Center text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatSize(totalSize),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Summary Cards ────────────────────────────────────

@Composable
private fun SummaryCardsRow(state: StatsUiState.Loaded) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Cloud,
            label = "Telegram",
            value = formatSize(state.uploadedSize),
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.PhoneAndroid,
            label = "Local Cache",
            value = formatSize(state.localCacheSize),
        )
    }

    Spacer(Modifier.height(12.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Folder,
            label = "Files",
            value = "${state.totalFiles}",
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Storage,
            label = "Chunks",
            value = "${state.totalChunks}",
        )
    }

    Spacer(Modifier.height(8.dp))
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── File Type Breakdown ──────────────────────────────

@Composable
private fun FileTypeRow(
    stats: FileTypeStats,
    color: Color,
    totalSize: Long,
) {
    val percentage = if (totalSize > 0) {
        (stats.totalSize.toFloat() / totalSize * 100).toInt()
    } else 0

    ListItem(
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = color,
                modifier = Modifier.size(12.dp),
            ) {}
        },
        headlineContent = {
            Text(
                text = stats.category,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = "${stats.fileCount} file${if (stats.fileCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatSize(stats.totalSize),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
    )
}

// ── Largest Files ────────────────────────────────────

@Composable
private fun LargestFileRow(file: FileEntity) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = iconForMime(file.mimeType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                text = file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = file.mimeType,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Text(
                text = formatSize(file.size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
    )
}

// ── Section Header ───────────────────────────────────

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

// ── Helpers ──────────────────────────────────────────

private fun iconForMime(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Outlined.Image
    mime.startsWith("video/") -> Icons.Outlined.VideoFile
    mime.startsWith("audio/") -> Icons.Outlined.AudioFile
    mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") -> Icons.Outlined.Description
    mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("tar") || mime.contains("gzip") -> Icons.Outlined.Archive
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
