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
            if (isReady) return  // 双重检查
            try {
                val paramFile = copyAssetToCache(modelParam)
                val binFile = copyAssetToCache(modelBin)

                System.loadLibrary("yolov8_ncnn_jni")  // NCNN 静态链接在此 .so 中

                val ptr = nativeLoadModel(paramFile.absolutePath, binFile.absolutePath)
                if (ptr == 0L) throw Exception("NCNN 模型加载失败")

                netPtr = ptr
                isReady = true
                CrashLogger.i(TAG, "NCNN YOLOv8n 加载成功")
            } catch (e: Exception) {
                CrashLogger.e(TAG, "NCNN 加载失败: ${e.message}")
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
        if (!isReady) {
            CrashLogger.w(TAG, "NCNN 未就绪, 回退空结果")
            return@withContext SegmentResult(emptyList(), sceneBitmap.width, sceneBitmap.height)
        }

        val w = sceneBitmap.width
        val h = sceneBitmap.height
        val startMs = System.currentTimeMillis()

        // 预处理: resize → 640×640 → RGB → float [0,1]
        val inputPixels = preprocess(sceneBitmap)

        // 推理
        val detectStart = System.currentTimeMillis()
        val detections = nativeDetect(netPtr, inputPixels, INPUT_SIZE, INPUT_SIZE,
            confThreshold, iouThreshold) ?: emptyArray()
        val detectMs = System.currentTimeMillis() - detectStart

        // 诊断: 输出原始检测结果
        if (detections.isEmpty()) {
            CrashLogger.w(TAG, "YOLO 推理完成但 0 个检测 (${detectMs}ms) — 请检查 logcat 中 NcnnYOLO_JNI 的 blob 维度日志")
        } else {
            CrashLogger.i(TAG, "YOLO 原始检测: ${detections.size} 个 (${detectMs}ms)")
            detections.take(5).forEach { row ->
                CrashLogger.i(TAG, "  [${row[0]},${row[1]},${row[2]},${row[3]}] cls=${row[5].toInt()} conf=%.2f".format(row[4]))
            }
        }

        // 后处理: 解析 float[] → 映射到原图坐标
        val objects = detections.mapIndexed { i, row ->
            val x1 = (row[0] * w).toInt().coerceIn(0, w)
            val y1 = (row[1] * h).toInt().coerceIn(0, h)
            val x2 = (row[2] * w).toInt().coerceIn(0, w)
            val y2 = (row[3] * h).toInt().coerceIn(0, h)
            val conf = row[4]
            val clsId = row[5].toInt()
            val cx = (x1 + x2) / 2
            val cy = (y1 + y2) / 2
            val area = (x2 - x1).toFloat() * (y2 - y1).toFloat()
            val className = if (clsId in COCO_NAMES.indices) COCO_NAMES[clsId] else "unknown"
            val depth = when {
                cy > h * 0.7 -> DepthLayer.FOREGROUND
                cy < h * 0.3 -> DepthLayer.BACKGROUND
                else -> DepthLayer.MIDGROUND
            }
            val interTemplates = INTERACTABLE_MAP[className]?.let { listOf(it) } ?: emptyList()

            DetectedObject(
                id = i,
                classId = clsId,
                className = className,
                bbox = RectI(x1, y1, x2, y2),
                center = PointI(cx, cy),
                area = area,
                mask = null,
                depthLayer = depth,
                confidence = conf,
                isInteractable = className in INTERACTABLE_MAP,
                interactionTemplates = interTemplates
            )
        }.sortedByDescending { it.area }

        val elapsed = System.currentTimeMillis() - startMs
        CrashLogger.i(TAG, "YOLO 检测: ${objects.size} 个物体, ${elapsed}ms")
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
                tmpFile.renameTo(cacheFile)
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
    private external fun nativeDetect(
        netPtr: Long, input: FloatArray, width: Int, height: Int,
        confThresh: Float, iouThresh: Float
    ): Array<FloatArray>  // 每行 [x1, y1, x2, y2, confidence, classId]

    data class Detection(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val confidence: Float, val classId: Int
    )
}
