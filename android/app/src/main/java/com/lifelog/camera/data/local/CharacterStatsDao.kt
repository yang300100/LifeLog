package com.lifelog.camera.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifelog.camera.data.local.entity.CharacterStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterStatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: CharacterStatsEntity)

    @Query("SELECT * FROM character_stats WHERE timestamp BETWEEN :startOfDay AND :endOfDay ORDER BY timestamp ASC")
    suspend fun getByDateRange(startOfDay: Long, endOfDay: Long): List<CharacterStatsEntity>

    @Query("SELECT * FROM character_stats WHERE timestamp BETWEEN :startOfDay AND :endOfDay ORDER BY timestamp ASC")
    fun getTodayStatsFlow(startOfDay: Long, endOfDay: Long): Flow<List<CharacterStatsEntity>>

    @Query("SELECT * FROM character_stats ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): CharacterStatsEntity?

    @Query("SELECT * FROM character_stats ORDER BY timestamp DESC LIMIT 1")
    fun getLatestFlow(): Flow<CharacterStatsEntity?>
}
