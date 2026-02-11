package com.tgstorage.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgstorage.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onNavigateBack: () -> Unit,
    viewModel: SecurityViewModel = viewModel(factory = SecurityViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showWipeDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        state.message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
        state.error?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isWorking) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Encryption", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.encryptionEnabled) "Enabled" else "Disabled",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.encryptionEnabled,
                        onCheckedChange = viewModel::toggleEncryption,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Passphrase", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (state.passphraseSet) "Passphrase set" else "No passphrase",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    if (state.passphraseSet) {
                        OutlinedTextField(
                            value = state.currentPassphrase,
                            onValueChange = viewModel::updateCurrentPassphrase,
                            label = { Text("Current passphrase") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = passphraseTransform(state.isPassphraseVisible),
                            trailingIcon = {
                                IconButton(onClick = viewModel::togglePassphraseVisibility) {
                                    Icon(
                                        if (state.isPassphraseVisible) Icons.Filled.VisibilityOff
                                        else Icons.Filled.Visibility,
                                        "Toggle visibility",
                                    )
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = state.newPassphrase,
                        onValueChange = viewModel::updateNewPassphrase,
                        label = { Text(if (state.passphraseSet) "New passphrase" else "Set passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = passphraseTransform(state.isPassphraseVisible),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.confirmPassphrase,
                        onValueChange = viewModel::updateConfirmPassphrase,
                        label = { Text("Confirm passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = passphraseTransform(state.isPassphraseVisible),
                    )

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::savePassphrase,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (state.passphraseSet) "Change passphrase" else "Set passphrase")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Key info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Hardware-backed", if (state.isHardwareBacked) "Yes" else "No")
                    InfoRow(
                        "Created",
                        state.keyCreatedAt?.let { formatTime(it) } ?: "Unknown",
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Danger zone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Wipe all local data, cache, and metadata. Telegram files remain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showWipeDialog = true }) {
                        Icon(Icons.Filled.DeleteForever, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.size(8.dp))
                        Text("Wipe all data", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text("Wipe all data?") },
            text = { Text("This deletes all local files, cache, and database entries. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showWipeDialog = false
                    viewModel.wipeAllData()
                }) {
                    Text("Wipe")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun passphraseTransform(visible: Boolean): VisualTransformation {
    return if (visible) VisualTransformation.None else PasswordVisualTransformation()
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium)
    }
}

private fun formatTime(timestamp: Long): String {
    val dt = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
    return dt.format(java.util.Date(timestamp))
}
