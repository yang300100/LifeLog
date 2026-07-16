package com.lifelog.camera.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.math.sqrt

@Entity(tableName = "skill_proficiency")
data class SkillProficiencyEntity(
    @PrimaryKey val skillName: String,    // 技能名（如 "programming"）
    val displayName: String,              // 显示名（如 "编程"）
    val exp: Int,                         // 累计经验
    val practiceMinutes: Int = 0,         // 累计练习时长（分钟）
    val lastExpGainAt: Long = 0,          // 上次加经验时间（30min 冷却用）
    val lastUsedAt: Long = 0              // 0 = 手动添加，未被摄像头拍到过
) {
    companion object {
        /** EXP → 等级 */
        fun expToLevel(exp: Int): Int = sqrt(exp.toDouble() / 100).toInt() + 1

        /** 等级 → 所需 EXP */
        fun levelToExp(level: Int): Int = (level - 1) * (level - 1) * 100
    }
}
