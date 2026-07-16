package com.lifelog.camera.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifelog.camera.data.local.entity.VideoClipEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoClipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clip: VideoClipEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(clips: List<VideoClipEntity>)

    @Query("SELECT * FROM video_clips ORDER BY capturedAt DESC")
    fun getAllFlow(): Flow<List<VideoClipEntity>>

    @Query("SELECT * FROM video_clips WHERE capturedAt BETWEEN :startOfDay AND :endOfDay ORDER BY capturedAt ASC")
    suspend fun getByDateRange(startOfDay: Long, endOfDay: Long): List<VideoClipEntity>

    @Query("SELECT MAX(id) FROM video_clips")
    suspend fun getMaxId(): Long?

    @Query("SELECT * FROM video_clips WHERE analyzed = 0 ORDER BY capturedAt ASC LIMIT :limit")
    suspend fun getUnanalyzed(limit: Int = 10): List<VideoClipEntity>

    @Query("UPDATE video_clips SET analyzed = 1 WHERE id IN (:ids)")
    suspend fun markAnalyzed(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM video_clips WHERE capturedAt BETWEEN :startOfDay AND :endOfDay")
    suspend fun countByDate(startOfDay: Long, endOfDay: Long): Int

    /** 获取今日全部视频（含已分析），用于重新分析 */
    @Query("SELECT * FROM video_clips WHERE capturedAt BETWEEN :startOfDay AND :endOfDay ORDER BY capturedAt ASC")
    suspend fun getTodayClips(startOfDay: Long, endOfDay: Long): List<VideoClipEntity>

    /** 重置指定视频的分析状态 */
    @Query("UPDATE video_clips SET analyzed = 0 WHERE id IN (:ids)")
    suspend fun resetAnalyzed(ids: List<Long>)

    @Query("SELECT * FROM video_clips WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VideoClipEntity?

    @Query("DELETE FROM video_clips WHERE id = :id")
    suspend fun deleteById(id: Long)
}
