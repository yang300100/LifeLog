package com.lifelog.camera.ai

import com.lifelog.camera.util.CrashLogger
import org.json.JSONArray
import org.json.JSONObject

/** AI 返回的 RPG 分析结果（属性为变化量 delta，非绝对值） */
data class RpgAnalysisResult(
    val activity: String,
    val category: String,
    val stats: RpgStatsDelta,
    val skills: List<RpgSkillGain>,
    val newSkills: List<String>
)

/** 属性变化量（正=增加，负=减少） */
data class RpgStatsDelta(val hp: Int, val mp: Int, val mood: Int, val mastery: Int)
data class RpgSkillGain(val name: String, val display: String, val expGain: Int)

object RpgAnalyzer {
    private const val TAG = "RpgAnalyzer"

    /** 构建 RPG 模式的 System Prompt */
    fun buildSystemPrompt(): String = buildString {
        append("你是用户的「人生 RPG 状态引擎」。用户胸前佩戴摄像头，每 2 分钟拍摄短视频。")
        append("请根据画面分析用户状态变化，返回属性变化量（delta）而非绝对值。\n\n")
        append("规则：\n")
        append("- 返回变化量(delta)：正数=增加，负数=减少，0=不变\n")
        append("- 同一活动持续进行时 HP/MP 缓慢下降（每次 -1~-3）\n")
        append("- 休息/用餐时 HP/MP 回升（每次 +3~+8）\n")
        append("- 心情随环境变化（每次 -3~+5）\n")
        append("- 掌控度随计划执行变化（每次 -2~+3）\n")
        append("- 技能经验按活动类型累积，同一技能多次出现叠加\n")
        append("- 首次出现的行为标记为 new_skills\n")
        append("\n技能命名规则（重要）：\n")
        append("- 技能名用英文小写+下划线，如 programming, gaming, cooking\n")
        append("- 同类型活动归为一个技能，不要拆分太细\n")
        append("  例：「玩游戏」「看游戏资讯」「看游戏直播」都归为 gaming\n")
        append("  例：「切菜」「炒菜」「烘焙」都归为 cooking\n")
        append("  例：「看新闻」「刷微博」「刷视频」不属技能，不返回\n")
        append("- 只有需要学习/练习才能进步的行为才算是技能\n")
        append("  不属于技能：走路、吃饭、通勤、购物、看资讯、刷手机\n")
        append("  属于技能：编程、弹琴、绘画、写作、运动、烹饪、外语\n")
    }

    /** 构建 RPG 模式的 User Prompt */
    fun buildUserPrompt(inputCount: Int, timestamps: List<String>): String = buildString {
        for (idx in 0 until inputCount) {
            val t = timestamps.getOrElse(idx) { "??:??" }
            append("[视频 $idx @ $t]\n")
        }
        append("\n返回 JSON 格式（注意 stats 是变化量 delta）：\n")
        append("{\n")
        append("  \"activities\": [{\n")
        append("    \"time\": \"HH:MM\",\n")
        append("    \"activity\": \"简短行为描述(中文)\",\n")
        append("    \"category\": \"work/eat/sport/transport/sleep/home/outdoor/other\",\n")
        append("    \"stats_delta\": {\"hp\": -2, \"mp\": -3, \"mood\": -1, \"mastery\": 2},\n")
        append("    \"skills\": [{\"name\": \"programming\", \"display\": \"编程\", \"exp_gain\": 8}],\n")
        append("    （技能合并：同类归一个，如 gaming 不拆 gaming_news）\n")
        append("    \"new_skills\": [\"coffee_brewing\"]\n")
        append("  }]\n")
        append("}")
    }

    /** 解析 RPG API 响应 */
    fun parseResponse(jsonText: String): List<RpgAnalysisResult> {
        val results = mutableListOf<RpgAnalysisResult>()
        try {
            val arr = if (jsonText.trimStart().startsWith("[")) {
                JSONArray(jsonText)
            } else {
                val obj = JSONObject(jsonText)
                obj.optJSONArray("activities") ?: JSONArray()
            }

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val statsObj = item.optJSONObject("stats_delta") ?: item.optJSONObject("stats") ?: JSONObject()
                val skillsArr = item.optJSONArray("skills") ?: JSONArray()
                val newArr = item.optJSONArray("new_skills") ?: JSONArray()

                results.add(RpgAnalysisResult(
                    activity = item.optString("activity", ""),
                    category = item.optString("category", "other"),
                    stats = RpgStatsDelta(
                        hp = statsObj.optInt("hp", 0),
                        mp = statsObj.optInt("mp", 0),
                        mood = statsObj.optInt("mood", 0),
                        mastery = statsObj.optInt("mastery", 0)
                    ),
                    skills = (0 until skillsArr.length()).map { j ->
                        val s = skillsArr.getJSONObject(j)
                        RpgSkillGain(
                            name = s.optString("name", ""),
                            display = s.optString("display", s.optString("name", "")),
                            expGain = s.optInt("exp_gain", 0)
                        )
                    },
                    newSkills = (0 until newArr.length()).map { j ->
                        newArr.getString(j)
                    }
                ))
            }
        } catch (e: Exception) {
            CrashLogger.e(TAG, "解析 RPG 响应失败: ${jsonText.take(300)}", e)
        }
        CrashLogger.i(TAG, "RPG 解析结果: ${results.size} 个活动")
        return results
    }

    /** 聚合技能经验：同技能名称合并 */
    fun mergeSkillGains(results: List<RpgAnalysisResult>): Map<String, RpgSkillGain> {
        val merged = mutableMapOf<String, RpgSkillGain>()
        results.flatMap { it.skills }.forEach { skill ->
            val existing = merged[skill.name]
            if (existing != null) {
                merged[skill.name] = existing.copy(
                    expGain = existing.expGain + skill.expGain
                )
            } else {
                merged[skill.name] = skill
            }
        }
        return merged
    }
}
