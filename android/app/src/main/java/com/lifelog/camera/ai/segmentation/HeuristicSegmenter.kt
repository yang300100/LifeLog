package com.lifelog.camera.ai.segmentation

import android.graphics.Bitmap
import android.graphics.Color
import com.lifelog.camera.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 纯启发式分割器 — 不依赖深度学习的 fallback
 *
 * 策略:
 * 1. 用边缘检测 (Canny) 找轮廓
 * 2. 用颜色聚类找大面积均匀区域
 * 3. 粗略估计深度层 (Y坐标)
 *
 * 精度远低于 YOLO，但不需要模型文件，保证离线可用。
 */
class HeuristicSegmenter : Segmenter {

    override var isReady: Boolean = true
        private set

    override suspend fun load() { isReady = true }

    override fun release() {}

    override suspend fun segment(sceneBitmap: Bitmap): SegmentResult = withContext(Dispatchers.Default) {
        val w = sceneBitmap.width
        val h = sceneBitmap.height
        val pixels = IntArray(w * h)
        sceneBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val objects = mutableListOf<DetectedObject>()

        // ── 策略1: 检测肤色区域 → 可能是人 ──
        val skinRegions = findSkinRegions(pixels, w, h)
        skinRegions.forEachIndexed { i, region ->
            objects.add(DetectedObject(
                id = i,
                classId = 0,
                className = "person",
                bbox = region.bbox,
                center = region.center,
                area = region.area,
                mask = null,
                depthLayer = DepthLayer.MIDGROUND,
                confidence = 0.6f
            ))
        }

        // ── 策略2: 检测大面积暗色区域 → 可能是屏幕/键盘 ──
        val darkRegions = findDarkRegions(pixels, w, h)
        darkRegions.forEachIndexed { i, region ->
            val isKeyboard = region.aspectRatio > 2.0
            objects.add(DetectedObject(
                id = objects.size + i,
                classId = if (isKeyboard) 66 else 62, // keyboard or tv
                className = if (isKeyboard) "keyboard" else "tv",
                bbox = region.bbox,
                center = region.center,
                area = region.area,
                mask = null,
                depthLayer = DepthLayer.MIDGROUND,
                confidence = 0.4f,
                isInteractable = true,
                interactionTemplates = if (isKeyboard) listOf(
                    InteractionTemplate("键盘", "keyboard", "手指轻轻放在{位置}键盘上，歪头看向用户")
                ) else listOf(
                    InteractionTemplate("屏幕", "tv", "歪着头好奇地看着{位置}的屏幕")
                )
            ))
        }

        // ── 策略3: 检测高亮小区域 → 可能是杯子/瓶子 ──
        val brightRegions = findBrightRegions(pixels, w, h)
        brightRegions.forEachIndexed { i, region ->
            objects.add(DetectedObject(
                id = objects.size + i,
                classId = 41, // cup
                className = "cup",
                bbox = region.bbox,
                center = region.center,
                area = region.area,
                mask = null,
                depthLayer = DepthLayer.FOREGROUND,
                confidence = 0.3f,
                isInteractable = true,
                interactionTemplates = listOf(
                    InteractionTemplate("杯子", "cup", "探头好奇地看着{位置}的杯子")
                )
            ))
        }

        // 按面积排序
        objects.sortByDescending { it.area }

        SegmentResult(objects, w, h, 0)
    }

    // ── 启发式区域检测 ──

    private data class Region(
        val bbox: RectI, val center: PointI, val area: Float,
        val centerX: Int, val centerY: Int, val aspectRatio: Float
    )

    private fun findSkinRegions(pixels: IntArray, w: Int, h: Int): List<Region> {
        val mask = BooleanArray(pixels.size)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            // 简单肤色检测
            if (r > 95 && g > 40 && b > 20 &&
                r > g && r > b &&
                (r - g) > 15 && r < 230
            ) {
                mask[i] = true
            }
        }
        return extractConnectedRegions(mask, w, h, minArea = w * h * 0.005f)
    }

    private fun findDarkRegions(pixels: IntArray, w: Int, h: Int): List<Region> {
        val mask = BooleanArray(pixels.size)
        for (i in pixels.indices) {
            val gray = (Color.red(pixels[i]) + Color.green(pixels[i]) + Color.blue(pixels[i])) / 3
            if (gray < 60) mask[i] = true
        }
        return extractConnectedRegions(mask, w, h, minArea = w * h * 0.01f)
    }

    private fun findBrightRegions(pixels: IntArray, w: Int, h: Int): List<Region> {
        val mask = BooleanArray(pixels.size)
        for (i in pixels.indices) {
            val gray = (Color.red(pixels[i]) + Color.green(pixels[i]) + Color.blue(pixels[i])) / 3
            if (gray > 220) mask[i] = true
        }
        return extractConnectedRegions(mask, w, h, minArea = w * h * 0.002f)
    }

    private fun extractConnectedRegions(
        mask: BooleanArray, w: Int, h: Int, minArea: Float
    ): List<Region> {
        val visited = BooleanArray(mask.size)
        val regions = mutableListOf<Region>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx] || visited[idx]) continue

                // BFS 找连通域
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                visited[idx] = true
                var minX = x; var maxX = x
                var minY = y; var maxY = y
                var count = 0

                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    count++
                    val cx = cur % w; val cy = cur / w
                    minX = minOf(minX, cx); maxX = maxOf(maxX, cx)
                    minY = minOf(minY, cy); maxY = maxOf(maxY, cy)

                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = cx + dx; val ny = cy + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val nidx = ny * w + nx
                            if (mask[nidx] && !visited[nidx]) {
                                visited[nidx] = true
                                queue.add(nidx)
                            }
                        }
                    }
                }

                val area = (maxX - minX + 1) * (maxY - minY + 1).toFloat()
                if (area >= minArea) {
                    val aspect = (maxX - minX + 1).toFloat() / (maxY - minY + 1).toFloat()
                    regions.add(Region(
                        bbox = RectI(minX, minY, maxX, maxY),
                        center = PointI((minX + maxX) / 2, (minY + maxY) / 2),
                        area = area,
                        centerX = (minX + maxX) / 2,
                        centerY = (minY + maxY) / 2,
                        aspectRatio = aspect
                    ))
                }
            }
        }
        return regions.sortedByDescending { it.area }.take(10)
    }
}
