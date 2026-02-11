package com.tgstorage.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Helper class for querying media files using MediaStore API.
 * 
 * Supports:
 * - Images, videos, and audio files
 * - Android 10+ scoped storage compliance
 * - Efficient batch querying for gallery backup
 * - Safe file access via content URIs
 */
class MediaStoreHelper(private val context: Context) {

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    data class MediaFile(
        val id: Long,
        val name: String,
        val path: String,
        val size: Long,
        val mimeType: String,
        val dateModified: Long,
        val dateAdded: Long,
        val contentUri: Uri,
        val mediaType: MediaType,
    )

    enum class MediaType { IMAGE, VIDEO, AUDIO }

    // ═══════════════════════════════════════════════════════════════════════════
    // IMAGE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all images from the device.
     * @param sortByDateDesc Sort by date modified descending (newest first)
     * @param limit Maximum number of results (0 = unlimited)
     * @param sinceTimestamp Only return files modified after this Unix timestamp (seconds)
     */
    suspend fun getImages(
        sortByDateDesc: Boolean = true,
        limit: Int = 0,
        sinceTimestamp: Long = 0,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        queryMedia(
            collection = getImagesUri(),
            mediaType = MediaType.IMAGE,
            sortByDateDesc = sortByDateDesc,
            limit = limit,
            sinceTimestamp = sinceTimestamp,
        )
    }

    /**
     * Get images from a specific album/folder.
     * @param bucketId The album's bucket ID from MediaStore
     */
    suspend fun getImagesFromAlbum(bucketId: Long): List<MediaFile> = withContext(Dispatchers.IO) {
        queryMedia(
            collection = getImagesUri(),
            mediaType = MediaType.IMAGE,
            selection = "${MediaStore.Images.Media.BUCKET_ID} = ?",
            selectionArgs = arrayOf(bucketId.toString()),
        )
    }

