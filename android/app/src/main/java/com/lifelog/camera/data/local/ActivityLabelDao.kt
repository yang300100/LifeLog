package com.lifelog.camera.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifelog.camera.data.local.entity.ActivityLabelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityLabelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(labels: List<ActivityLabelEntity>)

    @Query("SELECT * FROM activity_labels WHERE clipId = :clipId ORDER BY timestamp ASC")
    suspend fun getByClipId(clipId: Long): List<ActivityLabelEntity>

    @Query("SELECT * FROM activity_labels WHERE timestamp BETWEEN :startOfDay AND :endOfDay ORDER BY timestamp ASC")
    suspend fun getByDateRange(startOfDay: Long, endOfDay: Long): List<ActivityLabelEntity>

    @Query("SELECT * FROM activity_labels WHERE timestamp BETWEEN :startOfDay AND :endOfDay ORDER BY timestamp ASC")
    fun getByDateRangeFlow(startOfDay: Long, endOfDay: Long): Flow<List<ActivityLabelEntity>>

    @Query("DELETE FROM activity_labels WHERE clipId = :clipId")
    suspend fun deleteByClipId(clipId: Long)
}
