package com.lifelog.camera.ai

import android.util.Log
import com.lifelog.camera.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIClient @Inject constructor(
    private val keyframeExtractor: KeyframeExtractor
) {
    companion object {
        private const val TAG = "AIClient"
        private const val TIMEOUT_SEC = 120L
    }

    data class ApiConfig(
        val baseUrl: String = "https://api.openai.com/v1",
        val apiKey: String = "",
        val model: String = "gpt-4o",
        val temperature: Float = 1.0f  // Kimi 等模型只支持 1.0，默认为此
    ) {
        val isValid: Boolean get() = apiKey.isNotBlank()
    }

    data class ActivityResult(
        val time: String,
        val category: String,
        val behavior: String,
        val description: String,
        val confidence: Float
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** 视频输入类型：MP4 文件 或 MJPEG 帧列表 */
    sealed class VideoInput {
        data class VideoFile(val path: String) : VideoInput()
        data class Frames(val jpegs: List<ByteArray>) : VideoInput()
    }

    // ── 行为分析 ──

    suspend fun analyzeActivity(
        inputs: List<VideoInput>,     // 每个视频的输入（MP4 或帧列表）
        timestamps: List<String>,     // "HH:MM" 格式
        config: ApiConfig
    ): Result<List<ActivityResult>> = withContext(Dispatchers.IO) {
        try {
            val mediaContents = JSONArray()
            inputs.forEachIndexed { idx, input ->
                val label = timestamps.getOrElse(idx) { "??:??" }
                when (input) {
                    is VideoInput.VideoFile -> {
                        // 流式读取 + base64 编码，避免 readBytes() OOM
                        val b64 = streamFileToBase64(java.io.File(input.path))
                        mediaContents.put(JSONObject().apply {
                            put("type", "video_url")
                            put("video_url", JSONObject().apply {
                                put("url", "data:video/mp4;base64,$b64")
                            })
                        })
                        CrashLogger.i(TAG, "  视频 $idx @ $label: MP4 base64, 原始 ${java.io.File(input.path).length()} bytes")
                    }
                    is VideoInput.Frames -> {
                        // MJPEG 帧列表
                        input.jpegs.forEach { jpeg ->
                            mediaContents.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", keyframeExtractor.toBase64Uri(jpeg))
                                    put("detail", "low")
                                })
                            })
                        }
                        CrashLogger.i(TAG, "  视频 $idx @ $label: ${input.jpegs.size} 帧")
                    }
                }
            }

            val systemPrompt = buildString {
                append("你是用户的生活行为分析助手。用户佩戴胸前摄像头全天拍摄。")
                append("请分析以下视频中的用户行为。")
                append("时间戳标注在每组视频前。")
            }

            val userContent = JSONArray()
            userContent.put(JSONObject().apply {
                put("type", "text")
                put("text", buildString {
                    append("分析这些视频中的用户行为。返回 JSON 数组：\n")
                    inputs.forEachIndexed { idx, input ->
                        val t = timestamps.getOrElse(idx) { "??:??" }
                        val desc = when (input) {
                            is VideoInput.VideoFile -> "MP4 视频"
                            is VideoInput.Frames -> "${input.jpegs.size} 帧序列"
                        }
                        append("[视频 $idx @ $t] ($desc)\n")
                    }
                    append("\n每个视频输出：\n")
                    append("{\"time\":\"HH:MM\",\"category\":\"work|eat|sleep|transport|sport|home|outdoor|uncertain\",")
                    append("\"behavior\":\"具体行为(英文下划线)\",")
                    append("\"description\":\"中文行为描述\",\"confidence\":0.0-1.0}")
                })
            })

            // 合并图片/视频到 user content
            for (i in 0 until mediaContents.length()) {
                userContent.put(mediaContents.getJSONObject(i))
            }

            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })

            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", messages)
                put("max_tokens", 8000)
                put("temperature", config.temperature.toDouble())
                // 禁用 Kimi k2.6 思考功能，避免 reasoning_content 吃光 token
                put("thinking", JSONObject().apply {
                    put("type", "disabled")
                })
            }

            val response = callApi(config, body)
            val results = parseActivityResponse(response)

            Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, "分析失败", e)
            Result.failure(e)
        }
    }

    // ── 日报生成 ──

    suspend fun generateReport(
        activities: List<ActivityResult>,
        config: ApiConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 合并连续相同行为
            val merged = mergeConsecutive(activities)

            val timelineJson = JSONArray()
            merged.forEach { act ->
                timelineJson.put(JSONObject().apply {
                    put("time", act.time)
                    put("category", act.category)
                    put("behavior", act.behavior)
                    put("description", act.description)
                })
            }

            val prompt = buildString {
                append("根据以下时间轴，以第一人称'我'生成一段 100-200 字的自然语言生活日记。\n\n")
                append("时间轴:\n")
                append(timelineJson.toString(2))
                append("\n\n要求：使用第一人称'我今天...'，自然流畅，像写日记，突出主要活动，不需要列举每个时间点。")
                append("\n\n请返回格式：{\"report\":\"日记内容\"}")
            }

            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", 500)
                put("temperature", config.temperature.toDouble())
                put("thinking", JSONObject().apply {
                    put("type", "disabled")
                })
            }

            val response = callApi(config, body)
            val reportJson = JSONObject(response)
            val report = reportJson.optString("report", "")
                .ifEmpty { response.take(500) }

            Result.success(report)
        } catch (e: Exception) {
            Log.e(TAG, "日报生成失败", e)
            Result.failure(e)
        }
    }

    // ── 流式文件 base64（避免 readBytes() OOM）──

    private fun streamFileToBase64(file: java.io.File): String {
        val buffer = ByteArray(8192) // 8KB 块
        val baos = java.io.ByteArrayOutputStream()
        val b64os = android.util.Base64OutputStream(baos, android.util.Base64.NO_WRAP)
        java.io.FileInputStream(file).use { fis ->
            var n: Int
            while (fis.read(buffer).also { n = it } != -1) {
                b64os.write(buffer, 0, n)
            }
        }
        b64os.close()
        return baos.toString("UTF-8")
    }

    // ── HTTP 调用 ──

    private fun callApi(config: ApiConfig, body: JSONObject): String {
        val url = "${config.baseUrl.trimEnd('/')}/chat/completions"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        return response.use { resp ->
            val responseBody = resp.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw Exception("API error ${response.code}: ${responseBody.take(200)}")
            }

            CrashLogger.i(TAG, "API 原始响应 (前500字符): ${responseBody.take(500)}")

            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            if (choices.length() == 0) throw Exception("No choices in response")

            val message = choices.getJSONObject(0).getJSONObject("message")
            var content = message.optString("content", "")
            // Kimi k2.6 等推理模型: reasoning_content 吃光 token 导致 content 为空
            // 此时用 reasoning_content 作为兜底
            if (content.isBlank()) {
                val reasoning = message.optString("reasoning_content", "")
                if (reasoning.isNotBlank()) {
                    CrashLogger.w(TAG, "content 为空，使用 reasoning_content 兜底 (${reasoning.length} 字符)")
                    content = reasoning
                }
            }
            val finishReason = choices.getJSONObject(0).optString("finish_reason", "unknown")
            CrashLogger.i(TAG, "finish_reason=$finishReason, content长度=${content.length}")
            CrashLogger.i(TAG, "API 响应内容: ${content.take(500)}")

            if (content.isBlank()) {
                throw Exception("API 返回空内容 (finish_reason=$finishReason)，模型可能不支持视觉输入。尝试用 moonshot-v1-8k-vision 或检查 API Key 余额")
            }

            val jsonStart = content.indexOf('[')
            val jsonEnd = content.lastIndexOf(']')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                content.substring(jsonStart, jsonEnd + 1)
            } else {
                val braceStart = content.indexOf('{')
                val braceEnd = content.lastIndexOf('}')
                if (braceStart >= 0 && braceEnd > braceStart) {
                    content.substring(braceStart, braceEnd + 1)
                } else {
                    content
                }
            }
        }
    }

    // ── 解析响应 ──

    private fun parseActivityResponse(jsonText: String): List<ActivityResult> {
        val results = mutableListOf<ActivityResult>()
        try {
            // 尝试作为数组解析
            val arr = if (jsonText.trimStart().startsWith("[")) {
                JSONArray(jsonText)
            } else {
                // 尝试从对象中提取
                val obj = JSONObject(jsonText)
                obj.optJSONArray("activities")
                    ?: obj.optJSONArray("results")
                    ?: JSONArray()
            }

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                results.add(ActivityResult(
                    time = item.optString("time", "??:??"),
                    category = item.optString("category", "uncertain"),
                    behavior = item.optString("behavior", "unknown"),
                    description = item.optString("description", ""),
                    confidence = item.optDouble("confidence", 0.5).toFloat()
                ))
            }
        } catch (e: Exception) {
            CrashLogger.e(TAG, "解析活动响应失败: ${jsonText.take(300)}", e)
        }
        CrashLogger.i(TAG, "解析结果: ${results.size} 个活动")
        return results
    }

    private fun mergeConsecutive(activities: List<ActivityResult>): List<ActivityResult> {
        if (activities.isEmpty()) return activities
        val result = mutableListOf(activities.first())
        for (i in 1 until activities.size) {
            val prev = result.last()
            val curr = activities[i]
            if (curr.behavior == prev.behavior && curr.category == prev.category) {
                // 合并
                result[result.lastIndex] = prev.copy(
                    description = curr.description
                )
            } else {
                result.add(curr)
            }
        }
        return result
    }

    // ── RPG 状态分析 ──

    suspend fun analyzeRpg(
        inputs: List<VideoInput>,
        timestamps: List<String>,
        config: ApiConfig
    ): Result<List<RpgAnalysisResult>> = withContext(Dispatchers.IO) {
        try {
            CrashLogger.i(TAG, "=== analyzeRpg 入口: ${inputs.size} inputs, ${timestamps.size} timestamps ===")
            val userContent = JSONArray()
            userContent.put(JSONObject().apply {
                put("type", "text")
                put("text", RpgAnalyzer.buildUserPrompt(inputs.size, timestamps))
            })

            // 添加视频/帧内容
            inputs.forEachIndexed { idx, input ->
                when (input) {
                    is VideoInput.VideoFile -> {
                        val b64 = streamFileToBase64(java.io.File(input.path))
                        userContent.put(JSONObject().apply {
                            put("type", "video_url")
                            put("video_url", JSONObject().apply {
                                put("url", "data:video/mp4;base64,$b64")
                            })
                        })
                    }
                    is VideoInput.Frames -> {
                        input.jpegs.forEach { jpeg ->
                            userContent.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", keyframeExtractor.toBase64Uri(jpeg))
                                    put("detail", "low")
                                })
                            })
                        }
                    }
                }
            }

            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", RpgAnalyzer.buildSystemPrompt())
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })

            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", messages)
                put("max_tokens", 8000)
                put("temperature", config.temperature.toDouble())
                put("thinking", JSONObject().apply {
                    put("type", "disabled")
                })
            }

            CrashLogger.i(TAG, "RPG 调用 API: model=${config.model}, bodySize=${body.toString().length}")
            val response = callApi(config, body)
            CrashLogger.i(TAG, "RPG API 返回: ${response.take(200)}")
            val results = RpgAnalyzer.parseResponse(response)
            CrashLogger.i(TAG, "RPG 解析完成: ${results.size} 个结果")

            Result.success(results)
        } catch (e: Exception) {
            CrashLogger.e(TAG, "RPG分析失败: ${e.message}", e)
            Result.failure(e)
        }
    }
}
