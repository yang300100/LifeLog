package com.lifelog.camera.ai.candidate

import android.graphics.*
import com.lifelog.camera.data.model.CandidatePosition
import com.lifelog.camera.data.model.DetectedObject
import kotlin.math.max
import kotlin.math.min

/**
 * 分割标注图生成器
 *
 * 在原图上叠加:
 *   - 红色半透明: 障碍物 (person, 车辆等)
 *   - 蓝色方框: 可互动物体
 *   - 绿色半透明: 地面区域
 *   - 黄色圆点+编号: 候选位置
 */
class AnnotatedImageBuilder(
    private val imageWidth: Int,
    private val imageHeight: Int
) {
    private val w = imageWidth
    private val h = imageHeight

    fun build(
        sceneBitmap: Bitmap,
        objects: List<DetectedObject>,
        candidates: List<CandidatePosition>,
        groundCells: List<Pair<Int, Int>> = emptyList()
    ): Bitmap {
        val result = sceneBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. 地面区域 (绿色半透明)
        if (groundCells.isNotEmpty()) {
            paint.color = Color.argb(50, 50, 200, 50)
            paint.style = Paint.Style.FILL
            for ((gx, gy) in groundCells) {
                val cellW = w / 8; val cellH = h / 6
                canvas.drawRect(
                    (gx * cellW).toFloat(), (gy * cellH).toFloat(),
                    ((gx + 1) * cellW).toFloat(), ((gy + 1) * cellH).toFloat(),
                    paint
                )
            }
        }

        // 2. 障碍物蒙版 (红色半透明)
        paint.color = Color.argb(50, 220, 50, 50)
        paint.style = Paint.Style.FILL
        val obstaclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 220, 50, 50)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        for (obj in objects) {
            if (obj.classId in CandidateGenerator.OBSTACLE_IDS || obj.className == "person") {
                canvas.drawRect(obj.bbox.x1.toFloat(), obj.bbox.y1.toFloat(),
                    obj.bbox.x2.toFloat(), obj.bbox.y2.toFloat(), paint)
                canvas.drawRect(obj.bbox.x1.toFloat(), obj.bbox.y1.toFloat(),
                    obj.bbox.x2.toFloat(), obj.bbox.y2.toFloat(), obstaclePaint)
            }
        }

        // 3. 所有 YOLO 检测物体 — 边框 + 类名 + 置信度
        val detectLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = maxOf(24f, minOf(w, h).toFloat() / 40f)
        }
        for (obj in objects) {
            val isInteract = obj.isInteractable
            val isObstacle = obj.classId in CandidateGenerator.OBSTACLE_IDS || obj.className == "person"

            // 边框颜色: 可交互=蓝, 障碍=红, 其他=灰
            val boxColor = when {
                isInteract -> Color.argb(220, 50, 140, 255)
                isObstacle -> Color.argb(180, 220, 50, 50)
                else -> Color.argb(160, 160, 160, 160)
            }
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = boxColor
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            canvas.drawRect(obj.bbox.x1.toFloat(), obj.bbox.y1.toFloat(),
                obj.bbox.x2.toFloat(), obj.bbox.y2.toFloat(), boxPaint)

            // 标签: 类名 + 置信度
            detectLabel.color = boxColor
            val label = "${obj.className} ${(obj.confidence * 100).toInt()}%"
            canvas.drawText(label, obj.bbox.x1.toFloat(),
                maxOf(detectLabel.textSize + 4, (obj.bbox.y1 - 6).toFloat()), detectLabel)
        }

        // 4. 候选位置 (黄色圆点 + 编号)
        val markerRadius = maxOf(12f, minOf(w, h).toFloat() / 60f)

        for (c in candidates) {
            val cx = c.centerX.toFloat()
            val cy = c.centerY.toFloat()

            // 外圈
            val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 220, 50)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawCircle(cx, cy, markerRadius, outerPaint)

            // 内点
            val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(255, 255, 220, 50)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, 4f, innerPaint)

            // 编号背景
            val numText = c.id.toString()
            val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, 255, 220, 50)
                textSize = maxOf(32f, markerRadius * 2)
                textAlign = Paint.Align.CENTER
            }
            val textH = numPaint.descent() - numPaint.ascent()
            val textW = numPaint.measureText(numText)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 220, 50)
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                cx - textW / 2 - 4, cy - markerRadius - textH - 6,
                cx + textW / 2 + 4, cy - markerRadius - 2,
                bgPaint
            )

            // 编号文字
            numPaint.color = Color.BLACK
            canvas.drawText(numText, cx, cy - markerRadius - 6f, numPaint)
        }

        return result
    }
}
