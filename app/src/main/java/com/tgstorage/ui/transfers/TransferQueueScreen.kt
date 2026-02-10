package com.tgstorage.ui.transfers

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tgstorage.ui.components.EmptyState

@Composable
fun TransferQueueScreen() {
    // Phase 4 will implement the full transfer queue
    EmptyState(
        icon = Icons.Outlined.SwapVert,
        title = "No active transfers",
        subtitle = "Your uploads and downloads will appear here",
        modifier = Modifier.fillMaxSize(),
    )
}
