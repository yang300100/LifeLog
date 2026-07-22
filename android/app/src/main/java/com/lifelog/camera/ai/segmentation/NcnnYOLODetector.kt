package com.lifelog.camera.ai.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lifelog.camera.data.model.*
import com.lifelog.camera.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * NCNN YOLOv8n 检测器 — 实现 Segmenter 接口
 *
 * 部署步骤:
 *   1. 导出模型: python prototype/export_yolo_ncnn.py
 *   2. ONNX→NCNN: onnxsim + onnx2ncnn → yolov8n.param + yolov8n.bin
 *   3. 放入 app/src/main/assets/
 *   4. 下载 NCNN Android SDK, 放置 .so 到 jniLibs/
 *
 * NCNN Android SDK: https://github.com/Tencent/ncnn/releases
 *   下载 ncnn-YYYYMMDD-android-vulkan.zip
 *   解压到 app/src/main/jni/ncnn/
 *
 * 输入: 640×640 RGB, 归一化 [0,1]
 * 输出: [1, 84, 8400] → 解析 → NMS → List<DetectedObject>
 */
class NcnnYOLODetector(
    private val context: Context,
    private val modelParam: String = "yolov8n.param",
    private val modelBin: String = "yolov8n.bin",
    private val confThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.45f
) : Segmenter {

    companion object {
        private const val TAG = "NcnnYOLO"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80

        // COCO 类别名
        val COCO_NAMES = arrayOf(
            "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
            "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
            "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
            "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
            "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
            "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
            "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake",
            "chair","couch","potted plant","bed","dining table","toilet","tv","laptop",
            "mouse","remote","keyboard","cell phone","microwave","oven","toaster","sink",
            "refrigerator","book","clock","vase","scissors","teddy bear","hair drier","toothbrush"
        )

        // 可交互类别 → 中文名 + 动作模板
        val INTERACTABLE_MAP: Map<String, InteractionTemplate> = mapOf(
            "cup" to InteractionTemplate("杯子", "cup", "探头好奇地看着{位置}的杯子"),
            "bottle" to InteractionTemplate("水瓶", "bottle", "好奇地看向{位置}的水瓶"),
            "book" to InteractionTemplate("书本", "book", "凑过去看{位置}摊开的书"),
            "tv" to InteractionTemplate("屏幕", "tv", "歪着头好奇地看着{位置}的屏幕"),
            "laptop" to InteractionTemplate("笔记本", "laptop", "歪头看着{位置}的笔记本电脑"),
            "keyboard" to InteractionTemplate("键盘", "keyboard", "手指轻轻放在{位置}键盘上，歪头看向用户"),
            "cell phone" to InteractionTemplate("手机", "cell phone", "歪头看向{位置}的手机屏幕"),
            "dining table" to InteractionTemplate("桌子", "dining table", "双手撑在{位置}桌边，身体微微前倾"),
            "chair" to InteractionTemplate("椅子", "chair", "放松地靠在{位置}椅子旁"),
            "couch" to InteractionTemplate("沙发", "couch", "放松地靠在{位置}沙发旁"),
            "bench" to InteractionTemplate("长椅", "bench", "放松地站在{位置}长椅旁"),
            "potted plant" to InteractionTemplate("盆栽", "potted plant", "蹲在{位置}盆栽旁，伸手轻触叶子"),
            "teddy bear" to InteractionTemplate("泰迪熊", "teddy bear", "开心地看向{位置}的泰迪熊"),
            "vase" to InteractionTemplate("花瓶", "vase", "欣赏{位置}的花瓶"),
        )
    }

    @Volatile
    override var isReady: Boolean = false
        private set

    @Volatile
    private var netPtr: Long = 0  // NCNN Net 指针

    override suspend fun load() {
        if (isReady) return
        synchronized(this) {
            if (isReady) return
            CrashLogger.i(TAG, "========== YOLO 模型加载开始 ==========")
            try {
                CrashLogger.i(TAG, "复制模型文件到缓存...")
                val paramFile = copyAssetToCache(modelParam)
                val binFile = copyAssetToCache(modelBin)
                CrashLogger.i(TAG, "param: ${paramFile.absolutePath} (${paramFile.length()} bytes)")
                CrashLogger.i(TAG, "bin:   ${binFile.absolutePath} (${binFile.length()} bytes)")

                CrashLogger.i(TAG, "加载 JNI 库 yolov8_ncnn_jni...")
                System.loadLibrary("yolov8_ncnn_jni")
                CrashLogger.i(TAG, "JNI 库加载成功")

                CrashLogger.i(TAG, "调用 nativeLoadModel...")
                val loadStart = System.currentTimeMillis()
                val ptr = nativeLoadModel(paramFile.absolutePath, binFile.absolutePath)
                val loadMs = System.currentTimeMillis() - loadStart
                if (ptr == 0L) throw Exception("NCNN 模型加载失败 (nativeLoadModel 返回 0)")
                CrashLogger.i(TAG, "nativeLoadModel 成功, ptr=0x%x, 耗时 ${loadMs}ms".format(ptr))

                netPtr = ptr
                isReady = true
                CrashLogger.i(TAG, "========== YOLO 模型就绪 ==========")
                // 输出模型 blob 维度信息（供诊断兼容性）
                try {
                    val blobInfo = nativeGetBlobInfo(ptr)
                    CrashLogger.i(TAG, "模型 Blob: $blobInfo")
                } catch (e: Exception) {
                    CrashLogger.w(TAG, "无法获取 blob 信息: ${e.message}")
                }
            } catch (e: Exception) {
                CrashLogger.e(TAG, "YOLO 加载失败: ${e.message}", e)
                isReady = false
            }
        }
    }

    override fun release() {
        if (netPtr != 0L) {
            nativeReleaseModel(netPtr)
            netPtr = 0
        }
        isReady = false
    }

    override suspend fun segment(sceneBitmap: Bitmap): SegmentResult = withContext(Dispatchers.Default) {
        val w = sceneBitmap.width; val h = sceneBitmap.height
        CrashLogger.i(TAG, "========== YOLO 推理开始: ${w}x${h}, conf=${confThreshold}, iou=${iouThreshold} ==========")

        if (!isReady) {
            CrashLogger.w(TAG, "NCNN 未就绪 (isReady=false, netPtr=$netPtr)")
            return@withContext SegmentResult(emptyList(), w, h)
        }
        if (netPtr == 0L) {
            CrashLogger.w(TAG, "NCNN netPtr=0, 模型已释放或加载失败")
            return@withContext SegmentResult(emptyList(), w, h)
        }

        val startMs = System.currentTimeMillis()

        // 预处理
        val preStart = System.currentTimeMillis()
        val inputPixels = preprocess(sceneBitmap)
        val preMs = System.currentTimeMillis() - preStart
        CrashLogger.i(TAG, "预处理: ${INPUT_SIZE}x${INPUT_SIZE} RGB→float[0,1], ${preMs}ms, 首像素 R=${"%.3f".format(inputPixels[0])} G=${"%.3f".format(inputPixels[INPUT_SIZE*INPUT_SIZE])} B=${"%.3f".format(inputPixels[2*INPUT_SIZE*INPUT_SIZE])}")

        // 推理
        CrashLogger.i(TAG, "调用 nativeDetect(ptr=0x%x, ${INPUT_SIZE}x${INPUT_SIZE})...".format(netPtr))
        val detectStart = System.currentTimeMillis()
        val detections = nativeDetect(netPtr, inputPixels, INPUT_SIZE, INPUT_SIZE,
            confThreshold, iouThreshold)
        val detectMs = System.currentTimeMillis() - detectStart

        // 诊断: 0检测时尝试极低阈值，判断是模型无输出还是阈值问题
        if (detections.isEmpty()) {
            CrashLogger.w(TAG, "conf=%.2f 时 0 检测，尝试 conf=0.05 诊断...".format(confThreshold))
            val diagDetections = nativeDetect(netPtr, inputPixels, INPUT_SIZE, INPUT_SIZE,
                0.05f, iouThreshold)
            if (diagDetections.isEmpty()) {
                CrashLogger.e(TAG, "conf=0.05 仍 0 检测! 模型可能不兼容或输入格式错误 — 请检查 logcat NcnnYOLO_JNI 的 blob 维度日志")
            } else {
                CrashLogger.w(TAG, "conf=0.05 → ${diagDetections.size} 个检测! 当前 conf=%.2f 阈值太高".format(confThreshold))
                diagDetections.take(8).forEach { row ->
                    val clsId = row[5].toInt()
                    val name = if (clsId in COCO_NAMES.indices) COCO_NAMES[clsId] else "cls#$clsId"
                    CrashLogger.w(TAG, "  低分检测: $name conf=%.4f".format(row[4]))
                }
            }
        } else {
            CrashLogger.i(TAG, "原始检测: ${detections.size} 个, ${detectMs}ms")
            detections.forEachIndexed { i, row ->
                if (row.size >= 6) {
                    val clsId = row[5].toInt()
                    val name = if (clsId in COCO_NAMES.indices) COCO_NAMES[clsId] else "cls#$clsId"
                    CrashLogger.i(TAG, "  #$i: [${"%.2f".format(row[0])},${"%.2f".format(row[1])},${"%.2f".format(row[2])},${"%.2f".format(row[3])}] $name conf=${"%.3f".format(row[4])}")
                } else {
                    CrashLogger.w(TAG, "  #$i: 行长度=${row.size} (<6, 异常!)")
                }
            }
        }

        // 后处理: 映射到原图坐标
        val objects = detections.mapIndexed { i, row ->
            val x1 = (row[0] * w).toInt().coerceIn(0, w)
            val y1 = (row[1] * h).toInt().coerceIn(0, h)
            val x2 = (row[2] * w).toInt().coerceIn(0, w)
            val y2 = (row[3] * h).toInt().coerceIn(0, h)
            val conf = row[4]
            val clsId = row[5].toInt()
            val className = if (clsId in COCO_NAMES.indices) COCO_NAMES[clsId] else "unknown"
            val isInteractable = className in INTERACTABLE_MAP

            DetectedObject(
                id = i,
                classId = clsId,
                className = className,
                bbox = RectI(x1, y1, x2, y2),
                center = PointI((x1 + x2) / 2, (y1 + y2) / 2),
                area = (x2 - x1).toFloat() * (y2 - y1).toFloat(),
                mask = null,
                depthLayer = when {
                    (y1 + y2) / 2 > h * 0.7 -> DepthLayer.FOREGROUND
                    (y1 + y2) / 2 < h * 0.3 -> DepthLayer.BACKGROUND
                    else -> DepthLayer.MIDGROUND
                },
                confidence = conf,
                isInteractable = isInteractable,
                interactionTemplates = if (isInteractable) INTERACTABLE_MAP[className]?.let { listOf(it) } ?: emptyList() else emptyList()
            )
        }.sortedByDescending { it.area }

        val elapsed = System.currentTimeMillis() - startMs
        val interactableCount = objects.count { it.isInteractable }
        CrashLogger.i(TAG, "========== YOLO 完成: ${objects.size} 物体 (${interactableCount} 可交互), ${elapsed}ms ==========")
        SegmentResult(objects, w, h, elapsed)
    }

    // ── 预处理 ──

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        scaled.recycle()

        val floats = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        for (i in pixels.indices) {
            val p = pixels[i]
            floats[i] = (p shr 16 and 0xFF) / 255f                       // R
            floats[i + INPUT_SIZE * INPUT_SIZE] = (p shr 8 and 0xFF) / 255f  // G
            floats[i + 2 * INPUT_SIZE * INPUT_SIZE] = (p and 0xFF) / 255f    // B
        }
        return floats
    }

    // ── 工具 ──

    private fun copyAssetToCache(filename: String): File {
        val cacheFile = File(context.cacheDir, filename)
        if (!cacheFile.exists()) {
            // 原子写入: 先写临时文件, 成功后再 rename (避免写入中断导致文件损坏)
            val tmpFile = File(context.cacheDir, "$filename.tmp")
            tmpFile.delete()  // 清理上次可能残留的临时文件
            try {
                context.assets.open(filename).use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (!tmpFile.renameTo(cacheFile)) {
                    // renameTo 可能跨文件系统失败，回退到复制+删除
                    cacheFile.outputStream().use { out ->
                        tmpFile.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    tmpFile.delete()
                }
            } catch (e: Exception) {
                tmpFile.delete()
                throw e
            }
        }
        return cacheFile
    }

    // ═══ JNI Native 方法 ═══

    private external fun nativeLoadModel(paramPath: String, binPath: String): Long
    private external fun nativeReleaseModel(netPtr: Long)
    private external fun nativeGetBlobInfo(netPtr: Long): String
    private external fun nativeDetect(
        netPtr: Long, input: FloatArray, width: Int, height: Int,
        confThresh: Float, iouThresh: Float
    ): Array<FloatArray>  // 每行 [x1, y1, x2, y2, confidence, classId]

    data class Detection(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val confidence: Float, val classId: Int
    )
}
