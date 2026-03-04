package com.tgstorage.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tgstorage.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteById(folderId: Long)

    /** Get all root-level folders (no parent) */
    @Query("SELECT * FROM folders WHERE parent_id IS NULL ORDER BY name ASC")
    fun getRootFolders(): Flow<List<FolderEntity>>

    /** Get subfolders of a given parent */
    @Query("SELECT * FROM folders WHERE parent_id = :parentId ORDER BY name ASC")
    fun getSubFolders(parentId: Long): Flow<List<FolderEntity>>

    /** Get folder by ID */
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    /** Get all folders (for move-to picker) */
    @Query("SELECT * FROM folders ORDER BY name ASC")
    suspend fun getAllFoldersSync(): List<FolderEntity>

    /** Get all folders as Flow */
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    /** Count files in a folder */
    @Query("SELECT COUNT(*) FROM files WHERE folder_id = :folderId")
    suspend fun getFileCount(folderId: Long): Int

    /** Count subfolders */
    @Query("SELECT COUNT(*) FROM folders WHERE parent_id = :parentId")
    suspend fun getSubFolderCount(parentId: Long): Int
}
