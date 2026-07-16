#ifndef LIFELOG_CAMERA_H
#define LIFELOG_CAMERA_H

#include <stdint.h>
#include <stdbool.h>

// 前向声明
struct AppConfig;

// 初始化摄像头（根据 AppConfig 设置分辨率和质量）
// 调用前确保 config_set_defaults 已填充合理默认值
bool camera_init(const AppConfig &cfg);

// 录制 MJPEG 视频到文件
// 返回实际录制的帧数，-1=文件打开失败, -2=SD写入失败
int camera_capture_video(const char *filepath,
                         uint32_t duration_ms,
                         uint8_t fps);

// 关闭摄像头电源 (Deep Sleep 前调用)
void camera_power_off();

#endif
