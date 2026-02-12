package com.tgstorage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.tgstorage.ui.shell.AppShell
import com.tgstorage.ui.theme.TgStorageTheme
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.data.transfer.TransferManager
import com.tgstorage.data.updater.AppUpdater
import com.tgstorage.ui.settings.ThemeMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Resume any pending uploads from DB on app start
        TransferManager.resumePendingUploads()

        setContent {
            val db = TgStorageApp.instance.database
            val themeModeRaw by db.metadataDao().observeValue(MetadataKeys.THEME_MODE)
                .collectAsState(initial = null)
            val dynamicColorRaw by db.metadataDao().observeValue(MetadataKeys.DYNAMIC_COLOR)
                .collectAsState(initial = null)

            val themeMode = when (themeModeRaw) {
                ThemeMode.LIGHT.value -> ThemeMode.LIGHT
                ThemeMode.DARK.value -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            val dynamicColor = dynamicColorRaw?.toBoolean() ?: true

            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            // Auto-check for updates on launch
            val updater = remember { AppUpdater(this@MainActivity) }
            val updateState by updater.state.collectAsState()

            LaunchedEffect(Unit) {
                updater.checkForUpdate()
            }

            TgStorageTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppShell(navController = navController)

                    // Show update dialog when update is available
                    if (updateState is AppUpdater.UpdateState.UpdateAvailable) {
                        val available = updateState as AppUpdater.UpdateState.UpdateAvailable
                        UpdateDialog(
                            version = available.version,
                            releaseNotes = available.releaseNotes,
                            sizeBytes = available.sizeBytes,
                            onUpdate = { updater.downloadAndInstall(available.downloadUrl, available.version) },
                            onDismiss = { updater.resetState() },
                        )
                    }
                }
            }
        }
    }
}
