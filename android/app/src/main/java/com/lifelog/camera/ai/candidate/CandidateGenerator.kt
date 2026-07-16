package com.lifelog.camera.ai.candidate

import com.lifelog.camera.data.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 摄影构图候选生成器 — 纯 Kotlin，无外部依赖
 *
 * 基于 YOLO 分割结果 + 经典摄影构图规则:
 * 1. 构建占用图 (障碍物mask区域)
 * 2. 构建地面图 (Y坐标启发式)
 * 3. 连通域分析找到可站立区域
 * 4. 以多种摄影构图法则生成候选位置
 * 5. 为每个候选匹配附近可交互物体
 *
 * 支持的构图方式:
 *   - 三分法 (Rule of Thirds)
 *   - 黄金分割 (Golden Ratio)
 *   - 对称构图 (Symmetry)
 *   - 对角线构图 (Diagonal)
 *   - 负空间构图 (Negative Space)
 *   - 中心构图 (Center Focus)
 */
class CandidateGenerator(
    private val objects: List<DetectedObject>,
    private val imageWidth: Int,
    private val imageHeight: Int
) {
    private val w = imageWidth
    private val h = imageHeight

    // ═══════════════════════════════════════════════════════
    // 构图风格定义
    // ═══════════════════════════════════════════════════════

    private enum class CompositionStyle(
        val label: String,           // 构图名称
        val characterGuide: String   // 人物大小参考 + 构图姿态引导 (注入prompt, AI自行微调)
    ) {
        RULE_OF_THIRDS(
            "三分法构图",
            "人物约占画面三分之一高度，自然融入场景节奏与空间层次"
        ),
        GOLDEN_RATIO(
            "黄金分割构图",
            "人物约占画面四分之一到三分之一高度，位于黄金分割视觉重心，比例优雅和谐"
        ),
        SYMMETRY(
            "对称构图",
            "人物约占画面四分之一高度，居中而立，左右画面均衡对称，姿态端庄稳重"
        ),
        DIAGONAL(
            "对角线构图",
            "人物约占画面四分之一到三分之一高度，沿对角线方向自然站立，画面富有动感与纵深感"
        ),
        NEGATIVE_SPACE(
            "负空间构图",
            "人物约占画面八分之一到十分之一高度，小巧如点缀，周围留白充裕，营造安静陪伴的意境"
        ),
        CENTER_FOCUS(
            "中心构图",
            "人物约占画面三分之一高度，位于正中偏下作为视觉锚点，直接而温和"
        )
    }

    /**
     * 构图锚点定义
     * @param xRatio 水平比例 (0~1)
     * @param yRatio 垂直比例 (0~1)
     * @param positionName 位置名称
     * @param style 构图风格
     * @param priority 优先级 (越小越优先)
     */
    private data class CompositionAnchor(
        val xRatio: Float, val yRatio: Float,
        val positionName: String,
        val style: CompositionStyle,
        val priority: Int
    )

    /** 全部构图锚点，按优先级排列 */
    private val anchors: List<CompositionAnchor> = listOf(
        // ── 三分法构图 (Rule of Thirds) ──
        CompositionAnchor(1f/3, 2f/3, "九宫格左下交点", CompositionStyle.RULE_OF_THIRDS, 0),
        CompositionAnchor(2f/3, 2f/3, "九宫格右下交点", CompositionStyle.RULE_OF_THIRDS, 0),
        CompositionAnchor(1f/3, 1f/3, "九宫格左上交点", CompositionStyle.RULE_OF_THIRDS, 2),
        CompositionAnchor(2f/3, 1f/3, "九宫格右上交点", CompositionStyle.RULE_OF_THIRDS, 2),

        // ── 黄金分割构图 (Golden Ratio, φ≈0.618) ──
        CompositionAnchor(0.382f, 0.618f, "黄金分割左下点", CompositionStyle.GOLDEN_RATIO, 1),
        CompositionAnchor(0.618f, 0.618f, "黄金分割右下点", CompositionStyle.GOLDEN_RATIO, 1),
        CompositionAnchor(0.382f, 0.382f, "黄金分割左上点", CompositionStyle.GOLDEN_RATIO, 3),
        CompositionAnchor(0.618f, 0.382f, "黄金分割右上点", CompositionStyle.GOLDEN_RATIO, 3),

        // ── 对称构图 (Symmetry) ──
        CompositionAnchor(0.5f, 2f/3, "中轴偏下", CompositionStyle.SYMMETRY, 1),
        CompositionAnchor(0.5f, 0.5f, "画面正中", CompositionStyle.SYMMETRY, 3),

        // ── 对角线构图 (Diagonal) ──
        // 沿左上→右下对角线
        CompositionAnchor(0.25f, 0.3f,  "对角线上段", CompositionStyle.DIAGONAL, 2),
        CompositionAnchor(0.5f,  0.55f, "对角线中段", CompositionStyle.DIAGONAL, 2),
        CompositionAnchor(0.75f, 0.8f,  "对角线下段", CompositionStyle.DIAGONAL, 2),
        // 沿右上→左下对角线
        CompositionAnchor(0.75f, 0.3f,  "反对角线上段", CompositionStyle.DIAGONAL, 3),
        CompositionAnchor(0.25f, 0.8f,  "反对角线下段", CompositionStyle.DIAGONAL, 3),

        // ── 负空间构图 (Negative Space) — 边缘位置，人物小而意境深远 ──
        CompositionAnchor(0.15f, 0.55f, "左侧负空间", CompositionStyle.NEGATIVE_SPACE, 3),
        CompositionAnchor(0.85f, 0.55f, "右侧负空间", CompositionStyle.NEGATIVE_SPACE, 3),
        CompositionAnchor(0.5f,  0.35f, "上方负空间", CompositionStyle.NEGATIVE_SPACE, 4),

        // ── 中心构图兜底 ──
        CompositionAnchor(0.5f, 2f/3, "中央偏下", CompositionStyle.CENTER_FOCUS, 1),
    )

    // 去重阈值 (像素)
    private val dedupThresholdPx = 40

    // ═══════════════════════════════════════════════════════
    // 公开接口
    // ═══════════════════════════════════════════════════════

    /** 生成候选位置 (最多 maxCount 个) */
    fun generate(maxCount: Int = 5): List<CandidatePosition> {
        val regions = findStandableRegions()

        val candidates = mutableListOf<CandidatePosition>()
        var nextId = 1
        val usedPoints = mutableSetOf<Pair<Int, Int>>()  // 去重用

        for (anchor in anchors.sortedBy { it.priority }) {
            if (nextId > maxCount) break

            val cx = (anchor.xRatio * w).toInt().coerceIn(0, w - 1)
            val cy = (anchor.yRatio * h).toInt().coerceIn(0, h - 1)

            // 去重: 与已有候选过近则跳过
            if (usedPoints.any { (ux, uy) ->
                abs(ux - cx) < dedupThresholdPx && abs(uy - cy) < dedupThresholdPx
            }) continue

            // 找到最佳可站立区域
            val bestRegion = if (regions.isNotEmpty()) {
                regions.firstOrNull { r -> cx in r.x1..r.x2 && cy in r.y1..r.y2 }
                    ?: regions.minByOrNull { r ->
                        val dx = if (cx in r.x1..r.x2) 0
                        else min(abs(cx - r.x1), abs(cx - r.x2))
                        val dy = if (cy in r.y1..r.y2) 0
                        else min(abs(cy - r.y1), abs(cy - r.y2))
                        dx * dx + dy * dy
                    }
            } else null

            // 确定实际坐标
            val (useX, useY, inRegion) = if (bestRegion != null) {
                val inReg = cx in bestRegion.x1..bestRegion.x2 && cy in bestRegion.y1..bestRegion.y2
                if (inReg) Triple(cx, cy, true)
                else Triple(
                    cx.coerceIn(bestRegion.x1, bestRegion.x2),
                    cy.coerceIn(bestRegion.y1, bestRegion.y2),
                    false
                )
            } else Triple(cx, cy, false)

            usedPoints.add(Pair(useX, useY))
            val nearby = findNearbyObjects(useX, useY)

            // 可站立评分: 在区域内 + 高优先级 → 高分
            val regionBonus = if (inRegion) 0.3f else 0f
            val priorityPenalty = anchor.priority * 0.12f
            val standable = (0.7f + regionBonus - priorityPenalty).coerceIn(0f, 1f)

            // 位置描述: 构图风格 + 具体位置 + 人物引导 + 参照物
            val detailDesc = buildString {
                append(anchor.style.label)
                append("，")
                append(anchor.positionName)
                append("，")
                append(anchor.style.characterGuide)
                if (nearby.isNotEmpty()) {
                    val first = nearby.first()
                    append("，${first.relativePosition}${first.obj}旁")
                }
            }

            candidates.add(CandidatePosition(
                id = nextId,
                centerX = useX, centerY = useY,
                xRatio = useX.toFloat() / w, yRatio = useY.toFloat() / h,
                regionDescription = detailDesc,
                standableScore = standable,
                nearbyObjects = nearby.map { it.obj },
                interactionSuggestions = nearby
            ))
            nextId++
        }

        if (candidates.isEmpty()) return fallbackCandidates(maxCount)
        return candidates.sortedByDescending { it.standableScore }.take(maxCount)
    }

    // ── 内部: 找到可站立区域 ──

    private data class StandableRegion(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val centerX: Int, val centerY: Int,
        val area: Int, val areaRatio: Float
    )

    private fun findStandableRegions(): List<StandableRegion> {
        val gridCols = 8
        val gridRows = 6
        val cellW = w / gridCols
        val cellH = h / gridRows

        val occupied = Array(gridRows) { BooleanArray(gridCols) }
        for (obj in objects) {
            if (obj.classId in OBSTACLE_IDS || obj.className == "person") {
                val gx1 = (obj.bbox.x1 / cellW).coerceIn(0, gridCols - 1)
                val gy1 = (obj.bbox.y1 / cellH).coerceIn(0, gridRows - 1)
                val gx2 = (obj.bbox.x2 / cellW).coerceIn(0, gridCols - 1)
                val gy2 = (obj.bbox.y2 / cellH).coerceIn(0, gridRows - 1)
                for (gy in gy1..gy2) for (gx in gx1..gx2) occupied[gy][gx] = true
            }
        }

        val groundCells = mutableListOf<Triple<Int, Int, Int>>()
        for (row in gridRows / 2 until gridRows) {
            for (col in 0 until gridCols) {
                if (!occupied[row][col]) {
                    val rowScore = (row - gridRows / 2).toFloat() / (gridRows / 2).toFloat()
                    val colScore = 1f - abs(col - gridCols / 2).toFloat() / (gridCols / 2).toFloat()
                    groundCells.add(Triple(row, col, (rowScore * 0.6 + colScore * 0.4).let { (it * 100).toInt() }))
                }
            }
        }

        if (groundCells.isEmpty()) return emptyList()

        groundCells.sortByDescending { it.third }
        val regions = mutableListOf<StandableRegion>()
        val used = mutableSetOf<Pair<Int, Int>>()

        for ((row, col, _) in groundCells.take(6)) {
            if (Pair(row, col) in used) continue
            var rMin = row; var rMax = row
            var cMin = col; var cMax = col
            var totalCells = 0
            for (dr in -1..1) for (dc in -1..1) {
                val nr = (row + dr).coerceIn(0, gridRows - 1)
                val nc = (col + dc).coerceIn(0, gridCols - 1)
                if (!occupied[nr][nc] && Pair(nr, nc) !in used) {
                    rMin = min(rMin, nr); rMax = max(rMax, nr)
                    cMin = min(cMin, nc); cMax = max(cMax, nc)
                    used.add(Pair(nr, nc))
                    totalCells++
                }
            }
            if (totalCells == 0) continue

            val x1 = cMin * cellW; val y1 = rMin * cellH
            val x2 = min((cMax + 1) * cellW, w); val y2 = min((rMax + 1) * cellH, h)
            val area = (x2 - x1) * (y2 - y1)
            regions.add(StandableRegion(
                x1, y1, x2, y2,
                (x1 + x2) / 2, (y1 + y2) / 2,
                area, area.toFloat() / (w * h)
            ))
        }

        return regions.sortedByDescending { it.area }
    }

    // ── 内部: 搜索附近可交互物体 ──

    private fun findNearbyObjects(cx: Int, cy: Int): List<InteractionSuggestion> {
        val maxDist = max(w.toFloat() * 0.25f, h.toFloat() * 0.25f)
        val nearby = mutableListOf<InteractionSuggestion>()

        for (obj in objects) {
            if (!obj.isInteractable) continue
            val ox = obj.center.x; val oy = obj.center.y
            val dist = sqrt(((cx - ox).toFloat().pow2() + (cy - oy).toFloat().pow2()).toDouble()).toFloat()
            if (dist >= maxDist) continue

            val relPos = if (abs(ox - cx) > abs(oy - cy)) {
                if (ox > cx) "右侧" else "左侧"
            } else {
                if (oy > cy) "下方" else "上方"
            }

            for (tmpl in obj.interactionTemplates) {
                nearby.add(InteractionSuggestion(
                    obj = tmpl.objectName,
                    action = tmpl.actionTemplate.replace("{位置}", relPos),
                    relativePosition = relPos,
                    distancePx = dist.toInt()
                ))
            }
        }
        return nearby
    }

    // ── 内部: 回退 ──

    private fun fallbackCandidates(maxCount: Int): List<CandidatePosition> {
        // 优先下半部分的构图锚点
        val fallbackAnchors = anchors
            .filter { it.yRatio >= 0.5f }
            .ifEmpty { anchors }

        return fallbackAnchors.take(maxCount).mapIndexed { i, anchor ->
            val cx = (anchor.xRatio * w).toInt()
            val cy = (anchor.yRatio * h).toInt()
            val desc = "${anchor.style.label}，${anchor.positionName}，${anchor.style.characterGuide}"
            CandidatePosition(
                id = i + 1, centerX = cx, centerY = cy,
                xRatio = anchor.xRatio, yRatio = anchor.yRatio,
                regionDescription = desc,
                standableScore = (0.6f - anchor.priority * 0.1f).coerceIn(0.2f, 1f)
            )
        }
    }

    // ── 辅助 ──

    private fun Float.pow2() = this * this

    companion object {
        // COCO 类别: 障碍物
        val OBSTACLE_IDS = setOf(0, 56, 57, 2, 3, 4, 5, 6, 7, 8, 9, 43, 76)
    }
}
