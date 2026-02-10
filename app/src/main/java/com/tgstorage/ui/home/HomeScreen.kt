package com.tgstorage.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tgstorage.ui.components.EmptyState

@Composable
fun HomeScreen(
    onNavigateToUpload: () -> Unit,
    onNavigateToFileDetail: (Long) -> Unit,
) {
    // Phase 3 will populate this with the real file browser
    Box(modifier = Modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Outlined.Folder,
            title = "No files yet",
            subtitle = "Upload your first file to get started",
        )
        FloatingActionButton(
            onClick = onNavigateToUpload,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Upload file",
            )
        }
    }
}
