# NCNN YOLOv8 JNI 编译配置
# NCNN SDK 目录结构: ncnn/${ANDROID_ABI}/include/ + ncnn/${ANDROID_ABI}/lib/

cmake_minimum_required(VERSION 3.18)

# NCNN 路径 (根据 ABI 自动选择)
set(NCNN_DIR ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI})

# 头文件
include_directories(${NCNN_DIR}/include)

# 链接
add_library(yolov8_ncnn_jni SHARED yolov8_ncnn_jni.cpp)

# 所有 NCNN 静态库
file(GLOB NCNN_LIBS ${NCNN_DIR}/lib/*.a)

target_link_libraries(yolov8_ncnn_jni
    ${NCNN_LIBS}
    android
    log
    jnigraphics
    z
    vulkan
    omp  # OpenMP (NCNN 内部并行)
    c++_shared  # libc++ (NDK r23 匹配 NCNN 的编译环境)
)
