/**
 * NCNN YOLOv8n JNI 桥接 — C++ 推理引擎
 *
 * 模型: YOLOv8n (detect), 输入 [1,3,640,640], 输出 [1,84,8400]
 * 编译: 需 NCNN Android SDK 放入 jni/ncnn/${ANDROID_ABI}/
 */

#include <jni.h>
#include <vector>
#include <algorithm>
#include <android/log.h>
#include <android/bitmap.h>

#define TAG "NcnnYOLO_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include <ncnn/net.h>
#include <ncnn/mat.h>

static ncnn::UnlockedPoolAllocator g_blob_pool_allocator;
static ncnn::PoolAllocator g_workspace_pool_allocator;

struct Detection {
    float x1, y1, x2, y2;
    float confidence;
    int classId;
};

// ═══════════════════════════════════════════════════
// 模型加载
// ═══════════════════════════════════════════════════

extern "C"
JNIEXPORT jlong JNICALL
Java_com_lifelog_camera_ai_segmentation_NcnnYOLODetector_nativeLoadModel(
    JNIEnv* env, jobject /* this */,
    jstring paramPath, jstring binPath) {

    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin = env->GetStringUTFChars(binPath, nullptr);

    LOGI("加载 NCNN: %s", param);

    ncnn::Net* net = new ncnn::Net();
    net->opt.use_vulkan_compute = true;   // GPU 加速
    net->opt.use_fp16_packed = true;
    net->opt.use_fp16_storage = true;
    net->opt.num_threads = 4;
    net->opt.blob_allocator = &g_blob_pool_allocator;
    net->opt.workspace_allocator = &g_workspace_pool_allocator;

    if (net->load_param(param) != 0) {
        LOGE("param 加载失败");
        delete net;
        env->ReleaseStringUTFChars(paramPath, param);
        env->ReleaseStringUTFChars(binPath, bin);
        return 0;
    }
    if (net->load_model(bin) != 0) {
        LOGE("bin 加载失败");
        delete net;
        env->ReleaseStringUTFChars(paramPath, param);
        env->ReleaseStringUTFChars(binPath, bin);
        return 0;
    }
    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath, bin);

    LOGI("NCNN 模型加载成功");
    return reinterpret_cast<jlong>(net);
}

// ═══════════════════════════════════════════════════
// 释放
// ═══════════════════════════════════════════════════

extern "C"
JNIEXPORT void JNICALL
Java_com_lifelog_camera_ai_segmentation_NcnnYOLODetector_nativeReleaseModel(
    JNIEnv*, jobject, jlong netPtr) {
    delete reinterpret_cast<ncnn::Net*>(netPtr);
    LOGI("模型已释放");
}

// ═══════════════════════════════════════════════════
// NMS + 后处理
// ═══════════════════════════════════════════════════

static float iou(const Detection& a, const Detection& b) {
    float ix1 = std::max(a.x1, b.x1);
    float iy1 = std::max(a.y1, b.y1);
    float ix2 = std::min(a.x2, b.x2);
    float iy2 = std::min(a.y2, b.y2);
    float inter = std::max(0.0f, ix2 - ix1) * std::max(0.0f, iy2 - iy1);
    float area_a = (a.x2 - a.x1) * (a.y2 - a.y1);
    float area_b = (b.x2 - b.x1) * (b.y2 - b.y1);
    return inter / (area_a + area_b - inter + 1e-6f);
}

static void nms(std::vector<Detection>& dets, float iou_thresh) {
    std::sort(dets.begin(), dets.end(),
              [](const Detection& a, const Detection& b) { return a.confidence > b.confidence; });

    for (size_t i = 0; i < dets.size(); i++) {
        if (dets[i].confidence < 0) continue;
        for (size_t j = i + 1; j < dets.size(); j++) {
            if (dets[j].confidence < 0) continue;
            if (dets[i].classId != dets[j].classId) continue;  // 同类 NMS
            if (iou(dets[i], dets[j]) > iou_thresh) {
                dets[j].confidence = -1;  // 标记抑制
            }
        }
    }
    dets.erase(std::remove_if(dets.begin(), dets.end(),
        [](const Detection& d) { return d.confidence < 0; }), dets.end());
}

