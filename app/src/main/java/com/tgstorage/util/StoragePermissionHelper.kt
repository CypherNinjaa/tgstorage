package com.tgstorage.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

/**
 * Modern storage permission helper for Android 11-14.
 * 
 * Handles:
 * - Android 13+ (API 33): Granular media permissions (READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO)
 * - Android 14+ (API 34): Partial photo picker (READ_MEDIA_VISUAL_USER_SELECTED)
 * - Android 11-12 (API 30-32): READ_EXTERNAL_STORAGE with scoped storage
 * - Android 11+ (API 30): MANAGE_EXTERNAL_STORAGE for full access (requires Settings redirect)
 * 
 * Best practices:
 * - Use Storage Access Framework (SAF) for user-selected files
 * - Use MediaStore for gallery backup
 * - Only request MANAGE_EXTERNAL_STORAGE when absolutely necessary
 */
object StoragePermissionHelper {

    // ═══════════════════════════════════════════════════════════════════════════
    // PERMISSION ARRAYS — For runtime requests
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Permissions required for media (gallery) access.
     * Returns appropriate permissions based on Android version.
     */
    fun getMediaPermissions(): Array<String> {
        return when {
            // Android 14+ (API 34): Include partial access permission
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            )
            // Android 13 (API 33): Granular media permissions
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
            // Android 10-12 (API 29-32): Legacy READ_EXTERNAL_STORAGE
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Permissions for images only (photo picker alternative).
     */
    fun getImagePermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Permissions for video only.
     */
    fun getVideoPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Permissions for audio files only.
     */
    fun getAudioPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERMISSION CHECKS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if all required media permissions are granted.
     */
    fun hasMediaPermissions(context: Context): Boolean {
        return getMediaPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if any media permission is granted (partial access on Android 14+).
     */
    fun hasAnyMediaPermission(context: Context): Boolean {
        return getMediaPermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if image permission is granted.
     */
    fun hasImagePermission(context: Context): Boolean {
        return getImagePermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if video permission is granted.
     */
    fun hasVideoPermission(context: Context): Boolean {
        return getVideoPermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if audio permission is granted.
     */
    fun hasAudioPermission(context: Context): Boolean {
        return getAudioPermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if full storage access is granted (Android 11+).
     * This requires MANAGE_EXTERNAL_STORAGE and user enabling it in Settings.
     */
    fun hasFullStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Below Android 11, READ_EXTERNAL_STORAGE effectively gives full access
            true
        }
    }

    /**
     * Check if user has granted partial photo access (Android 14+).
     * Returns true if only some photos are selected instead of full access.
     */
    fun hasPartialPhotoAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        
        val fullAccess = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        
        val partialAccess = ContextCompat.checkSelfPermission(
            context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ) == PackageManager.PERMISSION_GRANTED
        
        return partialAccess && !fullAccess
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERMISSION REQUESTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Request media permissions using the provided launcher.
     */
    fun requestMediaPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(getMediaPermissions())
    }

    /**
     * Request image-only permissions.
     */
    fun requestImagePermissions(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(getImagePermissions())
    }

    /**
     * Request video-only permissions.
     */
    fun requestVideoPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(getVideoPermissions())
    }

    /**
     * Request audio-only permissions.
     */
    fun requestAudioPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(getAudioPermissions())
    }

    /**
     * Open Settings screen to enable MANAGE_EXTERNAL_STORAGE.
     * Required for full file access on Android 11+.
     * 
     * NOTE: This should only be used when absolutely necessary (e.g., file manager apps).
     * For most apps, SAF and MediaStore are preferred.
     */
    fun requestFullStorageAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to general storage settings
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                activity.startActivity(fallbackIntent)
            }
        }
    }

    /**
     * Open app settings for permission management.
     * Useful when user has denied permissions permanently.
     */
    fun openAppSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        activity.startActivity(intent)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if we should show rationale for permissions.
     * Returns true if user has previously denied the permission.
     */
    fun shouldShowRationale(activity: Activity): Boolean {
        return getMediaPermissions().any {
            activity.shouldShowRequestPermissionRationale(it)
        }
    }

    /**
     * Check if permissions were permanently denied.
     * Returns true if user denied and selected "Don't ask again".
     */
    fun isPermanentlyDenied(activity: Activity, grantResults: Map<String, Boolean>): Boolean {
        val anyDenied = grantResults.values.any { !it }
        val shouldShowRationale = shouldShowRationale(activity)
        // If denied but rationale not shown, user selected "Don't ask again"
        return anyDenied && !shouldShowRationale
    }

    /**
     * Get a user-friendly description of current permission status.
     */
    fun getPermissionStatusDescription(context: Context): String {
        return when {
            hasFullStorageAccess() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> 
                "Full storage access granted"
            hasMediaPermissions(context) -> 
                "Media access granted"
            hasPartialPhotoAccess(context) -> 
                "Partial photo access (some photos selected)"
            hasAnyMediaPermission(context) -> 
                "Limited media access"
            else -> 
                "No storage permissions"
        }
    }

    /**
     * Returns the minimum Android SDK that supports the current app's storage approach.
     */
    fun getMinSupportedSdk(): Int = Build.VERSION_CODES.Q // Android 10

    /**
     * Check if the device supports the photo picker (Android 13+).
     */
    fun supportsPhotoPicker(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
