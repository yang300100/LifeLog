package com.lifelog.camera.util

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.*

/**
 * 屏幕使用时间追踪
 * 需要用户在系统设置中授予"使用情况访问权限"
 */
object ScreenTimeTracker {

    /** 获取今日屏幕使用总时长（分钟），无权限返回 0 */
    fun getTodayScreenMinutes(context: Context): Int {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val end = System.currentTimeMillis()

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            val totalMs = stats.sumOf { it.totalTimeInForeground }
            (totalMs / 60000).toInt()
        } catch (e: Exception) {
            0
        }
    }
}
