package com.lifelog.camera.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "character_stats")
data class CharacterStatsEntity(
    @PrimaryKey val timestamp: Long,      // 录制时间戳
    val hp: Int,                          // 0-100
    val mp: Int,                          // 0-100
    val mood: Int,                        // 0-100
    val mastery: Int,                     // 0-100 掌控度
    val clipId: Long                      // 关联视频
)
