package com.lifelog.camera.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lifelog.camera.data.local.entity.VideoClipEntity
import com.lifelog.camera.data.local.entity.ActivityLabelEntity
import com.lifelog.camera.data.local.entity.CharacterStatsEntity
import com.lifelog.camera.data.local.entity.SkillProficiencyEntity
import com.lifelog.camera.data.local.entity.DiscoveredSkillEntity

@Database(
    entities = [
        VideoClipEntity::class,
        ActivityLabelEntity::class,
        CharacterStatsEntity::class,
        SkillProficiencyEntity::class,
        DiscoveredSkillEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoClipDao(): VideoClipDao
    abstract fun activityLabelDao(): ActivityLabelDao
    abstract fun characterStatsDao(): CharacterStatsDao
    abstract fun skillProficiencyDao(): SkillProficiencyDao
    abstract fun discoveredSkillDao(): DiscoveredSkillDao
}
