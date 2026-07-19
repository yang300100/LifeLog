#ifndef LIFELOG_CONFIGURATION_H
#define LIFELOG_CONFIGURATION_H

#include <stdint.h>

struct AppConfig {
    char capture_mode[8];       // "photo" or "video"
    uint32_t video_duration;    // ms
    uint8_t  video_resolution;  // framesize_t enum value
    uint8_t  video_quality;     // 10-63
    uint8_t  video_fps;
    uint32_t interval;          // ms between captures
    uint32_t flash_threshold;   // JPEG bytes，低于此值开启闪光灯
    uint32_t ble_advertise_timeout;  // ms
    char ble_device_name[32];
    uint16_t ble_adv_interval;  // ms (20/100/500)
};

// 全局配置实例（lifelog.ino 中定义，其他模块可通过此声明访问）
extern AppConfig g_cfg;

// 加载默认值
void config_set_defaults(AppConfig &cfg);

// 从 SD 卡读取 camera.cfg 并解析
bool config_load(const char *filepath, AppConfig &cfg);

// 保存配置到 SD 卡
bool config_save(const char *filepath, const AppConfig &cfg);

#endif
