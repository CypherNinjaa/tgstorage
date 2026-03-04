package com.tgstorage.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Onboarding : Screen("onboarding")
    data object Lock : Screen("lock")
    data object Home : Screen("home")
    data object FileDetail : Screen("file_detail/{fileId}") {
        fun createRoute(fileId: Long): String = "file_detail/$fileId"
    }
    data object Upload : Screen("upload")
    data object Download : Screen("download/{fileId}") {
        fun createRoute(fileId: Long): String = "download/$fileId"
    }
    data object TransferQueue : Screen("transfer_queue")
    data object SyncDashboard : Screen("sync_dashboard")
    data object BackupRestore : Screen("backup_restore")
    data object Settings : Screen("settings")
    data object BotSettings : Screen("bot_settings")
    data object Security : Screen("security")
    data object StorageStats : Screen("storage_stats")
    data object About : Screen("about")
    data object Folders : Screen("folders")
    data object Trash : Screen("trash")
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(
        screen = Screen.Home,
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    BottomNavItem(
        screen = Screen.TransferQueue,
        label = "Transfers",
        selectedIcon = Icons.Filled.SwapVert,
        unselectedIcon = Icons.Outlined.SwapVert,
    ),
    BottomNavItem(
        screen = Screen.Settings,
        label = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
)
