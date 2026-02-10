package com.tgstorage.ui.filedetail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tgstorage.ui.components.ScreenStub

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailScreen(
    fileId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToDownload: () -> Unit,
) {
    // Phase 3 will implement the full file detail / preview
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File Detail") },
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
        ScreenStub(
            title = "File Detail",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
