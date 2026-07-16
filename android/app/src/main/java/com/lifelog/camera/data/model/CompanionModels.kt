package com.lifelog.camera.data.model

/**
 * LifeLog Companion — 数据模型
 * 全部使用 JSON 文件而非 Room 数据库存储
 */

// ── 参考图分类 ──

/** 参考图类别，决定如何在 prompt 中描述这张图 */
enum class ReferenceCategory(val label: String, val promptHint: String) {
    CHARACTER("角色参考", "这张参考图展示角色的外貌与服装特征"),
    ART_STYLE("画风参考", "这张参考图展示期望的画风与渲染风格"),
    MULTI_ANGLE("多角度", "这张参考图展示角色另一个角度的外观"),
    POSE("姿态参考", "这张参考图展示期望的姿态与构图"),
    OTHER("综合参考", "这张参考图作为综合参考"),
}

// ── 角色档案 ──

data class CompanionProfile(
    val characterRefPath: String? = null,          // 参考图路径
    val characterDescription: String = "",           // 角色文字描述
    val customPrompts: Map<String, String> = emptyMap()  // 自定义提示词
)

// ── 场景分析结果 ──

data class SceneAnalysis(
    val positionKey: String = "center",
    val poseKey: String = "standing",
    val sceneType: String = "",
    val lighting: String = "",
    val rationale: String = "",
    val generationHint: String = "",
    val anchorPoint: String = "",
    val spatialAnalysis: String = "",
    val emptyAreas: String = "",
    // Kimi 返回的比例 (0.0~1.0)
    val characterXRatio: Float = 0f,
    val characterYRatio: Float = 0f,
    val characterWRatio: Float = 0.28f,
    val characterHRatio: Float = 0.50f,
    // 代码计算的像素值 (ratio × sceneWidth/Height)
    val characterXPx: Int = 0,
    val characterYPx: Int = 0,
    val characterWPx: Int = 0,
    val characterHPx: Int = 0
) {
    /** 根据场景分辨率计算像素坐标。XY始终基于 positionKey 计算，不依赖 Kimi 可能返回的 0 值 */
    /**
     * 智能定位：Kimi 语义指导 + 图像分析精确定位
     * 1. Kimi 的 positionKey 确定大方向（左/中/右）
     * 2. 从 empty_areas 描述中提取垂直锚点百分比（如"桌面高度约75%" → 0.75）
     * 3. 在大方向区域内做图像分析找最佳空位
     */
    fun computePixels(sceneWidth: Int, sceneHeight: Int, emptyRegion: EmptyRegion? = null): SceneAnalysis {
        // ── 水平：positionKey 限定搜索区间 ──
        val (searchXMin, searchXMax) = when (positionKey) {
            "left" -> 0f to 0.35f
            "right" -> 0.55f to 1f
            "nearby" -> 0.35f to 0.65f
            "corner" -> 0.50f to 0.85f
            else -> 0.25f to 0.75f  // center
        }

        // ── 垂直：从 empty_areas / anchor_point 中尝试提取百分比 ──
        val vy = parseVerticalRatio(emptyAreas + anchorPoint + spatialAnalysis)

        // ── 在限定区间内选最优点 ──
        val rx = if (emptyRegion != null && emptyRegion.xRatio in searchXMin..searchXMax) {
            emptyRegion.xRatio
        } else {
            (searchXMin + searchXMax) / 2f  // 区间中点
        }
        val ry = if (vy > 0) vy else if (emptyRegion != null) emptyRegion.yRatio else 0.18f

        // WH: 优先用 Kimi 返回值
        val rw = if (characterWRatio > 0.01f) characterWRatio else 0.28f
        val rh = if (characterHRatio > 0.01f) characterHRatio else 0.50f
        val w = (rw * sceneWidth).toInt().coerceAtLeast(64)
        val h = (rh * sceneHeight).toInt().coerceAtLeast(64)
        return copy(
            characterXRatio = rx, characterYRatio = ry,
            characterWRatio = rw, characterHRatio = rh,
            characterXPx = (rx * sceneWidth).toInt().coerceIn(0, sceneWidth - w),
            characterYPx = (ry * sceneHeight).toInt().coerceIn(0, sceneHeight - h),
            characterWPx = w,
            characterHPx = h
        )
    }

    companion object {
        /** 从文本中提取垂直位置百分比（如"75%"、"桌面高度约70%处"） */
        private fun parseVerticalRatio(text: String): Float {
            val regex = Regex("""(\d{1,3})\s*%""")
            val matches = regex.findAll(text).toList()
            if (matches.isEmpty()) return 0f
            // 取最大的百分比（通常是垂直位置描述）
            val pct = matches.maxOf { it.groupValues[1].toIntOrNull() ?: 0 }
            return (pct / 100f).coerceIn(0.1f, 1f)
        }
    }
}

