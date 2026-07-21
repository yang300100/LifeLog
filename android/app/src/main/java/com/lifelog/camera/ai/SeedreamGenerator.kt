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
    companion object {
        private const val TAG = "SeedreamGen"
        private const val BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        private const val MODEL = "doubao-seedream-5-0-260128"
        private const val TIMEOUT_SEC = 300L

        /**
         * Seedream 融合 Prompt 模板 (中文, 有参考图模式)
         *
         * 遵循 Seedream 4.0 官方指南:
         *   - 自然语言描述 主体+行为+环境
         *   - 简洁精确优于堆砌词汇
         *   - 明确参考对象
         *
         * 注意: Seedream 5.0 不支持 negative_prompt —
         *   黑边/塑料感约束已全部正面化融入本模板
         */
        val SEEDREAM_PROMPT_TEMPLATE =
            "将角色自然地呈现在这张场景照片中，{position_desc}，{interaction_desc}。" +
            "角色与场景共享相同的光线环境，自然光的方向、强度与色温保持一致，脚下带有柔和投影。" +
            "人物轮廓由自然光线柔和勾勒，边缘像素与周围场景像素平滑过渡，如同原生照片中的景深虚化，" +
            "无任何可见接缝、分界线、黑边或描边痕迹。" +
            "肤色与服装色调融入场景的色彩氛围，保留皮肤纹理与面料质感的真实细节。" +
            "人物全身可见，双脚踏实地面，比例符合场景透视。" +
            "真实摄影风格，仿佛随手抓拍的生活照片。"

        fun buildSeedreamPrompt(positionDesc: String, interactionDesc: String): String {
            return SEEDREAM_PROMPT_TEMPLATE
                .replace("{position_desc}", positionDesc)
                .replace("{interaction_desc}", interactionDesc.ifBlank { "自然站立，面朝前方，带着温柔的微笑" })
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
     * @param referencePaths 角色参考图本地路径列表
     * @param positionDesc 位置描述 (中文) 如 "画面右侧键盘旁"
     * @param interactionDesc 交互描述 (中文) 如 "身体微微前倾，探头看向杯子"
     * @param referenceHint 参考图分类说明 (可选) 如 "参考图中包含：角色参考2张、画风参考1张"
     * @param customPrompt 自定义完整 prompt (可选, 覆盖自动构建)
     * @param size 输出尺寸 "2k" / "3k" / "4k"
     * @return 生成结果图片 URL
     */
    suspend fun generate(
        scenePath: String,
        referencePaths: List<String>,
        positionDesc: String,
        interactionDesc: String = "",
        referenceHint: String = "",
        customPrompt: String? = null,
        size: String = "2k"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val seedreamKey = apiPreferences.getSeedreamApiKey()
            if (seedreamKey.isBlank()) return@withContext Result.failure(Exception("请先配置 Seedream API Key"))

            // 构建 prompt (参考图说明 + 融合指令)
            val prompt = customPrompt ?: buildSeedreamPrompt(positionDesc, interactionDesc)
            val fullPrompt = if (referenceHint.isNotBlank()) {
                "$referenceHint$prompt"
            } else prompt

            CrashLogger.i(TAG, "Seedream 多图融合... 场景=1 参考=${referencePaths.size}")

            // 组装 image 数组
            val images = JSONArray().apply {
                put(imageFileToBase64(File(scenePath)))
                for (ref in referencePaths) {
                    put(imageFileToBase64(File(ref)))
                }
            }

            val body = JSONObject().apply {
                put("model", MODEL)
                put("prompt", fullPrompt)
                put("size", size)
                put("response_format", "url")
                put("image", images)
                put("watermark", false)
                put("sequential_image_generation", "disabled")
            }

            val response = callApi(seedreamKey, body)
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
        // Seedream 推荐 JPEG; PNG 可能导致 UnsupportedImageFormat
        val bytes = if (file.extension.lowercase() == "png") {
            // PNG → JPEG 转换
            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                ?: throw Exception("无法解码图片: ${file.name}")
            val bos = java.io.ByteArrayOutputStream()

            // PNG 有透明通道时，先绘制到白色背景上再压 JPEG
            // 否则透明区域变黑色，导致 Seedream 生成的人物出现黑边/黑影
            if (bmp.hasAlpha()) {
                val solidBmp = android.graphics.Bitmap.createBitmap(
                    bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(solidBmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                bmp.recycle()
                solidBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, bos)
                solidBmp.recycle()
            } else {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, bos)
                bmp.recycle()
            }
            bos.toByteArray()
        } else {
            file.readBytes()
        }
        return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun callApi(apiKey: String, body: JSONObject): String {
        val url = "${BASE_URL.trimEnd('/')}/images/generations"
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
                throw Exception("Seedream API ${response.code}: ${responseBody.take(300)}")
            }
            responseBody
        }
    }
}
