package com.lifelog.camera.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_clips")
data class VideoClipEntity(
    @PrimaryKey val id: Long,
    val fileName: String,
    val capturedAt: Long,
    val fileSize: Long,
    val durationMs: Int,
    val localPath: String,
    val syncedAt: Long,
    val analyzed: Boolean = false
)
