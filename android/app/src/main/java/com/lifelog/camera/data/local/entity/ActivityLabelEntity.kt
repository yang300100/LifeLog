package com.lifelog.camera.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_labels")
data class ActivityLabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clipId: Long,
    val timestamp: Long,
    val category: String,
    val behavior: String,
    val description: String,
    val confidence: Float
)
