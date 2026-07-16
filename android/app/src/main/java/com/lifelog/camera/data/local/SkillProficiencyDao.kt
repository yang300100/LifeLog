package com.lifelog.camera.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifelog.camera.data.local.entity.SkillProficiencyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillProficiencyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillProficiencyEntity)

    @Query("SELECT * FROM skill_proficiency ORDER BY exp DESC")
    suspend fun getAll(): List<SkillProficiencyEntity>

    @Query("SELECT * FROM skill_proficiency ORDER BY exp DESC")
    fun getAllFlow(): Flow<List<SkillProficiencyEntity>>

    @Query("SELECT * FROM skill_proficiency WHERE skillName = :skillName LIMIT 1")
    suspend fun getByName(skillName: String): SkillProficiencyEntity?

    @Query("DELETE FROM skill_proficiency WHERE skillName = :skillName")
    suspend fun deleteByName(skillName: String)
}
