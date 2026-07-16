package com.lifelog.camera.ai.segmentation

import android.graphics.Bitmap
import com.lifelog.camera.data.model.DetectedObject

/**
 * 图像分割器接口
 *
 * 当前提供两种实现:
 * - NcnnYOLOSegmenter: 本地 NCNN YOLOv8n-seg (需要模型文件部署到 assets)
 * - HeuristicSegmenter: 纯启发式分割 (不依赖深度学习, 作为 fallback)
 *
 * 模型转换链 (开发者参考):
 *   yolov8n-seg.pt → ONNX → ONNX-Simplifier → NCNN (param + bin)
 *   目标放置: app/src/main/assets/yolov8n_seg.param + yolov8n_seg.bin
 */
interface Segmenter {

    /**
     * 对场景图执行实例分割
     *
     * @param sceneBitmap 场景原图 (RGB)
     * @return 检测到的物体列表 (按面积降序)
     */
    suspend fun segment(sceneBitmap: Bitmap): SegmentResult

    /**
     * 是否已就绪 (模型已加载)
     */
    val isReady: Boolean

    /**
     * 加载模型 (可能在后台线程)
     */
    suspend fun load()

    /**
     * 释放模型资源
     */
    fun release()
}

data class SegmentResult(
    val objects: List<DetectedObject>,
    val imageWidth: Int,
    val imageHeight: Int,
    val inferenceMs: Long = 0
)
