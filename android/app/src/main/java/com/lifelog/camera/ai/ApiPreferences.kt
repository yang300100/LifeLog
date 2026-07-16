package com.lifelog.camera.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    // 使用 EncryptedSharedPreferences 保护 API Key
    // 如果设备不支持加密（API < 23），回退到普通 SharedPreferences
    private val prefs: SharedPreferences = try {
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            "ai_config_encrypted",
            androidx.security.crypto.MasterKey.Builder(context).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // 加密存储不可用时回退（不应在生产环境发生）
        android.util.Log.w("ApiPreferences", "EncryptedSharedPreferences 不可用，回退到明文存储")
        context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)
    }

    fun get(): AIClient.ApiConfig = AIClient.ApiConfig(
        baseUrl = prefs.getString("base_url", "https://api.openai.com/v1") ?: "",
        apiKey = prefs.getString("api_key", "") ?: "",
        model = prefs.getString("model", "gpt-4o") ?: "gpt-4o",
        temperature = prefs.getFloat("temperature", 1.0f)
    )

    /** 运行模式: "log" = 日志模式, "realtime" = 实时模式 */
    fun getMode(): String = prefs.getString("mode", "log") ?: "log"

    fun setMode(mode: String) {
        prefs.edit().putString("mode", mode).apply()
    }

    /** 每日校准日期（"yyyy-MM-dd"），当天已校准过则不再弹窗 */
    fun getLastCalibrateDate(): String = prefs.getString("calibrate_date", "") ?: ""

    fun setLastCalibrateDate(date: String) {
        prefs.edit().putString("calibrate_date", date).apply()
    }

    /** 每日反馈日期 */
    fun getLastFeedbackDate(): String = prefs.getString("feedback_date", "") ?: ""

    fun setLastFeedbackDate(date: String) {
        prefs.edit().putString("feedback_date", date).apply()
    }

    fun save(config: AIClient.ApiConfig) {
        prefs.edit()
            .putString("base_url", config.baseUrl)
            .putString("api_key", config.apiKey)
            .putString("model", config.model)
            .putFloat("temperature", config.temperature)
            .apply()
    }

    // ── Kimi API 配置 (场景分析 + 角色描述) ──

    fun getKimiConfig(): AIClient.ApiConfig = AIClient.ApiConfig(
        baseUrl = prefs.getString("kimi_base_url", "https://api.moonshot.cn/v1") ?: "",
        apiKey = prefs.getString("kimi_api_key", "") ?: "",
        model = prefs.getString("kimi_model", "kimi-k2.6") ?: "kimi-k2.6",
        temperature = prefs.getFloat("kimi_temperature", 0.6f)
    )

    fun saveKimiConfig(config: AIClient.ApiConfig) {
        prefs.edit()
            .putString("kimi_base_url", config.baseUrl)
            .putString("kimi_api_key", config.apiKey)
            .putString("kimi_model", config.model)
            .putFloat("kimi_temperature", config.temperature)
            .apply()
    }

    // ── Seedream 多图融合 API 配置 ──

    fun getSeedreamConfig(): AIClient.ApiConfig = AIClient.ApiConfig(
        baseUrl = prefs.getString("seedream_base_url", "https://ark.cn-beijing.volces.com/api/v3") ?: "",
        apiKey = prefs.getString("seedream_api_key", "") ?: "",
        model = prefs.getString("seedream_model", "doubao-seedream-5-0-260128") ?: "doubao-seedream-5-0-260128",
        temperature = 1.0f
    )

    fun saveSeedreamConfig(config: AIClient.ApiConfig) {
        prefs.edit()
            .putString("seedream_base_url", config.baseUrl)
            .putString("seedream_api_key", config.apiKey)
            .putString("seedream_model", config.model)
            .apply()
    }

    /** 便捷方法: 只取 Seedream API Key */
    fun getSeedreamApiKey(): String {
        return prefs.getString("seedream_api_key", "") ?: ""
    }

}
