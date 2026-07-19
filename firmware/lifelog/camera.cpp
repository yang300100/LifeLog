#include <Arduino.h>
#include "camera.h"
#include "camera_pins.h"
#include "config.h"
#include "configuration.h"
#include "esp_camera.h"
#include "esp_timer.h"
#include "esp_task_wdt.h"
#include <stdio.h>

bool camera_init(const AppConfig &cfg) {
    camera_config_t config;
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
    config.pin_d0 = Y2_GPIO_NUM;
    config.pin_d1 = Y3_GPIO_NUM;
    config.pin_d2 = Y4_GPIO_NUM;
    config.pin_d3 = Y5_GPIO_NUM;
    config.pin_d4 = Y6_GPIO_NUM;
    config.pin_d5 = Y7_GPIO_NUM;
    config.pin_d6 = Y8_GPIO_NUM;
    config.pin_d7 = Y9_GPIO_NUM;
    config.pin_xclk = XCLK_GPIO_NUM;
    config.pin_pclk = PCLK_GPIO_NUM;
    config.pin_vsync = VSYNC_GPIO_NUM;
    config.pin_href = HREF_GPIO_NUM;
    config.pin_sccb_sda = SIOD_GPIO_NUM;
    config.pin_sccb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn = PWDN_GPIO_NUM;
    config.pin_reset = RESET_GPIO_NUM;
    config.xclk_freq_hz = 20000000;

    // 根据 AppConfig 设置分辨率和质量（而非硬编码）
    config.frame_size = (framesize_t)cfg.video_resolution;
    config.pixel_format = PIXFORMAT_JPEG;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.jpeg_quality = cfg.video_quality;
    config.fb_count = 1;

    if (psramFound()) {
        config.fb_count = 2;
        config.grab_mode = CAMERA_GRAB_LATEST;
    }

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) return false;

    sensor_t *s = esp_camera_sensor_get();
    if (s != nullptr) {
        if (s->id.PID == OV2640_PID) {
            s->set_vflip(s, 1);
            s->set_brightness(s, 1);
            s->set_saturation(s, -2);
        } else if (s->id.PID == OV3660_PID) {
            s->set_vflip(s, 1);
            s->set_brightness(s, 0);
            s->set_saturation(s, 0);
        }
    }

    return true;
}

int camera_capture_video(const char *filepath,
                         uint32_t duration_ms,
                         uint8_t fps) {
    // 防御性检查：fps 必须 >= 1
    if (fps < 1 || fps > 30) {
        Serial.printf("[CAM] 无效 fps=%u，使用默认值 5\n", fps);
        fps = 5;
    }

    FILE *f = fopen(filepath, "wb");
    if (!f) return -1;

    uint32_t frame_interval_ms = 1000 / fps;
    uint64_t start_ms = esp_timer_get_time() / 1000;
    uint64_t end_ms = start_ms + duration_ms;
    int frame_count = 0;

    while ((esp_timer_get_time() / 1000) < end_ms) {
        uint64_t frame_start_ms = esp_timer_get_time() / 1000;

        camera_fb_t *fb = esp_camera_fb_get();
        if (!fb) {
            // 丢帧，短暂让出 CPU 后继续尝试
            vTaskDelay(1);
            continue;
        }

        // 写入帧长度头 (4 bytes LE) + JPEG 数据
        uint32_t frame_size = fb->len;
        size_t header_written = fwrite(&frame_size, 4, 1, f);
        if (header_written != 1) {
            esp_camera_fb_return(fb);
            fclose(f);
            return -2;
        }

        size_t data_written = fwrite(fb->buf, 1, fb->len, f);
        esp_camera_fb_return(fb);

        if (data_written != frame_size) {
            // SD 卡写入失败（可能卡满）
            fclose(f);
            return -2;
        }

        frame_count++;

        // 帧率控制：等待到下一帧时间
        // 注：elapsed 用 uint32_t 安全，单次录制 ≤30s，差值远小于 UINT32_MAX
        uint64_t now_ms = esp_timer_get_time() / 1000;
        uint32_t elapsed = (uint32_t)(now_ms - frame_start_ms);
        if (elapsed < frame_interval_ms) {
            vTaskDelay((frame_interval_ms - elapsed) / portTICK_PERIOD_MS);
        }

        // 定期喂看门狗（录制超过 3 秒时需要）
        // 注：仅当当前任务已订阅 TWDT 时才喂，避免 "task not found" 错误日志
        if (frame_count % 10 == 0) {
            if (esp_task_wdt_status(NULL) == ESP_OK) {
                esp_task_wdt_reset();
            }
        }
    }

    fclose(f);
    return frame_count;
}

const char* camera_framesize_name(uint8_t fs) {
    switch (fs) {
        case FRAMESIZE_QVGA: return "QVGA(320x240)";
        case FRAMESIZE_VGA:  return "VGA(640x480)";
        case FRAMESIZE_SVGA: return "SVGA(800x600)";
        case FRAMESIZE_XGA:  return "XGA(1024x768)";
        case FRAMESIZE_SXGA: return "SXGA(1280x1024)";
        case FRAMESIZE_UXGA: return "UXGA(1600x1200)";
        case FRAMESIZE_QXGA: return "QXGA(2048x1536)";
        default: return "未知";
    }
}

void camera_power_off() {
    // 释放摄像头资源（帧缓冲、DMA、时钟等）
    esp_camera_deinit();
#ifdef WITH_EVIL_CAM_PWR_SHUTDOWN
    // [已废弃] 简化硬件方案不再使用外部 MOSFET
    // 如需启用物理断电：GPIO32 拉高 → P-MOSFET 切断 OV2640 低压供电
    gpio_set_direction(GPIO_NUM_32, GPIO_MODE_OUTPUT);
    gpio_set_level(GPIO_NUM_32, 1);
#endif
}
