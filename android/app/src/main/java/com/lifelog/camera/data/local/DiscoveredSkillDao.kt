package com.lifelog.camera.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifelog.camera.data.local.entity.DiscoveredSkillEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiscoveredSkillDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: DiscoveredSkillEntity)

    @Query("SELECT * FROM discovered_skills WHERE discoveredAt BETWEEN :startOfDay AND :endOfDay ORDER BY discoveredAt DESC")
    suspend fun getTodayDiscovered(startOfDay: Long, endOfDay: Long): List<DiscoveredSkillEntity>

    @Query("SELECT * FROM discovered_skills WHERE discoveredAt BETWEEN :startOfDay AND :endOfDay ORDER BY discoveredAt DESC")
    fun getTodayDiscoveredFlow(startOfDay: Long, endOfDay: Long): Flow<List<DiscoveredSkillEntity>>
}