// ── 放置参数 ──

data class Placement(
    val x: Float, val y: Float,
    val w: Float, val h: Float
)

// ── 空区域检测结果 ──

data class EmptyRegion(
    val xRatio: Float,
    val yRatio: Float,
    val avgR: Float, val avgG: Float, val avgB: Float
)

// ── 单次生成元数据 ──

data class CompanionMeta(
    val generationId: String,
    val clipId: Long,
    val sceneFrameIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val status: String = "pending",           // pending/analyzing/generating/processing/done/error
    val error: String? = null,
    val analysis: SceneAnalysis? = null,
    val characterDescription: String = "",
    val prompts: Map<String, String> = emptyMap(),
    val positionKey: String = "",
    val poseKey: String = ""
)

// ── 画廊列表项（轻量摘要） ──

data class CompanionGenerationSummary(
    val generationId: String,
    val clipId: Long,
    val createdAt: Long,
    val thumbnailPath: String,
    val status: String
)

// ── 流水线断点续传状态 ──

data class ProcessingState(
    val activeGenerationId: String? = null,
    val step: String = "idle",                 // idle/extracting/analyzing/generating/processing
    val startedAt: Long = 0,
    val clipId: Long = 0,
    val error: String? = null
)

// ── 默认提示词 ──

object CompanionDefaults {
    val SCENE_ANALYSIS_PROMPT = """
你是一个温馨的"虚拟陪伴角色定位助手"。分析这张第一视角照片，为虚拟同伴角色找到最合适的放置位置。

核心原则：
- 优先空白区域：找画面中没有物体遮挡的纯色大块空白区，角色不覆盖任何现有物体
- 不遮挡主体：不能挡住显示器、键盘、鼠标等人机交互区域
- 角色是"陪伴者"不是"参与者"——安静待在旁边，不参与用户的活动
- 画面平衡：角色位置应让整体构图更均衡

返回JSON（不要markdown代码块）。w/h 需根据空白区大小调整，不同场景应不同：

{
  "scene_type": "场景类型",
  "empty_areas": "描述画面的空白区域",
  "lighting": { "direction": "主光源方向", "temperature": "色温", "description": "光源描述" },
  "recommended_placement": "left/center/right/corner/nearby",
  "recommended_pose": "standing/sitting_nearby/leaning/crouching/waiting/perched",
  "character_w_ratio": 0.28,
  "character_h_ratio": 0.50,
  "rationale": "推荐理由"
}

w/h 填写指南：
- character_w_ratio: 角色占画面宽度比例。大空白区=0.25~0.35，小空白区=0.15~0.22
- character_h_ratio: 角色占画面高度比例。全身=0.45~0.60，半身=0.30~0.40

请在 empty_areas 中详细描述你推荐的位置区域（如"显示器右侧桌面边缘，约占画面60%~85%水平位置，桌面高度约75%处"），不需要精确坐标，但要有足够的空间描述帮助后续精确定位。
""".trimIndent()

    val CHARACTER_DESCRIBE_PROMPT = """
请详细描述这张图片中的人物/角色，提取以下视觉特征:

1. 发型与发色
2. 瞳色
3. 面部特征
4. 服装（上衣、下装、鞋）
5. 身高体型特征
6. 气质/风格
7. 特殊配件

请用一段流畅的中文描述，50-80字。直接输出描述文字，不要JSON格式。
""".trimIndent()


    // ═══ B方案新 Prompt (中文, 用于新管线) ═══

    /** Kimi 候选筛选 Prompt (中文) */
    val KIMI_SELECTION_PROMPT = """
你是一位摄影构图助手和虚拟陪伴角色的"行为导演"。

这张标注图是在原图上叠加了分割信息：
- 红色半透明 = 必须避开的人物/障碍物
- 蓝色方框 = 可互动的物体（杯子、书本、电脑等）
- 绿色半透明 = 地面/可站立区域
- 黄色圆点+编号 = 候选插入位置

以下结构化数据补充了每个候选的详细信息：
{candidate_json}

请完成以下任务：

1. 逐一审视每个候选位置，综合考虑：
   - 位置自然度：角色站在这里是否合理？
   - 互动机会：附近有可互动的物体吗？互动是否自然、有陪伴感？
   - 遮挡风险：角色是否会被前景物体遮挡？
   - 光照方向：从场景光线推测，该位置是顺光/侧光/逆光？
   - 构图平衡：角色放在这里后整体画面是否平衡？

2. 如果候选附近有可互动物体，为角色选择一个最自然、最有陪伴感的互动动作。

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
  "light_direction": "from_left",
  "confidence": 0.85,
  "warnings": []
}
    """.trimIndent()

}
