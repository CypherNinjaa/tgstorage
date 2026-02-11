package com.tgstorage.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tgstorage.data.local.entity.BotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BotDao {

    // ─── Insert / Update ───────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bot: BotEntity): Long

    @Update
    suspend fun update(bot: BotEntity)

    @Delete
    suspend fun delete(bot: BotEntity)

    @Query("DELETE FROM bots WHERE id = :botId")
    suspend fun deleteById(botId: Long)

    // ─── Queries ───────────────────────────────────────

    @Query("SELECT * FROM bots ORDER BY is_primary DESC, created_at ASC")
    fun getAllBots(): Flow<List<BotEntity>>

    @Query("SELECT * FROM bots ORDER BY is_primary DESC, created_at ASC")
    suspend fun getAllBotsOnce(): List<BotEntity>

    @Query("SELECT * FROM bots WHERE is_active = 1 AND is_verified = 1 ORDER BY is_primary DESC, created_at ASC")
    suspend fun getActiveBots(): List<BotEntity>

    @Query("SELECT * FROM bots WHERE is_active = 1 AND is_verified = 1 ORDER BY is_primary DESC, created_at ASC")
    fun getActiveBotsFlow(): Flow<List<BotEntity>>

    @Query("SELECT * FROM bots WHERE id = :botId")
    suspend fun getById(botId: Long): BotEntity?

    @Query("SELECT * FROM bots WHERE is_primary = 1 LIMIT 1")
    suspend fun getPrimaryBot(): BotEntity?

    @Query("SELECT COUNT(*) FROM bots")
    fun getBotCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM bots WHERE is_active = 1 AND is_verified = 1")
    suspend fun getActiveBotCount(): Int

    // ─── Update operations ─────────────────────────────

    @Query("UPDATE bots SET is_active = :isActive WHERE id = :botId")
    suspend fun setActive(botId: Long, isActive: Boolean)

    @Query("UPDATE bots SET is_verified = :isVerified, verified_at = :verifiedAt WHERE id = :botId")
    suspend fun setVerified(botId: Long, isVerified: Boolean, verifiedAt: Long?)

    @Query("UPDATE bots SET is_primary = 0")
    suspend fun clearPrimaryFlag()

    @Query("UPDATE bots SET is_primary = 1 WHERE id = :botId")
    suspend fun setPrimary(botId: Long)

    // ─── Check if token exists ─────────────────────────

    @Query("SELECT COUNT(*) FROM bots WHERE token_encrypted = :encryptedToken")
    suspend fun countByToken(encryptedToken: String): Int
}
