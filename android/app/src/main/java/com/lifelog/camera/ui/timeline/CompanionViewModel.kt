package com.lifelog.camera.ui.timeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.lifelog.camera.ai.*
import com.lifelog.camera.data.local.CompanionStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import com.lifelog.camera.data.model.*
import com.lifelog.camera.util.CrashLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Companion ViewModel — B方案 逐步交互式管线
 *
 * 流程:
 *   选帧 → Step1(候选+标注图) → 用户确认 → Step2(Kimi筛选) → 用户确认 → Step3(Seedream生成)
 */
@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val pipeline: CompanionPipeline,
    private val storage: CompanionStorage,
    private val keyframeExtractor: KeyframeExtractor,
    private val apiPreferences: ApiPreferences,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    // ── 管线状态 ──
    val pipelineState: StateFlow<PipelineState> = pipeline.state
    val isRunning: StateFlow<Boolean> = pipeline.isRunning

    // ── 帧选择 ──
    private val _extractedFrames = MutableStateFlow<List<ByteArray>>(emptyList())
    val extractedFrames: StateFlow<List<ByteArray>> = _extractedFrames.asStateFlow()
    private val _selectedFrameIndex = MutableStateFlow(-1)
    val selectedFrameIndex: StateFlow<Int> = _selectedFrameIndex.asStateFlow()
    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()
    private var currentClipId: Long = 0
    private var currentClipPath: String = ""
    private var currentSceneBitmap: Bitmap? = null

    // ── Prompt (中文, 可编辑) ──
    val kimiPrompt: StateFlow<String> = pipeline.kimiPrompt
    val seedreamPrompt: StateFlow<String> = pipeline.seedreamPrompt

    // ── 候选 + 标注图 ──
    val candidates: StateFlow<List<CandidatePosition>> = pipeline.candidates
    val annotatedBitmap: StateFlow<Bitmap?> = pipeline.annotatedBitmap
    val kimiSelection: StateFlow<KimiSelection?> = pipeline.kimiSelection

    // ── 画廊 ──
    private val _generations = MutableStateFlow<List<CompanionGenerationSummary>>(emptyList())
    val generations: StateFlow<List<CompanionGenerationSummary>> = _generations.asStateFlow()

    // ── Seedream Key ──
    private val _seedreamKeyConfigured = MutableStateFlow(false)
    val seedreamKeyConfigured: StateFlow<Boolean> = _seedreamKeyConfigured.asStateFlow()

    init {
        refreshGallery()
        checkSeedreamKey()
        // 管线完成时自动刷新画廊 + 停止后台服务
        viewModelScope.launch {
            pipeline.state.collect { state ->
                when (state) {
                    is PipelineState.Done -> {
                        refreshGallery()
                        CompanionService.stop(appContext)
                    }
                    is PipelineState.Error -> {
                        CompanionService.stop(appContext)
                    }
                    else -> {}
                }
            }
        }
    }

    // ── 帧提取 ──
    fun startFrameExtraction(videoPath: String, clipId: Long) {
        currentClipId = clipId; currentClipPath = videoPath
        _selectedFrameIndex.value = -1
        currentSceneBitmap?.recycle(); currentSceneBitmap = null

        viewModelScope.launch {
            _isExtracting.value = true
            try {
                val file = File(videoPath)
                if (!file.exists()) { _extractedFrames.value = emptyList(); return@launch }
                val frames = withContext(Dispatchers.IO) {
                    if (file.extension.equals("mp4", ignoreCase = true)) {
                        keyframeExtractor.extractMp4Frames(videoPath, 12)
                    } else {
                        keyframeExtractor.readAllFrames(file).let { all ->
                            val step = maxOf(1, all.size / 10)
                            all.filterIndexed { i, _ -> i % step == 0 || i == all.lastIndex }.take(12)
                        }
                    }
                }
                _extractedFrames.value = frames
            } catch (e: Exception) { _extractedFrames.value = emptyList() }
            finally { _isExtracting.value = false }
        }
    }

    fun selectFrame(index: Int) {
        _selectedFrameIndex.value = index
        currentSceneBitmap?.recycle()
        currentSceneBitmap = _extractedFrames.value.getOrNull(index)?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size)
        }
    }

    // ── Step 1: 生成候选 + 标注图 ──
    fun step1_generateCandidates() {
        val bmp = currentSceneBitmap ?: return
        val refPaths = getRefPaths()
        CompanionService.start(appContext)  // 启动前台服务保活
        pipeline.step1_generateCandidates(bmp, currentClipPath, currentClipId, refPaths, buildRefHint())
    }

    // ── Step 2: Kimi 筛选 ──
    fun step2_kimiSelect(customPrompt: String? = null) {
        pipeline.step2_kimiSelect(customPrompt ?: kimiPrompt.value)
    }

    // ── Step 3: Seedream 生成 ──
    fun step3_seedreamGenerate(customPrompt: String? = null) {
        pipeline.step3_seedreamGenerate(customPrompt ?: seedreamPrompt.value)
    }

    // ── 一键运行 ──
    fun runFullPipeline() {
        val bmp = currentSceneBitmap ?: return
        CompanionService.start(appContext)
        pipeline.runPipeline(bmp, currentClipPath, currentClipId, getRefPaths(), referenceHint = buildRefHint())
    }

    fun cancel() { pipeline.cancel() }

    // ── 候选增删 ──
    fun deleteCandidate(id: Int) { pipeline.removeCandidate(id) }
    fun addCandidate(xRatio: Float, yRatio: Float, description: String) {
        pipeline.addCandidate(xRatio, yRatio, description)
    }

    // ── Prompt 编辑 ──
    fun updateKimiPrompt(p: String) { pipeline.updateKimiPrompt(p) }
    fun updateSeedreamPrompt(p: String) { pipeline.updateSeedreamPrompt(p) }
    fun resetKimiPrompt() { pipeline.updateKimiPrompt(KimiSelector.DEFAULT_SELECTION_PROMPT) }
    fun buildDefaultSeedreamPrompt(): String {
        val cands = pipeline.getCandidates()
        val best = cands.maxByOrNull { it.standableScore }
        val pos = best?.regionDescription ?: "画面中央偏下"
        val act = best?.interactionSuggestions?.firstOrNull()?.action ?: "自然站立"
        return SeedreamGenerator.buildSeedreamPrompt(pos, act)
    }

    // ── Seedream Key ──
    fun checkSeedreamKey() { _seedreamKeyConfigured.value = apiPreferences.getSeedreamApiKey().isNotBlank() }
    fun saveSeedreamApiKey(key: String) {
        apiPreferences.saveSeedreamConfig(AIClient.ApiConfig(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey = key, model = "doubao-seedream-5-0-260128"
        ))
        _seedreamKeyConfigured.value = key.isNotBlank()
    }

    // ── 画廊 ──
    fun refreshGallery() {
        viewModelScope.launch { _generations.value = withContext(Dispatchers.IO) { storage.listGenerations() } }
    }
    fun deleteGeneration(generationId: String) {
        viewModelScope.launch { withContext(Dispatchers.IO) { storage.deleteGeneration(generationId) }; refreshGallery() }
    }

    private fun getRefPaths(): List<String> {
        return storage.getCharacterRefFiles().map { it.absolutePath }
    }

    /**
     * 构建参考图指引前缀 — 遵循 Seedream 多图输入官方格式:
     *   清楚指明不同图像需要参考的对象
     *   例如: "参考图中角色的形象特征，参考画风图的渲染风格。"
     */
    private fun buildRefHint(): String {
        val files = storage.getCharacterRefFiles()
        if (files.isEmpty()) return ""
        val metas = storage.loadRefMetas()

        // 按类别分组文件名
        val byCategory = mutableMapOf<ReferenceCategory, MutableList<String>>()
        for (f in files) {
            val cat = metas[f.name] ?: ReferenceCategory.CHARACTER
            byCategory.getOrPut(cat) { mutableListOf() }.add(f.name)
        }
        if (byCategory.isEmpty()) return ""

        // 为每种类别生成参考指引
        val guides = mutableListOf<String>()
        // 角色类优先
        listOf(ReferenceCategory.CHARACTER, ReferenceCategory.MULTI_ANGLE).forEach { cat ->
            if (cat in byCategory) {
                guides.add("参考图中角色的形象特征")
            }
        }
        if (ReferenceCategory.ART_STYLE in byCategory) {
            guides.add("参考画风图的渲染风格与色调")
        }
        if (ReferenceCategory.POSE in byCategory) {
            guides.add("参考姿态图的构图与动作感觉")
        }

        return if (guides.isNotEmpty()) {
            "${guides.joinToString("，")}。"
        } else {
            "参考图中角色的形象特征。"
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentSceneBitmap?.recycle()
    }
}
