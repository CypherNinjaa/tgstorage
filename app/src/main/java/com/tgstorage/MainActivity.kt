package com.tgstorage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.tgstorage.ui.shell.AppShell
import com.tgstorage.ui.theme.TgStorageTheme
import com.tgstorage.data.local.entity.MetadataKeys
import com.tgstorage.ui.settings.ThemeMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

            TgStorageTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppShell(navController = navController)
                }
            }
        }
    }
}