// ═══════════════════════════════════════════════════
// 推理
// ═══════════════════════════════════════════════════

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_lifelog_camera_ai_segmentation_NcnnYOLODetector_nativeDetect(
    JNIEnv* env, jobject /* this */,
    jlong netPtr, jfloatArray input, jint width, jint height,
    jfloat confThresh, jfloat iouThresh) {

    auto* net = reinterpret_cast<ncnn::Net*>(netPtr);
    if (!net) {
        LOGE("net 为空");
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    jfloat* data = env->GetFloatArrayElements(input, nullptr);

    // 构建 NCNN Mat: CHW 格式 (3, 640, 640)
    // YOLO 输入: RGB, 归一化到 [0,1], NCHW
    int in_w = width;
    int in_h = height;
    ncnn::Mat in(in_w, in_h, 3);
    {
        float* r_ch = in.channel(0);  // R
        float* g_ch = in.channel(1);  // G
        float* b_ch = in.channel(2);  // B
        int plane_size = in_w * in_h;
        memcpy(r_ch, data, plane_size * sizeof(float));
        memcpy(g_ch, data + plane_size, plane_size * sizeof(float));
        memcpy(b_ch, data + 2 * plane_size, plane_size * sizeof(float));
    }
    env->ReleaseFloatArrayElements(input, data, JNI_ABORT);

    // 推理
    ncnn::Extractor ex = net->create_extractor();
    ex.input("images", in);

    ncnn::Mat out;
    int ret = ex.extract("output0", out);

    if (ret != 0) {
        // 尝试其他输出名 (不同版本的 YOLOv8 可能不同)
        ret = ex.extract("output", out);
    }
    if (ret != 0) {
        LOGE("推理失败: extract=%d", ret);
        return nullptr;
    }

    LOGI("推理完成: out shape = %d x %d x %d", out.w, out.h, out.c);

    // 解析输出 [num_classes+4, num_proposals]
    // YOLOv8n: [84, 8400] (4 xywh + 80 class scores)
    // YOLOv8n-seg 导出的 detect: [116, 8400] (4 xywh + 80 class + 32 mask coeffs)
    const int num_proposals = out.h;      // 8400
    const int out_channels = out.w;       // 84 或 116
    const int num_classes = out_channels - 4;  // 80 或 112
    const int real_classes = std::min(num_classes, 80);  // 只取前80类

    std::vector<Detection> proposals;
    for (int i = 0; i < num_proposals; i++) {
        const float* ptr = out.row(i);

        // 找最大类别分数
        float max_score = 0;
        int max_class = 0;
        for (int c = 0; c < real_classes; c++) {
            float score = ptr[4 + c];
            if (score > max_score) {
                max_score = score;
                max_class = c;
            }
        }
        if (max_score < confThresh) continue;

        float cx = ptr[0];
        float cy = ptr[1];
        float w = ptr[2];
        float h = ptr[3];

        proposals.push_back({
            cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2,
            max_score, max_class
        });
    }

    // NMS
    nms(proposals, iouThresh);

    LOGI("YOLO: %zu proposals → %zu after NMS", proposals.size(), proposals.size());

    // 归一化坐标 (相对于 640×640)
    for (auto& d : proposals) {
        d.x1 /= in_w; d.y1 /= in_h;
        d.x2 /= in_w; d.y2 /= in_h;
    }

    // 构建 Java 返回
    jclass detClass = env->FindClass(
        "com/lifelog/camera/ai/segmentation/NcnnYOLODetector$Detection");
    jmethodID ctor = env->GetMethodID(detClass, "<init>", "(FFFFFI)V");

    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(proposals.size()), detClass, nullptr);

    for (size_t i = 0; i < proposals.size(); i++) {
        const auto& d = proposals[i];
        jobject obj = env->NewObject(detClass, ctor,
            d.x1, d.y1, d.x2, d.y2, d.confidence, d.classId);
        env->SetObjectArrayElement(result, i, obj);
        env->DeleteLocalRef(obj);
    }
    return result;
}
