package com.lifelog.camera.ai

import android.util.Base64
import android.util.Log
import com.lifelog.camera.data.model.*
import com.lifelog.camera.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kimi k2.6 候选筛选器
 *
 * 职责: 从 YOLO+规则生成的 3-5 个候选中选出最佳位置 + 交互动作
 *
 * 输入: 场景原图 + 分割标注图 + 候选 JSON
 * 输出: KimiSelection (best_candidate_id, interaction, facing, light...)
 */
@Singleton
class KimiSelector @Inject constructor(
    private val apiPreferences: ApiPreferences
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    suspend fun select(
        sceneBase64: String,
        annotatedBase64: String,
        candidates: List<CandidatePosition>,
        customPrompt: String? = null
    ): Result<KimiSelection> = withContext(Dispatchers.IO) {
        try {
            val kimiCfg = apiPreferences.getKimiConfig()
            if (!kimiCfg.isValid) return@withContext Result.failure(Exception("请先配置 Kimi API Key"))

            val prompt = customPrompt ?: DEFAULT_SELECTION_PROMPT
            val candidateJson = buildCandidateJson(candidates)
            val fullPrompt = prompt.replace("{candidate_json}", candidateJson)

            CrashLogger.i(TAG, "Kimi 候选筛选请求... 候选数=${candidates.size}")

            val body = JSONObject().apply {
                put("model", KIMI_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", sceneBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", annotatedBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", fullPrompt)
                            })
                        })
                    })
                })
                put("max_tokens", 800)
                put("temperature", 0.6)
                put("thinking", JSONObject().apply {
                    put("type", "disabled")
                })
            }

            val response = callApi(KIMI_BASE, kimiCfg.apiKey, body)
            val respJson = JSONObject(response)
            val content = respJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            CrashLogger.i(TAG, "Kimi 响应长度: ${content.length}")
            val selection = parseResponse(content, candidates)
            Result.success(selection)

        } catch (e: Exception) {
            Log.e(TAG, "Kimi 筛选失败, 回退规则候选", e)
            // 返回 success + fallback, 但 confidence=0 标记为回退
            Result.success(fallbackSelection(candidates).copy(confidence = 0.1f))
        }
    }

    // ── 内部 ──

    private fun buildCandidateJson(candidates: List<CandidatePosition>): String {
        val arr = JSONArray()
        for (c in candidates) {
            val interactions = JSONArray()
            for (s in c.interactionSuggestions.take(3)) {
                interactions.put(JSONObject().apply {
                    put("object", s.obj)
                    put("action", s.action)
                    put("position", s.relativePosition)
                })
            }
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("description", c.regionDescription)
                put("nearby_objects", JSONArray(c.nearbyObjects))
                put("interaction_options", interactions)
                put("coordinates", "(${c.centerX}, ${c.centerY})")
            })
        }
        return arr.toString(2)
    }

    private fun parseResponse(content: String, candidates: List<CandidatePosition>): KimiSelection {
        return try {
            var jsonStr = content
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val jsonStart = jsonStr.indexOf('{')
            val jsonEnd = jsonStr.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                jsonStr = jsonStr.substring(jsonStart, jsonEnd)
            }

            val obj = JSONObject(jsonStr)

            val evaluations = mutableListOf<CandidateEvaluation>()
            val evalsArr = obj.optJSONArray("evaluations")
            if (evalsArr != null) {
                for (i in 0 until evalsArr.length()) {
                    val e = evalsArr.getJSONObject(i)
                    evaluations.add(CandidateEvaluation(
                        id = e.getInt("id"),
                        score = e.getDouble("score"),
                        reason = e.optString("reason", "")
                    ))
                }
            }

            val interObj = obj.optJSONObject("selected_interaction")
            val interaction = if (interObj != null) SelectedInteraction(
                obj = interObj.optString("object", ""),
                detailedDescription = interObj.optString("detailed_description", ""),
                pose = interObj.optString("pose", ""),
                facing = interObj.optString("facing", ""),
                gazeTarget = interObj.optString("gaze_target", "")
            ) else null

            KimiSelection(
                bestCandidateId = obj.optInt("best_candidate_id", 1),
                selectedInteraction = interaction,
                personFacing = obj.optString("person_facing", "toward_camera"),
                lightDirection = obj.optString("light_direction", ""),
                confidence = obj.optDouble("confidence", 0.5).toFloat(),
                evaluations = evaluations,
                warnings = obj.optJSONArray("warnings")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                rawResponse = content
            )
        } catch (e: Exception) {
            CrashLogger.e(TAG, "解析 Kimi 响应失败: ${e.message}")
            fallbackSelection(candidates)
        }
    }

    private fun fallbackSelection(candidates: List<CandidatePosition>): KimiSelection {
        if (candidates.isEmpty()) return KimiSelection(
            bestCandidateId = -1,
            selectedInteraction = null,
            confidence = 0f
        )
        val best = candidates.maxByOrNull { it.standableScore } ?: candidates.first()
        val interaction = best.interactionSuggestions.firstOrNull()
        return KimiSelection(
            bestCandidateId = best.id,
            selectedInteraction = interaction?.let {
                SelectedInteraction(
                    obj = it.obj,
                    detailedDescription = it.action,
                    pose = "standing",
                    facing = "toward_camera"
                )
            },
            personFacing = "toward_camera",
            confidence = 0.5f
        )
    }

    private fun callApi(baseUrl: String, apiKey: String, body: JSONObject): String {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw Exception("Kimi API ${response.code}: ${responseBody.take(200)}")
            }
            responseBody
        }
    }

    companion object {
        private const val TAG = "KimiSelector"
        private const val KIMI_BASE = "https://api.moonshot.cn/v1"
        private const val KIMI_MODEL = "kimi-k2.6"
        private const val TIMEOUT_SEC = 180L

        val DEFAULT_SELECTION_PROMPT = """
你是一位摄影构图助手和虚拟陪伴角色的"行为导演"。

这张标注图是在原图上叠加了分割信息：
- 红色半透明 = 必须避开的人物/障碍物
- 蓝色方框 = 可互动的物体（杯子、书本、电脑等）
- 绿色半透明 = 地面/可站立区域
- 黄色圆点+编号 = 候选插入位置

以下结构化数据补充了每个候选的详细信息：
{candidate_json}

请完成以下任务：

1. **逐一审视**每个候选位置，综合考虑：
   - 位置自然度：角色站在这里是否合理？
   - 互动机会：附近有可互动的物体吗？互动是否自然、有陪伴感？
   - 遮挡风险：角色是否会被前景物体遮挡？
   - 光照方向：从场景光线推测，该位置是顺光/侧光/逆光？
   - 构图平衡：角色放在这里后整体画面是否平衡？

2. 如果候选附近有可互动物体，为角色选择一个**最自然、最有陪伴感**的互动动作。

3. 选出最佳候选，如果全部不合适返回 -1。

严格按以下 JSON 格式输出（不要 markdown 代码块）：
{
  "evaluations": [
    {"id": 1, "score": 4, "reason": "..."},
    {"id": 2, "score": 2, "reason": "..."}
  ],
  "best_candidate_id": 1,
  "selected_interaction": {
    "object": "杯子",
    "detailed_description": "身体微微前倾，双手轻轻撑在桌边，探头好奇地看着桌上的杯子，嘴角带着温柔的笑意，仿佛在等用户喝水",
    "pose": "leaning_forward",
    "facing": "toward_right",
    "gaze_target": "水杯"
  },
  "person_facing": "toward_left",
  "confidence": 0.85,
  "warnings": []
}

评分标准 (1-5分):
- 5分: 完美位置，自然且可与物体互动
- 3分: 可接受，但不够理想
- 1分: 不合适，应避免
        """.trimIndent()
    }
}
