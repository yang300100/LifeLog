#include "configuration.h"
#include "config.h"
#include "esp_camera.h"
#include <stdio.h>
#include <string.h>

// 简单的 key=value 解析，跳过空白和注释行
static bool parse_line(const char *line, char *key, size_t key_sz,
                       char *value, size_t val_sz) {
    const char *p = line;
    while (*p == ' ' || *p == '\t') p++;
    if (*p == '#' || *p == '\0' || *p == '\n' || *p == '\r') return false;

    const char *k = p;
    while (*p && *p != '=' && *p != '\n' && *p != '\r') p++;
    size_t klen = p - k;
    if (klen >= key_sz) klen = key_sz - 1;
    memcpy(key, k, klen);
    key[klen] = '\0';

    if (*p != '=') return false;
    p++;

    while (*p == ' ' || *p == '\t') p++;
    const char *v = p;
    while (*p && *p != '\n' && *p != '\r') p++;
    size_t vlen = p - v;
    if (vlen >= val_sz) vlen = val_sz - 1;
    memcpy(value, v, vlen);
    value[vlen] = '\0';

    return true;
}

void config_set_defaults(AppConfig &cfg) {
    strcpy(cfg.capture_mode, "video");
    cfg.video_duration = VIDEO_DURATION_DEFAULT;
    cfg.video_resolution = VIDEO_RESOLUTION_DEFAULT;
    cfg.video_quality = VIDEO_QUALITY_DEFAULT;
    cfg.video_fps = VIDEO_FPS_DEFAULT;
    cfg.interval = INTERVAL_DEFAULT;
    cfg.ble_advertise_timeout = BLE_ADV_TIMEOUT_DEFAULT;
    strcpy(cfg.ble_device_name, BLE_DEVICE_NAME_DEFAULT);
    cfg.ble_adv_interval = BLE_ADV_INTERVAL_DEFAULT;
}

bool config_load(const char *filepath, AppConfig &cfg) {
    config_set_defaults(cfg);

    FILE *f = fopen(filepath, "r");
    if (!f) return false;  // 文件不存在，使用默认值

    char line[128];
    char key[64], value[64];

    while (fgets(line, sizeof(line), f)) {
        if (!parse_line(line, key, sizeof(key), value, sizeof(value)))
            continue;

        if (strcmp(key, "capture_mode") == 0)
            strncpy(cfg.capture_mode, value, sizeof(cfg.capture_mode) - 1);
        else if (strcmp(key, "video_duration") == 0) {
            cfg.video_duration = atoi(value);
            if (cfg.video_duration < 1000 || cfg.video_duration > 30000) {
                cfg.video_duration = VIDEO_DURATION_DEFAULT;
            }
        }
        else if (strcmp(key, "video_resolution") == 0) {
            if (strcmp(value, "QVGA") == 0) cfg.video_resolution = FRAMESIZE_QVGA;
            else if (strcmp(value, "VGA") == 0) cfg.video_resolution = FRAMESIZE_VGA;
            else if (strcmp(value, "SVGA") == 0) cfg.video_resolution = FRAMESIZE_SVGA;
            else if (strcmp(value, "XGA") == 0) cfg.video_resolution = FRAMESIZE_XGA;
            else if (strcmp(value, "SXGA") == 0) cfg.video_resolution = FRAMESIZE_SXGA;
            else if (strcmp(value, "UXGA") == 0) cfg.video_resolution = FRAMESIZE_UXGA;
            else if (strcmp(value, "QXGA") == 0) cfg.video_resolution = FRAMESIZE_QXGA;
        }
        else if (strcmp(key, "video_quality") == 0) {
            cfg.video_quality = atoi(value);
            if (cfg.video_quality < 10 || cfg.video_quality > 63) {
                cfg.video_quality = VIDEO_QUALITY_DEFAULT;
            }
        }
        else if (strcmp(key, "video_fps") == 0) {
            cfg.video_fps = atoi(value);
            // 防御性范围检查：fps 必须在 1-30 之间
            if (cfg.video_fps < 1 || cfg.video_fps > 30) {
                cfg.video_fps = VIDEO_FPS_DEFAULT;
            }
        }
        else if (strcmp(key, "interval") == 0) {
            cfg.interval = atoi(value);
            if (cfg.interval < 10000 || cfg.interval > 3600000) {
                cfg.interval = INTERVAL_DEFAULT;
            }
        }
        else if (strcmp(key, "ble_advertise_timeout") == 0) {
            cfg.ble_advertise_timeout = atoi(value);
            if (cfg.ble_advertise_timeout < 5000 || cfg.ble_advertise_timeout > 120000) {
                cfg.ble_advertise_timeout = BLE_ADV_TIMEOUT_DEFAULT;
            }
        }
        else if (strcmp(key, "ble_device_name") == 0)
            strncpy(cfg.ble_device_name, value, sizeof(cfg.ble_device_name) - 1);
        else if (strcmp(key, "ble_adv_interval") == 0) {
            cfg.ble_adv_interval = atoi(value);
            if (cfg.ble_adv_interval < 20 || cfg.ble_adv_interval > 1000) {
                cfg.ble_adv_interval = BLE_ADV_INTERVAL_DEFAULT;
            }
        }
    }

    fclose(f);
    return true;
}

bool config_save(const char *filepath, const AppConfig &cfg) {
    FILE *f = fopen(filepath, "w");
    if (!f) return false;

    fprintf(f, "# LifeLog Camera Configuration\n");
    fprintf(f, "capture_mode = %s\n", cfg.capture_mode);
    fprintf(f, "video_duration = %u\n", cfg.video_duration);
    fprintf(f, "video_resolution = %s\n",
            cfg.video_resolution == FRAMESIZE_QVGA ? "QVGA" :
            cfg.video_resolution == FRAMESIZE_VGA  ? "VGA"  :
            cfg.video_resolution == FRAMESIZE_SVGA ? "SVGA" :
            cfg.video_resolution == FRAMESIZE_XGA  ? "XGA"  :
            cfg.video_resolution == FRAMESIZE_SXGA ? "SXGA" :
            cfg.video_resolution == FRAMESIZE_UXGA ? "UXGA" : "QXGA");
    fprintf(f, "video_quality = %u\n", cfg.video_quality);
    fprintf(f, "video_fps = %u\n", cfg.video_fps);
    fprintf(f, "interval = %u\n", cfg.interval);
    fprintf(f, "ble_advertise_timeout = %u\n", cfg.ble_advertise_timeout);
    fprintf(f, "ble_device_name = %s\n", cfg.ble_device_name);
    fprintf(f, "ble_adv_interval = %u\n", cfg.ble_adv_interval);

    fclose(f);
    return true;
}
