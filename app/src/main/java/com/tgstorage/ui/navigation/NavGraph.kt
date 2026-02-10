package com.tgstorage.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tgstorage.ui.about.AboutScreen
import com.tgstorage.ui.backup.BackupRestoreScreen
import com.tgstorage.ui.download.DownloadScreen
import com.tgstorage.ui.filedetail.FileDetailScreen
import com.tgstorage.ui.home.HomeScreen
import com.tgstorage.ui.onboarding.OnboardingScreen
import com.tgstorage.ui.security.SecurityScreen
import com.tgstorage.ui.settings.SettingsScreen
import com.tgstorage.ui.splash.SplashScreen
import com.tgstorage.ui.stats.StorageStatsScreen
import com.tgstorage.ui.sync.SyncDashboardScreen
import com.tgstorage.ui.transfers.TransferQueueScreen
import com.tgstorage.ui.upload.UploadScreen

@Composable
fun TgStorageNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Splash.route,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Screen.Splash.route) {
            SplashScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToOnboarding = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToUpload = { navController.navigate(Screen.Upload.route) },
                onNavigateToFileDetail = { fileId ->
                    navController.navigate(Screen.FileDetail.createRoute(fileId))
                },
            )
        }

        composable(
            route = Screen.FileDetail.route,
            arguments = listOf(navArgument("fileId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: return@composable
            FileDetailScreen(
                fileId = fileId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDownload = {
                    navController.navigate(Screen.Download.createRoute(fileId))
                },
            )
        }

        composable(Screen.Upload.route) {
            UploadScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToFileDetail = { fileId ->
                    navController.navigate(Screen.FileDetail.createRoute(fileId)) {
                        popUpTo(Screen.Upload.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Screen.Download.route,
            arguments = listOf(navArgument("fileId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: return@composable
            DownloadScreen(
                fileId = fileId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.TransferQueue.route) {
            TransferQueueScreen()
        }

        composable(Screen.SyncDashboard.route) {
            SyncDashboardScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.BackupRestore.route) {
            BackupRestoreScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToSecurity = { navController.navigate(Screen.Security.route) },
                onNavigateToAbout = { navController.navigate(Screen.About.route) },
                onNavigateToBackup = { navController.navigate(Screen.BackupRestore.route) },
                onNavigateToSync = { navController.navigate(Screen.SyncDashboard.route) },
                onNavigateToStats = { navController.navigate(Screen.StorageStats.route) },
            )
        }

        composable(Screen.Security.route) {
            SecurityScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.StorageStats.route) {
            StorageStatsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
