package com.lifelog.camera.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.lifelog.camera.ai.candidate.AnnotatedImageBuilder
import com.lifelog.camera.ai.candidate.CandidateGenerator
import com.lifelog.camera.ai.segmentation.HeuristicSegmenter
import com.lifelog.camera.ai.segmentation.NcnnYOLODetector
import com.lifelog.camera.ai.segmentation.SegmentResult
import com.lifelog.camera.ai.segmentation.Segmenter
import com.lifelog.camera.data.local.CompanionStorage
import com.lifelog.camera.data.model.*
import com.lifelog.camera.util.CrashLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LifeLog Companion — 管线编排器 (B方案)
 */
@Singleton
class CompanionPipeline @Inject constructor(
    private val kimiSelector: KimiSelector,
    private val seedreamGenerator: SeedreamGenerator,
    private val storage: CompanionStorage,
    private val apiPreferences: ApiPreferences,
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "CompanionPipeline"
    }

    // NCNN YOLOv8n 优先 (NDK 30); 失败自动回退 HeuristicSegmenter
    private val segmenter: Segmenter = try {
        NcnnYOLODetector(appContext, "yolov8n.param", "yolov8n.bin")
    } catch (e: Exception) {
        android.util.Log.w("CompanionPipeline", "NCNN 不可用, 回退 Heuristic: ${e.message}")
        HeuristicSegmenter()
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _kimiPrompt = MutableStateFlow(KimiSelector.DEFAULT_SELECTION_PROMPT)
    val kimiPrompt: StateFlow<String> = _kimiPrompt.asStateFlow()
    private val _seedreamPrompt = MutableStateFlow(SeedreamGenerator.SEEDREAM_PROMPT_TEMPLATE)
    val seedreamPrompt: StateFlow<String> = _seedreamPrompt.asStateFlow()
    private val _candidates = MutableStateFlow<List<CandidatePosition>>(emptyList())
    val candidates: StateFlow<List<CandidatePosition>> = _candidates.asStateFlow()
    private val _kimiSelection = MutableStateFlow<KimiSelection?>(null)
    val kimiSelection: StateFlow<KimiSelection?> = _kimiSelection.asStateFlow()

    /** 候选标注图 (step1 产出, UI 展示用) */
    private val _annotatedBitmap = MutableStateFlow<Bitmap?>(null)
    val annotatedBitmap: StateFlow<Bitmap?> = _annotatedBitmap.asStateFlow()

    // ── 管线内部状态 (跨步骤共享) ──
    private var cachedSceneBitmap: Bitmap? = null
    private var cachedObjects: List<DetectedObject> = emptyList()
    private var cachedSceneB64: String = ""
    private var cachedAnnotatedB64: String = ""
    private var genDir: File? = null
    private var generationId: String = ""
    private var cachedScenePath: String = ""
    private var cachedRefPaths: List<String> = emptyList()
    private var cachedRefHint: String = ""

    // ═══════════════════════════════════════════════════════
    // Step 1: 生成候选 + 标注图 (本地, 快速)
    // ═══════════════════════════════════════════════════════

    data class CandidateStepResult(
        val candidates: List<CandidatePosition>,
        val objectCount: Int
    )

    fun step1_generateCandidates(
        sceneBitmap: Bitmap,
        scenePath: String,
        clipId: Long,
        referencePaths: List<String>,
        referenceHint: String = ""
    ) {
        if (job?.isActive == true) { CrashLogger.w(TAG, "管线已在运行中"); return }

        cachedSceneBitmap = sceneBitmap
        cachedRefPaths = referencePaths
        cachedRefHint = referenceHint
        generationId = "gen_${System.currentTimeMillis()}_$clipId"
        genDir = storage.getGenerationDir(generationId)

        // 保存场景帧为 JPEG (供 Seedream 使用, 不能用视频路径)
        val sceneFile = File(genDir!!, "scene.jpg")
        FileOutputStream(sceneFile).use { out ->
            sceneBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        cachedScenePath = sceneFile.absolutePath

        _isRunning.value = true
        job = scope.launch {
            try {
                // 分割
                updateState(PipelineState.Segmenting)
                if (!segmenter.isReady) segmenter.load()
                var segResult: SegmentResult? = null
                try { segResult = segmenter.segment(sceneBitmap) }
                catch (e: Exception) { CrashLogger.w(TAG, "分割异常: ${e.message}") }

                cachedObjects = segResult?.objects ?: emptyList()
                val imgW = sceneBitmap.width; val imgH = sceneBitmap.height

                // 候选生成
                updateState(PipelineState.GeneratingCandidates(0))
                val gen = CandidateGenerator(cachedObjects, imgW, imgH)
                val candidates = gen.generate(5)
                _candidates.value = candidates
                updateState(PipelineState.GeneratingCandidates(candidates.size))

                // 标注图
                updateState(PipelineState.BuildingAnnotated)
                val annotatedBmp = AnnotatedImageBuilder(imgW, imgH)
                    .build(sceneBitmap, cachedObjects, candidates)
                _annotatedBitmap.value = annotatedBmp  // 暴露给 UI

                // 保存 + Base64 (为 step2 准备)
                val annotatedFile = File(genDir!!, "annotated.png")
                saveBitmap(annotatedBmp, annotatedFile)
                cachedAnnotatedB64 = bitmapToBase64(annotatedBmp, Bitmap.CompressFormat.PNG, 100)
                cachedSceneB64 = bitmapToBase64(sceneBitmap, Bitmap.CompressFormat.JPEG, 85)
                // 不回收 annotatedBmp — UI 还在用

                CrashLogger.i(TAG, "Step1 完成: ${candidates.size} 候选, ${cachedObjects.size} 物体")
                for (c in candidates) {
                    CrashLogger.i(TAG, "  #${c.id}: ${c.regionDescription} 附近=[${c.nearbyObjects.joinToString()}]")
                }
                updateState(PipelineState.CandidatesReady(candidates.size))

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CrashLogger.e(TAG, "Step1 失败: ${e.message}", e)
                updateState(PipelineState.Error(e.message ?: "未知错误"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Step 2: Kimi 候选筛选 + 构建 Seedream Prompt
    // ═══════════════════════════════════════════════════════

    fun step2_kimiSelect(customKimiPrompt: String? = null) {
        val candidates = _candidates.value
        if (candidates.isEmpty() || cachedSceneB64.isEmpty()) {
            CrashLogger.w(TAG, "Step2: 缺少候选或场景数据, 请先运行 step1")
            return
        }

        job = scope.launch {
            try {
                updateState(PipelineState.KimiSelecting)
                val kPrompt = customKimiPrompt ?: _kimiPrompt.value

                val selection = kimiSelector.select(
                    sceneBase64 = cachedSceneB64,
                    annotatedBase64 = cachedAnnotatedB64,
                    candidates = candidates,
                    customPrompt = kPrompt
                ).getOrNull()

                val effectiveSelection = selection ?: KimiSelection(
                    bestCandidateId = candidates.firstOrNull()?.id ?: -1,
                    selectedInteraction = null, confidence = 0f
                )
                _kimiSelection.value = effectiveSelection

                // 解析位置+交互 → 构建 Seedream Prompt
                val params = resolvePromptParams(candidates, effectiveSelection)
                val sdPrompt = SeedreamGenerator.buildSeedreamPrompt(params.posDesc, params.interactionDesc)
                _seedreamPrompt.value = sdPrompt

                CrashLogger.i(TAG, "Step2 完成: best=#${effectiveSelection.bestCandidateId} conf=${effectiveSelection.confidence}")
                CrashLogger.i(TAG, "Seedream prompt: ${sdPrompt.take(120)}...")
                updateState(PipelineState.KimiDone(effectiveSelection.bestCandidateId))

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CrashLogger.e(TAG, "Step2 失败: ${e.message}", e)
                updateState(PipelineState.Error(e.message ?: "未知错误"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Step 3: Seedream 生成 + 保存
    // ═══════════════════════════════════════════════════════

    fun step3_seedreamGenerate(
        customSeedreamPrompt: String? = null,
        outputSize: String = "2k"
    ) {
        val candidates = _candidates.value
        val selection = _kimiSelection.value
        if (candidates.isEmpty()) {
            CrashLogger.w(TAG, "Step3: 缺少数据, 请先运行 step1 + step2")
            return
        }

        job = scope.launch {
            try {
                updateState(PipelineState.SeedreamGenerating)

                val effectiveSelection = selection ?: KimiSelection(
                    bestCandidateId = candidates.firstOrNull()?.id ?: -1,
                    selectedInteraction = null, confidence = 0f
                )
                val params = resolvePromptParams(candidates, effectiveSelection)

                val resultUrl = seedreamGenerator.generate(
                    scenePath = cachedScenePath,
                    referencePaths = cachedRefPaths,
                    positionDesc = params.posDesc,
                    interactionDesc = params.interactionDesc,
                    referenceHint = cachedRefHint,
                    customPrompt = customSeedreamPrompt ?: _seedreamPrompt.value,
                    size = outputSize
                ).getOrElse { e ->
                    CrashLogger.e(TAG, "Seedream 生成失败: ${e.message}", e)
                    null
                }

                // 下载结果 → 后处理 → 保存
                val resultPath = if (resultUrl != null) {
                    val outputFile = File(genDir!!, "final_result.png")
                    try {
                        downloadFile(resultUrl, outputFile)
                        updateState(PipelineState.PostProcessing)
                        CrashLogger.i(TAG, "后处理开始: 色彩增强 + 高斯柔焦 + 胶片颗粒")
                        val rawBmp = BitmapFactory.decodeFile(outputFile.absolutePath)
                        if (rawBmp != null) {
                            val processed = ImagePostProcessor.process(rawBmp)
                            FileOutputStream(outputFile).use { out ->
                                processed.compress(Bitmap.CompressFormat.JPEG, 95, out)
                            }
                            processed.recycle()
                            rawBmp.recycle()
                            CrashLogger.i(TAG, "后处理完成: 饱和度${ImagePostProcessor.saturationFactor} 柔焦r=${ImagePostProcessor.blurRadius} 颗粒${ImagePostProcessor.grainIntensity}")
                        } else {
                            CrashLogger.w(TAG, "后处理跳过: 无法解码下载的图片")
                        }
                        outputFile.absolutePath
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        CrashLogger.e(TAG, "后处理异常: ${e.message}", e)
                        null
                    }
                } else null

                // 保存
                saveResults(
                    params.finalCandidate, effectiveSelection, resultPath, resultUrl,
                    _kimiPrompt.value, _seedreamPrompt.value
                )

                // 释放标注图
                _annotatedBitmap.value?.recycle()
                _annotatedBitmap.value = null

                CrashLogger.i(TAG, "✅ 管线完成: $generationId → ${resultPath ?: "无结果"}")
                updateState(PipelineState.Done(generationId, resultPath ?: ""))

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CrashLogger.e(TAG, "Step3 失败: ${e.message}", e)
                updateState(PipelineState.Error(e.message ?: "未知错误"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // 一键运行 (兼容旧调用)
    // ═══════════════════════════════════════════════════════

    fun runPipeline(
        sceneBitmap: Bitmap, scenePath: String, clipId: Long,
        referencePaths: List<String>,
        customKimiPrompt: String? = null, customSeedreamPrompt: String? = null,
        frameIndex: Int = 0, outputSize: String = "2k",
        referenceHint: String = ""
    ) {
        step1_generateCandidates(sceneBitmap, scenePath, clipId, referencePaths, referenceHint)
        // 在 step1 的 job 完成后自动续接 step2+step3
        scope.launch {
            job?.join()
            if (_state.value !is PipelineState.Error) {
                step2_kimiSelect(customKimiPrompt)
                job?.join()
                if (_state.value !is PipelineState.Error) {
                    step3_seedreamGenerate(customSeedreamPrompt, outputSize)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _isRunning.value = false
        _annotatedBitmap.value?.recycle()
        _annotatedBitmap.value = null
        cachedSceneBitmap?.recycle()
        cachedSceneBitmap = null
        cachedSceneB64 = ""
        cachedAnnotatedB64 = ""
        updateState(PipelineState.Idle)
    }

    // ── Prompt 编辑 ──
    fun updateKimiPrompt(p: String) { _kimiPrompt.value = p }
    fun updateSeedreamPrompt(p: String) { _seedreamPrompt.value = p }
    fun getCandidates(): List<CandidatePosition> = _candidates.value

    // ── 候选增删 (用户手动调整) ──

    /** 删除指定候选并重新编号, 同时清除旧的 Kimi 结果 */
    fun removeCandidate(id: Int) {
        val current = _candidates.value.toMutableList()
        current.removeAll { it.id == id }
        _candidates.value = current.mapIndexed { i, c -> c.copy(id = i + 1) }
        _kimiSelection.value = null              // 清除旧评估
        _seedreamPrompt.value = SeedreamGenerator.SEEDREAM_PROMPT_TEMPLATE  // 重置模板
        refreshAnnotatedBitmap()
    }

    /** 添加用户自定义候选, 同时清除旧的 Kimi 结果 */
    fun addCandidate(xRatio: Float, yRatio: Float, description: String) {
        val current = _candidates.value.toMutableList()
        val imgW = cachedSceneBitmap?.width ?: 640
        val imgH = cachedSceneBitmap?.height ?: 480
        val newId = (current.maxOfOrNull { it.id } ?: 0) + 1
        // 生成更详细的位置描述
        val fullDesc = if (description.isNotBlank()) {
            "用户指定: ${description} (水平${(xRatio*100).toInt()}%, 垂直${(yRatio*100).toInt()}%)"
        } else {
            "用户自定义位置 (水平${(xRatio*100).toInt()}%, 垂直${(yRatio*100).toInt()}%)"
        }
        current.add(CandidatePosition(
            id = newId,
            centerX = (xRatio * imgW).toInt(),
            centerY = (yRatio * imgH).toInt(),
            xRatio = xRatio, yRatio = yRatio,
            regionDescription = fullDesc,
            standableScore = 0.5f
        ))
        _candidates.value = current
        _kimiSelection.value = null              // 清除旧评估
        _seedreamPrompt.value = SeedreamGenerator.SEEDREAM_PROMPT_TEMPLATE  // 重置模板
        refreshAnnotatedBitmap()
    }

    /** 重新生成标注图 (候选变更后) */
    private fun refreshAnnotatedBitmap() {
        val bmp = cachedSceneBitmap ?: return
        val candidates = _candidates.value
        if (candidates.isEmpty()) return
        _annotatedBitmap.value?.recycle()
        _annotatedBitmap.value = AnnotatedImageBuilder(bmp.width, bmp.height)
            .build(bmp, cachedObjects, candidates)
    }

    // ── 内部 ──

    /** 从候选列表和 Kimi 筛选结果中解析 posDesc + interactionDesc + finalCandidate */
    private data class PromptParams(
        val posDesc: String,
        val interactionDesc: String,
        val finalCandidate: CandidatePosition?
    )

    private fun resolvePromptParams(
        candidates: List<CandidatePosition>,
        selection: KimiSelection
    ): PromptParams {
        val bestCandidate = if (selection.bestCandidateId > 0)
            candidates.find { it.id == selection.bestCandidateId } else null
        val finalCandidate = bestCandidate
            ?: candidates.maxByOrNull { it.standableScore }
            ?: candidates.firstOrNull()

        return PromptParams(
            posDesc = finalCandidate?.regionDescription ?: "画面中央偏下",
            interactionDesc = selection.selectedInteraction?.detailedDescription
                ?: finalCandidate?.interactionSuggestions?.firstOrNull()?.action
                ?: "自然站立，面朝前方，带着温柔的微笑",
            finalCandidate = finalCandidate
        )
    }

    private fun updateState(state: PipelineState) {
        _state.value = state
        _isRunning.value = state !is PipelineState.Done
                && state !is PipelineState.Error
                && state !is PipelineState.Idle
    }

    private fun saveResults(
        finalCandidate: CandidatePosition?, selection: KimiSelection,
        resultPath: String?, resultUrl: String?, kPrompt: String, sdPrompt: String
    ) {
        val pipelineResult = PipelineResult(
            generationId = generationId,
            timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date()),
            objectCount = cachedObjects.size,
            candidateCount = _candidates.value.size,
            bestCandidate = finalCandidate?.let { BestCandidateInfo(it.id, it.regionDescription, it.centerX, it.centerY) },
            kimiSelection = KimiSelectionInfo(selection.bestCandidateId,
                selection.selectedInteraction?.detailedDescription ?: "",
                selection.personFacing, selection.lightDirection, selection.confidence),
            outputPath = resultPath, resultUrl = resultUrl
        )
        storage.savePipelineResult(generationId, pipelineResult)

        val meta = CompanionMeta(
            generationId = generationId, clipId = 0, sceneFrameIndex = 0,
            createdAt = System.currentTimeMillis(), completedAt = System.currentTimeMillis(),
            status = if (resultPath != null) "done" else "error",
            analysis = null, characterDescription = "",
            prompts = mapOf("kimi_prompt" to kPrompt, "seedream_prompt" to sdPrompt),
            positionKey = finalCandidate?.regionDescription ?: "",
            poseKey = selection.selectedInteraction?.pose ?: "standing"
        )
        storage.saveMeta(generationId, meta)
    }

    private fun bitmapToBase64(bmp: Bitmap, format: Bitmap.CompressFormat, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(format, quality, baos)
        val bytes = baos.toByteArray()
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        return "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun saveBitmap(bmp: Bitmap, file: File) {
        val ext = file.extension.lowercase()
        val format = if (ext == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        FileOutputStream(file).use { out -> bmp.compress(format, 95, out) }
    }

    private fun downloadFile(url: String, file: File) {
        val okClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(url).build()
        okClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("下载失败 ${response.code}")
            response.body?.byteStream()?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        }
    }
}
