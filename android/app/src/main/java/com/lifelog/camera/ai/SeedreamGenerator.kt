package com.lifelog.camera.ai

import android.graphics.BitmapFactory
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seedream 5.0 多图融合生成器
 *
 * 使用火山引擎方舟 Ark API (OpenAI SDK 兼容格式)
 *
 * 图序约定:
 *   image[0] = 场景图 (图1)
 *   image[1:] = 角色参考图 (图2, 图3, ...)
 *
 * B方案: 无 Mask, Prompt 控位 — 用自然语言描述位置和交互
 */
@Singleton
class SeedreamGenerator @Inject constructor(
    private val apiPreferences: ApiPreferences
) {
    /** 参考图类型 */
    enum class RefType(val label: String) {
        CHARACTER("角色参考"),   // 人物形象（正面/标准照）
        CHARACTER_ALT("多角度"), // 同一角色的其他角度
        POSE("姿态参考"),        // 目标姿态/动作
        STYLE("画风参考"),       // 色彩/笔触/光影风格
        GENERAL("整体参考")      // 构图/氛围
    }

    companion object {
        private const val TAG = "SeedreamGen"
        private const val TIMEOUT_SEC = 300L

        /**
         * Seedream 融合 Prompt 模板
         *
         * 图序约定 (API image[] 数组):
         *   image[0] = 场景底图 (图1)
         *   image[1:] = 各类参考图 (图2, 图3, ...)
         *
         * 官方 prompt 范式: "将图1的xxx换为图2的xxx"
         * {ref_desc} 由 buildRefDescription() 根据参考图类型动态生成
         */
        val SEEDREAM_PROMPT_TEMPLATE =
            "写实摄影风格，{interaction_desc}。" +
            "将人物放置在黄色圆点处。" +
            "场景采用自然侧逆光为主光源，搭配柔和的体积光效果，" +
            "空气中悬浮着细微的尘埃粒子与漫射光晕，营造出通透的散射质感。" +
            "人物皮肤呈现真实的哑光肌理，带有细密的毛孔与自然肤色过渡；" +
            "衣物面料展现清晰的织物纹理、自然的褶皱与粗糙度。" +
            "光影边缘干净锐利，明暗交界线明确，无模糊揉合；" +
            "整体采用电影级调色，高动态范围，暗部保留丰富细节，高光区域呈柔和衰减。" +
            "最终画面呈现出照片级的真实感与临场氛围。"

        /** 根据参考图列表构建图序描述 */
        fun buildRefDescription(refs: List<Pair<RefType, Int>>): String {
            if (refs.isEmpty()) return "以参考图中的人物为参考人物，重绘图片图1"

            val parts = mutableListOf<String>()
            for ((type, imgNum) in refs) {
                val imgRef = "图${imgNum}"
                when (type) {
                    RefType.CHARACTER     -> parts.add("以${imgRef}中的人物为参考人物")
                    RefType.CHARACTER_ALT -> {}
                    RefType.POSE          -> parts.add("以${imgRef}为姿态参考")
                    RefType.STYLE         -> parts.add("以${imgRef}为画风参考")
                    RefType.GENERAL       -> parts.add("参考${imgRef}的整体构图与氛围")
                }
            }
            val hasAlt = refs.any { it.first == RefType.CHARACTER_ALT }
            val desc = parts.joinToString("，")
            return if (hasAlt) "${desc}，结合多角度参考确保角色形象一致" else desc
        }

        fun buildSeedreamPrompt(
            interactionDesc: String,
            refDesc: String = ""
        ): String {
            val action = interactionDesc.ifBlank { "自然站立，面朝前方" }
            val ref = refDesc.ifBlank { "以参考图中的人物为参考人物，重绘图片图1" }
            return SEEDREAM_PROMPT_TEMPLATE
                .replace("{ref_desc}", ref)
                .replace("{interaction_desc}", action)
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /**
     * 多图融合生成
     *
     * @param scenePath 场景图本地路径
     * @param references 参考图列表: Pair(本地路径, 参考类型)
     * @param positionDesc 位置描述 (中文)
     * @param interactionDesc 交互描述 (中文)
     * @param customPrompt 自定义完整 prompt (可选, 覆盖自动构建)
     * @param size 输出尺寸 "2k" / "3k" / "4k"
     * @return 生成结果图片 URL
     */
    suspend fun generate(
        scenePath: String,
        references: List<Pair<String, RefType>>,
        @Suppress("UNUSED_PARAMETER") positionDesc: String,
        interactionDesc: String = "",
        customPrompt: String? = null,
        size: String = "2k"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cfg = apiPreferences.getSeedreamConfig()
            if (cfg.apiKey.isBlank()) return@withContext Result.failure(Exception("请先配置 Seedream API Key"))

            // 组装 image 数组（仅保留存在的文件，同步记录实际的图序）
            val images = JSONArray().apply { put(imageFileToBase64(File(scenePath))) }
            val actualRefs = mutableListOf<Pair<SeedreamGenerator.RefType, Int>>()
            for ((path, type) in references) {
                val b64 = imageFileToBase64(File(path))
                if (b64.isNotEmpty()) {
                    images.put(b64)
                    // image[0]=图1(场景), image[images.length()-1]=图N(参考)
                    actualRefs.add(type to images.length() - 1 + 1)  // +1 转 1-based
                }
            }

            // 用实际存在的参考图构建 prompt（图序与 JSON 数组严格对齐）
            val refDesc = buildRefDescription(actualRefs)
            val prompt = customPrompt ?: buildSeedreamPrompt(interactionDesc, refDesc)
            CrashLogger.i(TAG, "Seedream 生成: model=${cfg.model}, 参考=${actualRefs.size}/${references.size}张")

            val body = JSONObject().apply {
                put("model", cfg.model)
                put("prompt", prompt)
                put("size", size)
                put("response_format", "url")
                put("image", images)
                put("watermark", false)
                put("sequential_image_generation", "disabled")
            }

            val response = callApi(cfg, body)
            val respJson = JSONObject(response)
            val url = respJson.getJSONArray("data")
                .getJSONObject(0)
                .getString("url")

            CrashLogger.i(TAG, "Seedream 生成成功!")
            Result.success(url)

        } catch (e: Exception) {
            Log.e(TAG, "Seedream 生成失败", e)
            Result.failure(e)
        }
    }

    // ── 内部 ──

    private fun imageFileToBase64(file: File): String {
        if (!file.exists()) {
            CrashLogger.w(TAG, "图片不存在，跳过: ${file.name}")
            return ""
        }

        // 参考图 PNG 直接传原始格式（保留 alpha 通道和完整色彩，匹配网页版效果）
        val isRefPng = file.extension.lowercase() == "png"
        if (isRefPng) {
            var bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            if (bmp == null) {
                CrashLogger.w(TAG, "图片损坏无法解码，跳过: ${file.name}")
                return ""
            }
            // 仅缩放超尺寸图片，不做格式转换（保留 PNG 质量）
            val maxDim = 3000
            if (bmp.width > maxDim || bmp.height > maxDim) {
                val ratio = if (bmp.width >= bmp.height) maxDim.toFloat() / bmp.width
                            else maxDim.toFloat() / bmp.height
                val scaled = android.graphics.Bitmap.createScaledBitmap(
                    bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
                bmp.recycle()
                bmp = scaled
            }
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            val bytes = bos.toByteArray()
            CrashLogger.i(TAG, "参考图 PNG: ${bmp.width}x${bmp.height}, ${bytes.size / 1024}KB")
            return "data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }

        // 场景图等非 PNG → JPEG（Seedream 对 JPEG 兼容性最好）
        var bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        if (bmp == null) {
            CrashLogger.w(TAG, "图片无法解码，跳过: ${file.name}")
            return ""
        }
        val maxDim = 3000
        if (bmp.width > maxDim || bmp.height > maxDim) {
            val ratio = if (bmp.width >= bmp.height) maxDim.toFloat() / bmp.width
                        else maxDim.toFloat() / bmp.height
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
            bmp.recycle()
            bmp = scaled
        }
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, bos)
        bmp.recycle()
        return "data:image/jpeg;base64,${Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)}"
    }

    private fun callApi(cfg: AIClient.ApiConfig, body: JSONObject): String {
        val url = "${cfg.baseUrl.trimEnd('/')}/images/generations"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw Exception("Seedream API ${response.code}: ${responseBody.take(300)}")
            }
            responseBody
        }
    }
}
