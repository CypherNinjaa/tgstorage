package com.tgstorage.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgstorage.data.local.entity.MetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataDao {

    @Query("SELECT value FROM metadata WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT value FROM metadata WHERE `key` = :key")
    fun observeValue(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setValue(metadata: MetadataEntity)

    @Query("DELETE FROM metadata WHERE `key` = :key")
    suspend fun deleteValue(key: String)
}
