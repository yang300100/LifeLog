package com.lifelog.camera.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.camera.ai.AIClient
import com.lifelog.camera.ai.ApiPreferences
import com.lifelog.camera.ble.BleFileTransfer
import com.lifelog.camera.data.local.CompanionStorage
import com.lifelog.camera.util.CrashLogger
import com.lifelog.camera.data.model.ReferenceCategory
import com.lifelog.camera.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val apiPreferences: ApiPreferences,
    private val companionStorage: CompanionStorage,
    val bleTransfer: BleFileTransfer,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val clipCount = MutableStateFlow(0)
    val videoDirSize = MutableStateFlow("0 MB")

    // API 配置
    val apiBaseUrl = MutableStateFlow("")
    val apiKey = MutableStateFlow("")
    val apiModel = MutableStateFlow("")
    val apiTemperature = MutableStateFlow("1.0")
    val isSaved = MutableStateFlow(false)

    // 运行模式
    val isRealtimeMode = MutableStateFlow(false)

    init {
        // 加载存储数据
        try {
            val config = apiPreferences.get()
            apiBaseUrl.value = config.baseUrl
            apiKey.value = config.apiKey
            apiModel.value = config.model
            apiTemperature.value = config.temperature.toString()
            isRealtimeMode.value = apiPreferences.getMode() == "realtime"
        } catch (e: Exception) {
            CrashLogger.e("SettingsVM", "配置读取失败", e)
        }

        viewModelScope.launch {
            repository.getAllClipsFlow().collect { clips ->
                clipCount.value = clips.size
                // 文件大小计算移到 IO 线程执行，避免阻塞主线程
                withContext(Dispatchers.IO) {
                    val dir = repository.getVideoDir()
                    val totalBytes = dir.walkTopDown()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                    videoDirSize.value = "%.1f MB".format(totalBytes / (1024.0 * 1024.0))
                }
            }
        }
    }

    fun toggleMode() {
        val newMode = if (isRealtimeMode.value) "log" else "realtime"
        apiPreferences.setMode(newMode)
        isRealtimeMode.value = newMode == "realtime"
    }

    fun saveApiConfig() {
        apiPreferences.save(AIClient.ApiConfig(
            baseUrl = apiBaseUrl.value,
            apiKey = apiKey.value,
            model = apiModel.value,
            temperature = apiTemperature.value.toFloatOrNull() ?: 1.0f
        ))
        isSaved.value = true
    }

    // ── 同伴配置 ──

    val kimiApiKey = MutableStateFlow("")
    val seedreamApiKey = MutableStateFlow("")
    val seedreamBaseUrl = MutableStateFlow("")
    val seedreamModel = MutableStateFlow("")

    init {
        try {
            val kimiCfg = apiPreferences.getKimiConfig()
            kimiApiKey.value = kimiCfg.apiKey
        } catch (e: Exception) {
            CrashLogger.e("SettingsVM", "Kimi 配置读取失败", e)
        }
        try {
            seedreamApiKey.value = apiPreferences.getSeedreamApiKey()
            val sdCfg = apiPreferences.getSeedreamConfig()
            seedreamBaseUrl.value = sdCfg.baseUrl
            seedreamModel.value = sdCfg.model
        } catch (e: Exception) {
            CrashLogger.e("SettingsVM", "Seedream 配置读取失败", e)
        }
    }

    fun saveKimiKey(key: String) {
        val current = apiPreferences.getKimiConfig()
        apiPreferences.saveKimiConfig(current.copy(apiKey = key))
        kimiApiKey.value = key
    }

    fun saveSeedreamConfig(key: String, baseUrl: String, model: String) {
        apiPreferences.saveSeedreamConfig(
            AIClient.ApiConfig(baseUrl = baseUrl, apiKey = key, model = model)
        )
        seedreamApiKey.value = key
        seedreamBaseUrl.value = baseUrl
        seedreamModel.value = model
    }

    // ── 角色文字描述 ──
    val characterDescription = MutableStateFlow("")

    init {
        characterDescription.value = companionStorage.loadProfile().characterDescription
    }

    fun saveCharacterDescription(desc: String) {
        viewModelScope.launch {
            val profile = companionStorage.loadProfile()
            companionStorage.saveProfile(profile.copy(characterDescription = desc))
            characterDescription.value = desc
        }
    }

    // ── 角色参考图 (多图 + 分类) ──

    val hasCharacterRef = MutableStateFlow(companionStorage.hasAnyCharacterReference())
    val characterRefBitmaps = MutableStateFlow(companionStorage.getCharacterRefFiles().mapNotNull {
        try {
            val bmp = BitmapFactory.decodeFile(it.absolutePath) ?: return@mapNotNull null
            // 缩放到 256px 宽（缩略图展示用，防止超大原图导致 Canvas 崩溃）
            if (bmp.width > 256) {
                val ratio = 256f / bmp.width
                val scaled = Bitmap.createScaledBitmap(bmp, 256, (bmp.height * ratio).toInt(), true)
                bmp.recycle()
                scaled
            } else bmp
        } catch (e: Exception) { null }
    })
    val characterRefFileNames = MutableStateFlow(companionStorage.getCharacterRefFiles().map { it.name })
    // 文件名 → 分类
    val refCategories = MutableStateFlow(companionStorage.loadRefMetas())
    private var nextRefIndex = MutableStateFlow(
        companionStorage.getCharacterRefFiles().size
    )

    /** 刷新参考图状态 (bitmaps + fileNames + categories) */
    private fun refreshRefState() {
        val files = companionStorage.getCharacterRefFiles()
        characterRefBitmaps.value = files.mapNotNull {
            try {
                val bmp = BitmapFactory.decodeFile(it.absolutePath) ?: return@mapNotNull null
                if (bmp.width > 256) {
                    val ratio = 256f / bmp.width
                    val scaled = Bitmap.createScaledBitmap(bmp, 256, (bmp.height * ratio).toInt(), true)
                    bmp.recycle()
                    scaled
                } else bmp
            } catch (e: Exception) { null }
        }
        characterRefFileNames.value = files.map { it.name }
        refCategories.value = companionStorage.loadRefMetas()
        hasCharacterRef.value = files.isNotEmpty()
    }

    fun addCharacterReference(uri: Uri) {
        viewModelScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    val idx = nextRefIndex.value
                    withContext(Dispatchers.IO) {
                        companionStorage.saveCharacterReference(idx, bitmap)
                        // 新图默认标记为"角色参考"
                        val fileName = if (idx == 0) "character_ref.png"
                                       else "character_ref_$idx.png"
                        companionStorage.setRefCategory(fileName, ReferenceCategory.CHARACTER)
                    }
                    nextRefIndex.value = idx + 1
                    refreshRefState()
                }
            } catch (e: Exception) { /* 忽略 */ }
        }
    }

    /** 删除参考图: 按 UI 显示位置找到实际文件后删除 */
    fun removeCharacterRef(displayIndex: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                companionStorage.deleteCharacterRefAt(displayIndex)
            }
            refreshRefState()
        }
    }

    /** 设置某张参考图的分类 */
    fun setRefCategory(displayIndex: Int, category: ReferenceCategory) {
        val files = companionStorage.getCharacterRefFiles()
        if (displayIndex >= files.size) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                companionStorage.setRefCategory(files[displayIndex].name, category)
            }
            refreshRefState()
        }
    }
}