    /**
     * Get list of image albums/folders.
     */
    suspend fun getImageAlbums(): List<Album> = withContext(Dispatchers.IO) {
        getAlbums(getImagesUri(), MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VIDEO QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all videos from the device.
     */
    suspend fun getVideos(
        sortByDateDesc: Boolean = true,
        limit: Int = 0,
        sinceTimestamp: Long = 0,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        queryMedia(
            collection = getVideosUri(),
            mediaType = MediaType.VIDEO,
            sortByDateDesc = sortByDateDesc,
            limit = limit,
            sinceTimestamp = sinceTimestamp,
        )
    }

    /**
     * Get videos from a specific album/folder.
     */
    suspend fun getVideosFromAlbum(bucketId: Long): List<MediaFile> = withContext(Dispatchers.IO) {
        queryMedia(
            collection = getVideosUri(),
            mediaType = MediaType.VIDEO,
            selection = "${MediaStore.Video.Media.BUCKET_ID} = ?",
            selectionArgs = arrayOf(bucketId.toString()),
        )
    }

    /**
     * Get list of video albums/folders.
     */
    suspend fun getVideoAlbums(): List<Album> = withContext(Dispatchers.IO) {
        getAlbums(getVideosUri(), MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUDIO QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all audio files from the device.
     */
    suspend fun getAudioFiles(
        sortByDateDesc: Boolean = true,
        limit: Int = 0,
        sinceTimestamp: Long = 0,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        queryMedia(
            collection = getAudioUri(),
            mediaType = MediaType.AUDIO,
            sortByDateDesc = sortByDateDesc,
            limit = limit,
            sinceTimestamp = sinceTimestamp,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMBINED QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all media files (images, videos, audio) for gallery backup.
     * @param sinceTimestamp Only return files modified after this timestamp
     */
    suspend fun getAllMedia(
        sortByDateDesc: Boolean = true,
        sinceTimestamp: Long = 0,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MediaFile>()
        results.addAll(getImages(sortByDateDesc, sinceTimestamp = sinceTimestamp))
        results.addAll(getVideos(sortByDateDesc, sinceTimestamp = sinceTimestamp))
        results.addAll(getAudioFiles(sortByDateDesc, sinceTimestamp = sinceTimestamp))
        
        if (sortByDateDesc) {
            results.sortedByDescending { it.dateModified }
        } else {
            results.sortedBy { it.dateModified }
        }
    }

    /**
     * Get media files modified since a specific time (for incremental backup).
     */
    suspend fun getNewMediaSince(timestamp: Long): List<MediaFile> {
        return getAllMedia(sinceTimestamp = timestamp)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILE ACCESS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Copy a media file to the app's private storage (safe for upload).
     * Use this when you need direct File access for upload operations.
     * 
     * @param mediaFile The media file to copy
     * @param destDir Destination directory (e.g., context.cacheDir)
     * @return The copied File, or null if copy failed
     */
    suspend fun copyToPrivateStorage(mediaFile: MediaFile, destDir: File): File? = withContext(Dispatchers.IO) {
        try {
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, mediaFile.name)
            
            context.contentResolver.openInputStream(mediaFile.contentUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            destFile.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get an InputStream for reading a media file.
     * Caller is responsible for closing the stream.
     */
    fun openInputStream(contentUri: Uri) = context.contentResolver.openInputStream(contentUri)

    /**
     * Check if a content URI is still accessible.
     */
    fun isAccessible(contentUri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(contentUri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ALBUMS
    // ═══════════════════════════════════════════════════════════════════════════

    data class Album(
        val id: Long,
        val name: String,
        val count: Int,
    )

    private fun getAlbums(uri: Uri, bucketIdColumn: String, bucketNameColumn: String): List<Album> {
        val albums = mutableMapOf<Long, Pair<String, Int>>()
        
        val projection = arrayOf(bucketIdColumn, bucketNameColumn)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(bucketIdColumn)
            val nameCol = cursor.getColumnIndexOrThrow(bucketNameColumn)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Unknown"
                val current = albums[id]
                albums[id] = if (current != null) {
                    current.first to (current.second + 1)
                } else {
                    name to 1
                }
            }
        }
        
        return albums.map { (id, pair) -> Album(id, pair.first, pair.second) }
            .sortedByDescending { it.count }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun getImagesUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun getVideosUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun getAudioUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun queryMedia(
        collection: Uri,
        mediaType: MediaType,
        sortByDateDesc: Boolean = true,
        limit: Int = 0,
        sinceTimestamp: Long = 0,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        
        // Build selection
        val selectionParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        
        selectionParts.add("${MediaStore.MediaColumns.SIZE} > 0")
        
        if (sinceTimestamp > 0) {
            selectionParts.add("${MediaStore.MediaColumns.DATE_MODIFIED} > ?")
            args.add(sinceTimestamp.toString())
        }
        
        if (selection != null) {
            selectionParts.add("($selection)")
            selectionArgs?.let { args.addAll(it) }
        }
        
        val finalSelection = selectionParts.joinToString(" AND ")
        val finalArgs = args.toTypedArray()
        
        val sortOrder = buildString {
            append("${MediaStore.MediaColumns.DATE_MODIFIED} ")
            append(if (sortByDateDesc) "DESC" else "ASC")
            if (limit > 0) append(" LIMIT $limit")
        }
        
        try {
            context.contentResolver.query(
                collection,
                projection,
                finalSelection,
                finalArgs,
                sortOrder,
            )?.use { cursor ->
                files.addAll(cursorToMediaFiles(cursor, collection, mediaType))
            }
        } catch (e: Exception) {
            // Log error but return empty list
        }
        
        return files
    }

    private fun cursorToMediaFiles(cursor: Cursor, collection: Uri, mediaType: MediaType): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: continue
            val path = cursor.getString(dataCol) ?: ""
            val size = cursor.getLong(sizeCol)
            val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
            val dateModified = cursor.getLong(dateModifiedCol)
            val dateAdded = cursor.getLong(dateAddedCol)
            
            val contentUri = ContentUris.withAppendedId(collection, id)
            
            files.add(MediaFile(
                id = id,
                name = name,
                path = path,
                size = size,
                mimeType = mime,
                dateModified = dateModified,
                dateAdded = dateAdded,
                contentUri = contentUri,
                mediaType = mediaType,
            ))
        }
        
        return files
    }
}
