package com.lifelog.camera.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "discovered_skills")
data class DiscoveredSkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillName: String,
    val displayName: String,
    val discoveredAt: Long,
    val clipId: Long
)
