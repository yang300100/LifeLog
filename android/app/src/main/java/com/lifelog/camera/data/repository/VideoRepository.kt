package com.lifelog.camera.data.repository

import com.lifelog.camera.data.local.AppDatabase
import com.lifelog.camera.data.local.StorageDayStats
import com.lifelog.camera.data.local.entity.VideoClipEntity
import com.lifelog.camera.data.local.entity.ActivityLabelEntity
import com.lifelog.camera.data.local.entity.CharacterStatsEntity
import com.lifelog.camera.data.local.entity.SkillProficiencyEntity
import com.lifelog.camera.data.local.entity.DiscoveredSkillEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepository @Inject constructor(
    private val db: AppDatabase,
    private val videoDir: File
) {
    private val clipDao = db.videoClipDao()
    private val labelDao = db.activityLabelDao()

    // ── 视频片段 ──

    fun getAllClipsFlow(): Flow<List<VideoClipEntity>> = clipDao.getAllFlow()

    suspend fun getMaxId(): Long = clipDao.getMaxId() ?: 0

    suspend fun saveClip(clip: VideoClipEntity) = clipDao.insert(clip)

    suspend fun saveClips(clips: List<VideoClipEntity>) = clipDao.insertAll(clips)

    suspend fun getClipsByDate(dayStartMs: Long, dayEndMs: Long): List<VideoClipEntity> =
        clipDao.getByDateRange(dayStartMs, dayEndMs)

    /** 获取今日全部视频（含已分析），用于重新分析 */
    suspend fun getTodayClips(dayStartMs: Long, dayEndMs: Long): List<VideoClipEntity> =
        clipDao.getTodayClips(dayStartMs, dayEndMs)

    /** 重置视频的分析状态 */
    suspend fun resetAnalyzed(ids: List<Long>) = clipDao.resetAnalyzed(ids)

    /** 删除视频：文件 + MJPEG预览 + 标签 + 数据库记录 */
    suspend fun getClipById(id: Long): VideoClipEntity? = clipDao.getById(id)

    suspend fun deleteClip(clipId: Long, localPath: String) {
        labelDao.deleteByClipId(clipId)
        clipDao.deleteById(clipId)
        // 删除源文件 + 转码产物（localPath 存的是 .mjpg，也要删同名 .mp4）
        val base = localPath.removeSuffix(".mjpg").removeSuffix(".mp4")
        File("$base.mjpg").delete()
        File("$base.mp4").delete()
    }

    // ── 存储管理 ──

    /** 按日期分组获取存储统计 */
    suspend fun getStorageStatsByDay(): List<StorageDayStats> =
        clipDao.getStorageStatsByDay()

    /** 获取指定日期范围的 clips */
    suspend fun getClipsInRange(start: Long, end: Long): List<VideoClipEntity> =
        clipDao.getClipsInRange(start, end)

    /** 仅删除视频源文件（.mjpg + .mp4），保留 DB 记录和分析结果 */
    suspend fun deleteSourceFilesOnly(clipIds: List<Long>): Int {
        var deleted = 0
        for (id in clipIds) {
            val clip = clipDao.getById(id) ?: continue
            val mjpg = File(clip.localPath)
            val mp4 = File(clip.localPath.replace(".mjpg", ".mp4"))
            if (mjpg.delete()) deleted++
            mp4.delete()  // 可能不存在（旧文件或转码失败），不报错
        }
        return deleted
    }

    /** 获取所有剪辑的总存储占用（字节） */
    suspend fun getTotalStorageBytes(): Long {
        val stats = clipDao.getStorageStatsByDay()
        return stats.sumOf { it.totalBytes }
    }

    // ── AI 分析 ──

    suspend fun getUnanalyzedClips(limit: Int = 10) = clipDao.getUnanalyzed(limit)

    suspend fun markAnalyzed(ids: List<Long>) = clipDao.markAnalyzed(ids)

    suspend fun saveLabels(labels: List<ActivityLabelEntity>) = labelDao.insertAll(labels)

    suspend fun getLabelsByDate(dayStartMs: Long, dayEndMs: Long): List<ActivityLabelEntity> =
        labelDao.getByDateRange(dayStartMs, dayEndMs)

    fun getLabelsByDateFlow(dayStartMs: Long, dayEndMs: Long): Flow<List<ActivityLabelEntity>> =
        labelDao.getByDateRangeFlow(dayStartMs, dayEndMs)

    // ── 文件存储 ──

    fun getVideoFile(fileName: String): File = File(videoDir, fileName)

    fun getVideoDir(): File = videoDir

    /** 用精确时间戳回填旧记录的 capturedAt（同步后拿到固件给的 age 时调用） */
    suspend fun fixTimestamps(capturedAtMap: Map<Long, Long>) {
        capturedAtMap.forEach { (id, ts) ->
            try { clipDao.updateCapturedAt(id, ts) } catch (_: Exception) {}
        }
    }

    // ── RPG 模式 ──

    suspend fun saveCharacterStats(stats: CharacterStatsEntity) =
        db.characterStatsDao().insert(stats)

    suspend fun getLatestCharacterStats(): CharacterStatsEntity? =
        db.characterStatsDao().getLatest()

    fun getLatestStatsFlow(): Flow<CharacterStatsEntity?> =
        db.characterStatsDao().getLatestFlow()

    fun getTodayStatsFlow(start: Long, end: Long): Flow<List<CharacterStatsEntity>> =
        db.characterStatsDao().getTodayStatsFlow(start, end)

    suspend fun getSkillByName(name: String): SkillProficiencyEntity? =
        db.skillProficiencyDao().getByName(name)

    suspend fun upsertSkill(skill: SkillProficiencyEntity) =
        db.skillProficiencyDao().upsert(skill)

    suspend fun deleteSkill(skillName: String) =
        db.skillProficiencyDao().deleteByName(skillName)

    fun getAllSkillsFlow(): Flow<List<SkillProficiencyEntity>> =
        db.skillProficiencyDao().getAllFlow()

    suspend fun getTodayDiscoveredSkills(start: Long, end: Long): List<DiscoveredSkillEntity> =
        db.discoveredSkillDao().getTodayDiscovered(start, end)

    fun getTodayDiscoveredSkillsFlow(start: Long, end: Long): Flow<List<DiscoveredSkillEntity>> =
        db.discoveredSkillDao().getTodayDiscoveredFlow(start, end)

    suspend fun insertDiscoveredSkill(skill: DiscoveredSkillEntity) =
        db.discoveredSkillDao().insert(skill)
}
