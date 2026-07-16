/**
 * NCNN YOLOv8n JNI 桥接 — C++ 推理引擎
 * 返回 float[][] 避免 Kotlin 内部类构造器签名问题
 */

#include <jni.h>
#include <vector>
#include <algorithm>
#include <android/log.h>

#define TAG "NcnnYOLO_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include <ncnn/net.h>
#include <ncnn/mat.h>

static ncnn::PoolAllocator g_blob_pool_allocator;
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
    JNIEnv* env, jobject, jstring paramPath, jstring binPath) {

    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin = env->GetStringUTFChars(binPath, nullptr);
    LOGI("加载 NCNN: %s", param);

    ncnn::Net* net = new ncnn::Net();
    net->opt.use_vulkan_compute = false;
    net->opt.use_fp16_packed = true;
    net->opt.use_fp16_storage = true;
    net->opt.num_threads = 4;
    net->opt.blob_allocator = &g_blob_pool_allocator;
    net->opt.workspace_allocator = &g_workspace_pool_allocator;

    int ret = net->load_param(param);
    if (ret != 0) { LOGE("param 加载失败"); delete net; env->ReleaseStringUTFChars(paramPath, param); env->ReleaseStringUTFChars(binPath, bin); return 0; }
    ret = net->load_model(bin);
    if (ret != 0) { LOGE("bin 加载失败"); delete net; env->ReleaseStringUTFChars(paramPath, param); env->ReleaseStringUTFChars(binPath, bin); return 0; }

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
// NMS
// ═══════════════════════════════════════════════════

static float iou(const Detection& a, const Detection& b) {
    float ix1 = std::max(a.x1, b.x1), iy1 = std::max(a.y1, b.y1);
    float ix2 = std::min(a.x2, b.x2), iy2 = std::min(a.y2, b.y2);
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
            if (dets[i].classId != dets[j].classId) continue;
            if (iou(dets[i], dets[j]) > iou_thresh) dets[j].confidence = -1;
        }
    }
    dets.erase(std::remove_if(dets.begin(), dets.end(),
        [](const Detection& d) { return d.confidence < 0; }), dets.end());
}

// ═══════════════════════════════════════════════════
// 推理 — 返回 float[][] (每行 [x1,y1,x2,y2,conf,classId])
// ═══════════════════════════════════════════════════

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_lifelog_camera_ai_segmentation_NcnnYOLODetector_nativeDetect(
    JNIEnv* env, jobject, jlong netPtr, jfloatArray input,
    jint width, jint height, jfloat confThresh, jfloat iouThresh) {

    auto* net = reinterpret_cast<ncnn::Net*>(netPtr);
    if (!net) { LOGE("net 为空"); return nullptr; }

    jsize len = env->GetArrayLength(input);
    jfloat* data = env->GetFloatArrayElements(input, nullptr);

    int in_w = width, in_h = height;
    ncnn::Mat in(in_w, in_h, 3);
    int plane_size = in_w * in_h;
    memcpy(in.channel(0), data, plane_size * sizeof(float));
    memcpy(in.channel(1), data + plane_size, plane_size * sizeof(float));
    memcpy(in.channel(2), data + 2 * plane_size, plane_size * sizeof(float));
    env->ReleaseFloatArrayElements(input, data, JNI_ABORT);

    ncnn::Extractor ex = net->create_extractor();
    // 从模型获取实际的 blob 名称 (不同导出方式名称不同)
    const auto& input_names = net->input_names();
    const auto& output_names = net->output_names();
    const char* in_name = input_names.empty() ? "images" : input_names[0];
    const char* out_name = output_names.empty() ? "output0" : output_names[0];
    LOGI("使用 blob: in=%s out=%s", in_name, out_name);

    ex.input(in_name, in);

    ncnn::Mat out;
    int ret = ex.extract(out_name, out);
    if (ret != 0) { LOGE("推理失败: extract(%s)=%d", out_name, ret); return nullptr; }

    LOGI("推理完成: w=%d h=%d c=%d dims=%d", out.w, out.h, out.c, out.dims);

    // NCNN 3D Mat 布局: [1, C, N] → c=C(通道), w=N(proposals), h=1
    // out.channel(c) 返回 1×N 的 2D Mat, [i] 取第 i 个 proposal
    const int num_proposals = out.w;     // 8400
    const int num_channels = out.c;      // 116 (或 84)
    const int real_classes = std::min(num_channels - 4, 80);
    LOGI("解析: proposals=%d channels=%d classes=%d",
         num_proposals, num_channels, real_classes);

    // 预读取所有通道数据 (避免 repeated channel() 调用)
    std::vector<const float*> channels(num_channels);
    for (int c = 0; c < num_channels; c++) {
        channels[c] = out.channel(c);
    }

    std::vector<Detection> proposals;
    for (int i = 0; i < num_proposals; i++) {
        float max_score = 0; int max_class = 0;
        for (int c = 0; c < real_classes; c++) {
            float score = channels[4 + c][i];
            if (score > max_score) { max_score = score; max_class = c; }
        }
        if (max_score < confThresh) continue;

        float cx = channels[0][i], cy = channels[1][i];
        float bw = channels[2][i], bh = channels[3][i];
        proposals.push_back({cx-bw/2, cy-bh/2, cx+bw/2, cy+bh/2, max_score, max_class});
    }

    nms(proposals, iouThresh);
    LOGI("YOLO: %zu detections after NMS", proposals.size());

    for (auto& d : proposals) {
        d.x1 /= in_w; d.y1 /= in_h;
        d.x2 /= in_w; d.y2 /= in_h;
    }

    // 返回 float[][] — 避开 Kotlin 内部类构造器签名问题
    jclass floatArrCls = env->FindClass("[F");
    jobjectArray result = env->NewObjectArray(proposals.size(), floatArrCls, nullptr);
    for (size_t i = 0; i < proposals.size(); i++) {
        const auto& d = proposals[i];
        jfloatArray row = env->NewFloatArray(6);
        jfloat vals[6] = {d.x1, d.y1, d.x2, d.y2, d.confidence, static_cast<jfloat>(d.classId)};
        env->SetFloatArrayRegion(row, 0, 6, vals);
        env->SetObjectArrayElement(result, i, row);
        env->DeleteLocalRef(row);
    }
    return result;
}
