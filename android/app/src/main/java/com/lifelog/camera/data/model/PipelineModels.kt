package com.lifelog.camera.data.model

import android.graphics.Bitmap

/**
 * LifeLog Companion — 新管线数据模型 (B方案: 分割+候选+Kimi筛选+Seedream融合)
 */

// ── YOLO 检测物体 ──

data class DetectedObject(
    val id: Int,
    val classId: Int,
    val className: String,
    val bbox: RectI,                    // 原图坐标 (x1,y1,x2,y2)
    val center: PointI,                 // 中心点
    val area: Float,                    // bbox 面积
    val mask: Bitmap?,                  // 分割 mask (原图尺寸, 可null表示无mask)
    val depthLayer: DepthLayer,         // 深度层
    val confidence: Float,
    val isInteractable: Boolean = false,
    val interactionTemplates: List<InteractionTemplate> = emptyList()
)

enum class DepthLayer { FOREGROUND, MIDGROUND, BACKGROUND }

data class RectI(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
    val width get() = x2 - x1
    val height get() = y2 - y1
}

data class PointI(val x: Int, val y: Int)

data class InteractionTemplate(
    val objectName: String,             // 物体中文名 (杯子/键盘/书本...)
    val className: String,              // 原始类名
    val actionTemplate: String,         // 交互动作模板
    val relativePosition: String = ""   // 相对候选位置的方向
)

// ── 候选位置 ──

data class CandidatePosition(
    val id: Int,
    val centerX: Int,
    val centerY: Int,
    val xRatio: Float,
    val yRatio: Float,
    val regionDescription: String,      // 中文区域描述 "画面右侧，键盘旁"
    val standableScore: Float = 0f,
    val nearbyObjects: List<String> = emptyList(),      // 附近物体中文名
    val interactionSuggestions: List<InteractionSuggestion> = emptyList()
)

data class InteractionSuggestion(
    val obj: String,                    // 物体中文名
    val action: String,                 // 中文动作描述
    val relativePosition: String = "",  // 相对候选位置的方向
    val distancePx: Int = 0             // 距离 (像素)
)

// ── Kimi 筛选结果 ──

data class KimiSelection(
    val bestCandidateId: Int,
    val selectedInteraction: SelectedInteraction?,
    val personFacing: String = "toward_camera",  // 角色面向
    val lightDirection: String = "",              // 光源方向
    val confidence: Float = 0.5f,
    val evaluations: List<CandidateEvaluation> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rawResponse: String = ""
)

data class SelectedInteraction(
    val obj: String = "",              // 交互物体中文名
    val detailedDescription: String = "", // 详细交互描述 (直接注入Seedream prompt)
    val pose: String = "",             // 姿态
    val facing: String = "",           // 面向
    val gazeTarget: String = ""        // 注视目标
)

data class CandidateEvaluation(
    val id: Int,
    val score: Double,
    val reason: String
)

// ── 管线状态 ──

sealed class PipelineState {
    data object Idle : PipelineState()
    data object Segmenting : PipelineState()
    data class GeneratingCandidates(val count: Int) : PipelineState()
    data object BuildingAnnotated : PipelineState()

    /** Step1 完成: 候选+标注图就绪, 等待用户确认 */
    data class CandidatesReady(val count: Int) : PipelineState()

    data object KimiSelecting : PipelineState()

    /** Step2 完成: Kimi 筛选完成, Seedream Prompt 已生成, 等待用户确认 */
    data class KimiDone(val bestCandidateId: Int) : PipelineState()

    data object SeedreamGenerating : PipelineState()

    /** Step3 完成生成, 正在进行后处理 (色彩+柔焦+颗粒) */
    data object PostProcessing : PipelineState()

    data class Done(val generationId: String, val resultPath: String) : PipelineState()
    data class Error(val msg: String) : PipelineState()
}

// ── 管线结果 ──

data class PipelineResult(
    val generationId: String,
    val timestamp: String,
    val objectCount: Int,
    val candidateCount: Int,
    val bestCandidate: BestCandidateInfo?,
    val kimiSelection: KimiSelectionInfo?,
    val outputPath: String?,
    val resultUrl: String?
)

data class BestCandidateInfo(
    val id: Int,
    val description: String,
    val x: Int,
    val y: Int
)

data class KimiSelectionInfo(
    val bestCandidateId: Int,
    val interaction: String,
    val facing: String,
    val light: String,
    val confidence: Float
)
